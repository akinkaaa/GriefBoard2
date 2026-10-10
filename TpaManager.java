package ru.griefboard;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Телепорт к игрокам: /tpa, /tpahere, /tpaccept, /tpdeny, /tpcancel, /tptoggle.
 * Запрос действует ограниченное время, телепорт идёт с задержкой (нужно стоять на месте),
 * на спавн и со спавна телепортироваться нельзя (выход в мир только через /rtp).
 */
public class TpaManager implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "&8[&6ТП&8] ";

    private static class Request {
        final UUID from;
        final boolean here;      // true = /tpahere: к отправителю идёт получатель
        final long expires;

        Request(UUID from, boolean here, long expires) {
            this.from = from;
            this.here = here;
            this.expires = expires;
        }
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration yml;
    private final Set<UUID> disabled = new HashSet<>();
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> warming = new HashSet<>();

    TpaManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "tpa.yml");
        yml = YamlConfiguration.loadConfiguration(file);
        for (String s : yml.getStringList("disabled")) {
            try { disabled.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (String name : new String[]{"tpa", "tpahere", "tpaccept", "tpdeny", "tpcancel", "tptoggle"}) {
            if (plugin.getCommand(name) != null) {
                plugin.getCommand(name).setExecutor(this);
                plugin.getCommand(name).setTabCompleter(this);
            }
        }
    }

    void disable() {
        save();
    }

    private void save() {
        List<String> list = new ArrayList<>();
        for (UUID id : disabled) list.add(id.toString());
        yml.set("disabled", list);
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить tpa.yml: " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        incoming.remove(id);
        for (LinkedHashMap<UUID, Request> map : incoming.values()) map.remove(id);
        warming.remove(id);
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "tpa": request(p, args, false); break;
            case "tpahere": request(p, args, true); break;
            case "tpaccept": accept(p, args); break;
            case "tpdeny": deny(p, args); break;
            case "tpcancel": cancel(p); break;
            case "tptoggle": toggle(p); break;
            default: break;
        }
        return true;
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length != 1) {
            say(p, "&cИспользуй: &e/" + (here ? "tpahere" : "tpa") + " <ник>");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { say(p, "&cИгрок не в сети."); return; }
        if (t.getUniqueId().equals(p.getUniqueId())) { say(p, "&cНа себя телепортироваться не нужно."); return; }
        if (lobbyBlocked(p, p, t)) return;
        if (disabled.contains(t.getUniqueId())) {
            say(p, "&cУ игрока отключены запросы телепортации.");
            return;
        }
        LinkedHashMap<UUID, Request> map = incoming.computeIfAbsent(t.getUniqueId(), k -> new LinkedHashMap<>());
        clean(map);
        if (map.containsKey(p.getUniqueId())) { say(p, "&cТы уже отправил запрос этому игроку."); return; }
        long ttl = cfgInt("expire-seconds", 60) * 1000L;
        map.put(p.getUniqueId(), new Request(p.getUniqueId(), here, System.currentTimeMillis() + ttl));

        say(p, "&aЗапрос отправлен игроку &f" + t.getName() + "&a. Отменить: &e/tpcancel");
        TextComponent text = comp(PREFIX + "&f" + p.getName() + (here
                ? " &7просит тебя телепортироваться к нему. "
                : " &7просит телепортироваться к тебе. "));
        TextComponent yes = comp("&a&l[ПРИНЯТЬ] ");
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + p.getName()));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(c("&7Нажми, чтобы принять")).create()));
        TextComponent no = comp("&c&l[ОТКЛОНИТЬ]");
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpdeny " + p.getName()));
        no.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(c("&7Нажми, чтобы отклонить")).create()));
        t.spigot().sendMessage(text, yes, no);
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
    }

    private void accept(Player me, String[] args) {
        Request r = pick(me, args);
        if (r == null) return;
        incoming.get(me.getUniqueId()).remove(r.from);
        Player from = Bukkit.getPlayer(r.from);
        if (from == null) { say(me, "&cИгрок уже вышел."); return; }
        if (lobbyBlocked(me, from, me)) return;

        Player mover = r.here ? me : from;
        Player dest = r.here ? from : me;
        say(from, "&f" + me.getName() + " &aпринял запрос.");
        startTeleport(mover, dest);
    }

    private void deny(Player me, String[] args) {
        Request r = pick(me, args);
        if (r == null) return;
        incoming.get(me.getUniqueId()).remove(r.from);
        say(me, "&7Запрос отклонён.");
        Player from = Bukkit.getPlayer(r.from);
        if (from != null) say(from, "&f" + me.getName() + " &cотклонил твой запрос.");
    }

    private void cancel(Player p) {
        int n = 0;
        for (LinkedHashMap<UUID, Request> map : incoming.values()) {
            if (map.remove(p.getUniqueId()) != null) n++;
        }
        say(p, n > 0 ? "&7Твои запросы отменены (" + n + ")." : "&cУ тебя нет активных запросов.");
    }

    private void toggle(Player p) {
        UUID id = p.getUniqueId();
        if (disabled.remove(id)) {
            say(p, "&aЗапросы телепортации &fвключены&a. Игроки снова могут отправлять тебе &e/tpa&a.");
        } else {
            disabled.add(id);
            incoming.remove(id);
            say(p, "&cЗапросы телепортации &fвыключены&c. Включить обратно: &e/tptoggle");
        }
        save();
    }

    // ---------- телепорт ----------

    private void startTeleport(Player mover, Player dest) {
        UUID id = mover.getUniqueId();
        if (warming.contains(id)) { say(mover, "&cТелепорт уже идёт."); return; }
        long cd = cfgInt("cooldown-seconds", 30) * 1000L;
        Long last = cooldown.get(id);
        if (last != null && System.currentTimeMillis() - last < cd) {
            say(mover, "&cПодожди ещё " + ((cd - (System.currentTimeMillis() - last)) / 1000 + 1) + " сек. до следующего телепорта.");
            return;
        }
        int warm = cfgInt("warmup-seconds", 5);
        final Location start = mover.getLocation();
        warming.add(id);
        say(mover, "&7Телепорт к &f" + (mover == dest ? "" : dest.getName())
                + " &7через &e" + warm + " &7сек. Не двигайся!");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warming.remove(id);
            if (!mover.isOnline()) return;
            if (!dest.isOnline()) { say(mover, "&cИгрок уже вышел."); return; }
            if (mover.getWorld() != start.getWorld() || mover.getLocation().distanceSquared(start) > 1.5) {
                say(mover, "&cТелепорт отменён: ты двигался.");
                return;
            }
            if (lobbyBlocked(mover, mover, dest)) return;
            mover.teleport(dest.getLocation());
            cooldown.put(id, System.currentTimeMillis());
            say(mover, "&aТы телепортирован к &f" + dest.getName());
            mover.playSound(mover.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        }, warm * 20L);
    }

    /** Со спавна и на спавн телепортироваться нельзя: выход в мир только через /rtp. */
    private boolean lobbyBlocked(Player notify, Player a, Player b) {
        if (!plugin.getConfig().getBoolean("tpa.block-lobby", true)) return false;
        String lobby = plugin.getConfig().getString("rtp.lobby-world", "lobby");
        if (a.getWorld().getName().equals(lobby) || b.getWorld().getName().equals(lobby)) {
            say(notify, "&cСо спавна и на спавн телепортироваться нельзя. Выход в мир только через &e/rtp&c.");
            return true;
        }
        return false;
    }

    // ---------- вспомогательное ----------

    /** Выбирает запрос: по нику из команды или самый свежий. */
    private Request pick(Player me, String[] args) {
        LinkedHashMap<UUID, Request> map = incoming.get(me.getUniqueId());
        if (map != null) clean(map);
        if (map == null || map.isEmpty()) {
            say(me, "&cУ тебя нет запросов телепортации.");
            return null;
        }
        if (args.length >= 1) {
            Player from = Bukkit.getPlayerExact(args[0]);
            Request r = from == null ? null : map.get(from.getUniqueId());
            if (r == null) say(me, "&cЗапроса от этого игрока нет.");
            return r;
        }
        Request last = null;
        for (Request r : map.values()) last = r;
        return last;
    }

    private void clean(Map<UUID, Request> map) {
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expires < now);
    }

    private TextComponent comp(String legacy) {
        TextComponent t = new TextComponent();
        for (BaseComponent b : TextComponent.fromLegacyText(c(legacy))) t.addExtra(b);
        return t;
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("tpa." + key, def);
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && Arrays.asList("tpa", "tpahere", "tpaccept", "tpdeny").contains(n)) {
            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        }
        return out;
    }
}
