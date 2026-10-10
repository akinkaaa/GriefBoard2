package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Команда /spawn: телепорт на спавн (мир lobby) через несколько секунд.
 * Пока идёт отсчёт, нужно стоять на месте, а сверху показывается полоска-таймер (боссбар).
 */
public class SpawnManager implements Listener, CommandExecutor {

    private static final String PREFIX = "&8[&6Спавн&8] ";

    private static class Session {
        final Location start;
        final long endAt;
        final long total;
        final BossBar bar;
        BukkitRunnable task;
        int lastSec = -1;

        Session(Location start, long total, BossBar bar) {
            this.start = start;
            this.total = total;
            this.endAt = System.currentTimeMillis() + total;
            this.bar = bar;
        }
    }

    private final GriefBoard plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    SpawnManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("spawn") != null) plugin.getCommand("spawn").setExecutor(this);
    }

    void disable() {
        for (UUID id : new ArrayList<>(sessions.keySet())) end(id, false, null);
    }

    // ---------- команда ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        UUID id = p.getUniqueId();
        Location target = spawnLocation();
        if (target == null) { say(p, "&cСпавн не найден. Проверь название мира в config.yml (spawn.world)."); return true; }
        if (p.getWorld().getName().equals(target.getWorld().getName())) { say(p, "&cТы уже на спавне."); return true; }
        if (sessions.containsKey(id)) { say(p, "&cТелепорт уже идёт. Не двигайся!"); return true; }

        boolean admin = p.hasPermission("griefboard.admin") && plugin.getConfig().getBoolean("spawn.admin-instant", true);
        if (admin) {
            teleport(p, target);
            return true;
        }

        long cd = plugin.getConfig().getInt("spawn.cooldown-seconds", 0) * 1000L;
        Long last = cooldown.get(id);
        if (cd > 0 && last != null && System.currentTimeMillis() - last < cd) {
            say(p, "&cПодожди ещё " + ((cd - (System.currentTimeMillis() - last)) / 1000 + 1) + " сек.");
            return true;
        }

        int warm = Math.max(1, plugin.getConfig().getInt("spawn.warmup-seconds", 5));
        BossBar bar = Bukkit.createBossBar(c("&6Телепорт на спавн: &f" + warm + " &6сек."), BarColor.YELLOW, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(p);

        final Session s = new Session(p.getLocation(), warm * 1000L, bar);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                tick(p, s);
            }
        };
        s.task = task;
        sessions.put(id, s);
        say(p, "&7Телепорт на спавн через &e" + warm + " &7сек. Не двигайся!");
        task.runTaskTimer(plugin, 0L, 5L);
        return true;
    }

    // ---------- отсчёт ----------

    private void tick(Player p, Session s) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || p.isDead()) {
            end(id, false, null);
            return;
        }
        if (p.getWorld() != s.start.getWorld() || p.getLocation().distanceSquared(s.start) > 1.5) {
            end(id, true, "&cТелепорт отменён: ты двигался.");
            return;
        }
        long left = s.endAt - System.currentTimeMillis();
        if (left <= 0) {
            end(id, false, null);
            Location target = spawnLocation();
            if (target == null) { say(p, "&cСпавн не найден."); return; }
            teleport(p, target);
            return;
        }
        int sec = (int) Math.ceil(left / 1000.0);
        s.bar.setProgress(Math.max(0.0, Math.min(1.0, left / (double) s.total)));
        s.bar.setTitle(c("&6Телепорт на спавн: &f" + sec + " &6сек."));
        if (sec != s.lastSec) {
            s.lastSec = sec;
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
        }
    }

    /** Останавливает отсчёт и убирает полоску. msg - сообщение игроку (или null). */
    private void end(UUID id, boolean cancelled, String msg) {
        Session s = sessions.remove(id);
        if (s == null) return;
        s.task.cancel();
        s.bar.removeAll();
        Player p = Bukkit.getPlayer(id);
        if (p != null && msg != null) {
            say(p, msg);
            if (cancelled) p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
        }
    }

    private void teleport(Player p, Location target) {
        p.setFallDistance(0);
        p.teleport(target);
        cooldown.put(p.getUniqueId(), System.currentTimeMillis());
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        say(p, "&aТы на спавне.");
    }

    private Location spawnLocation() {
        String name = plugin.getConfig().getString("spawn.world",
                plugin.getConfig().getString("rtp.lobby-world", "lobby"));
        World w = Bukkit.getWorld(name);
        if (w == null) return null;
        return w.getSpawnLocation().clone().add(0.5, 0, 0.5);
    }

    // ---------- события ----------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        if (!plugin.getConfig().getBoolean("spawn.cancel-on-damage", true)) return;
        UUID id = e.getEntity().getUniqueId();
        if (sessions.containsKey(id)) end(id, true, "&cТелепорт отменён: ты получил урон.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        end(e.getPlayer().getUniqueId(), false, null);
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
