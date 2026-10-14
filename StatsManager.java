package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Статистика убийств и смертей игроков, таблица лидеров (/top и голограмма на спавне).
 * Данные хранятся в plugins/GriefBoard/stats.yml.
 */
public class StatsManager implements Listener, CommandExecutor {

    private static class Rec {
        String name = "?";
        int kills;
        int deaths;
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration yml;
    private final Map<UUID, Rec> recs = new HashMap<>();
    private final Map<String, Long> lastKill = new HashMap<>();

    StatsManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "stats.yml");
        yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = yml.getConfigurationSection("players");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Rec r = new Rec();
                    r.name = cs.getString(key + ".name", "?");
                    r.kills = cs.getInt(key + ".kills");
                    r.deaths = cs.getInt(key + ".deaths");
                    recs.put(id, r);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("top") != null) plugin.getCommand("top").setExecutor(this);
    }

    void disable() {
        save();
    }

    private void save() {
        yml.set("players", null);
        for (Map.Entry<UUID, Rec> e : recs.entrySet()) {
            String b = "players." + e.getKey() + ".";
            yml.set(b + "name", e.getValue().name);
            yml.set(b + "kills", e.getValue().kills);
            yml.set(b + "deaths", e.getValue().deaths);
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить stats.yml: " + ex.getMessage());
        }
    }

    /** Запись игрока. Для нового игрока стартовые значения берутся из обычной статистики игры. */
    private Rec ensure(Player p) {
        Rec r = recs.get(p.getUniqueId());
        if (r == null) {
            r = new Rec();
            r.kills = p.getStatistic(Statistic.PLAYER_KILLS);
            r.deaths = p.getStatistic(Statistic.DEATHS);
            recs.put(p.getUniqueId(), r);
        }
        r.name = p.getName();
        return r;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        boolean isNew = !recs.containsKey(e.getPlayer().getUniqueId());
        ensure(e.getPlayer());
        if (isNew) save();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        boolean victimNew = !recs.containsKey(victim.getUniqueId());
        Rec vr = ensure(victim);
        if (!victimNew) vr.deaths++;

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            boolean killerNew = !recs.containsKey(killer.getUniqueId());
            Rec kr = ensure(killer);
            String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastKill.get(pair);
            long cd = plugin.getConfig().getInt("stats.kill-cooldown-seconds", 600) * 1000L;
            boolean farming = last != null && now - last < cd;
            if (!killerNew && !farming) kr.kills++;
            if (!farming) lastKill.put(pair, now);
        }
        save();
    }

    // ---------- таблица лидеров ----------

    private List<Rec> top(int n) {
        List<Rec> list = new ArrayList<>();
        for (Rec r : recs.values()) {
            if (r.kills > 0) list.add(r);
        }
        list.sort((a, b) -> {
            if (a.kills != b.kills) return Integer.compare(b.kills, a.kills);
            if (a.deaths != b.deaths) return Integer.compare(a.deaths, b.deaths);
            return a.name.compareToIgnoreCase(b.name);
        });
        return list.size() > n ? list.subList(0, n) : list;
    }

    private static String place(int i, Rec r) {
        String num = i == 0 ? "&6&l1. &f&l" : i == 1 ? "&f&l2. &f&l" : i == 2 ? "&c&l3. &f&l" : "&e" + (i + 1) + ". &f";
        return num + r.name + " &8| &c" + r.kills + " ⚔";
    }

    /** Строки голограммы: заголовок, разделитель, N мест и подпись. */
    List<String> hologramLines() {
        int size = Math.max(3, Math.min(15, plugin.getConfig().getInt("holograms.top.size", 10)));
        List<Rec> top = top(size);
        List<String> out = new ArrayList<>();
        out.add("&6&l⚔ ТОП " + size + " ПО УБИЙСТВАМ ⚔");
        out.add("&8&m                              ");
        for (int i = 0; i < size; i++) {
            out.add(i < top.size() ? place(i, top.get(i)) : "&8" + (i + 1) + ". &8—");
        }
        out.add("&8&m                              ");
        out.add("&7Убивай игроков и попади в топ!");
        return out;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        List<Rec> top = top(10);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8&m          &r &6&lТоп 10 по убийствам &8&m          "));
        if (top.isEmpty()) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&7Пока никто не убивал игроков."));
        }
        for (int i = 0; i < top.size(); i++) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', " " + place(i, top.get(i))
                    + " &8(смертей: &7" + top.get(i).deaths + "&8)"));
        }
        return true;
    }
}
