package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Команда /near: игроки поблизости с расстоянием и направлением (по сторонам света).
 */
public class NearManager implements CommandExecutor {

    private static final String PREFIX = "&8[&6Рядом&8] ";
    private static final String[] DIRS = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};

    private final GriefBoard plugin;
    private final Map<UUID, Long> last = new HashMap<>();

    NearManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        if (plugin.getCommand("near") != null) plugin.getCommand("near").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getInt("near.cooldown-seconds", 5) * 1000L;
        Long prev = last.get(p.getUniqueId());
        if (!p.hasPermission("griefboard.admin") && prev != null && now - prev < cd) {
            say(p, "&cПодожди ещё " + ((cd - (now - prev)) / 1000 + 1) + " сек.");
            return true;
        }
        last.put(p.getUniqueId(), now);

        int radius = Math.max(10, plugin.getConfig().getInt("near.radius", 150));
        int max = Math.max(1, plugin.getConfig().getInt("near.max-list", 15));
        Location me = p.getLocation();

        List<Player> found = new ArrayList<>();
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.getUniqueId().equals(p.getUniqueId())) continue;
            if (!t.getWorld().equals(p.getWorld())) continue;
            if (t.getLocation().distance(me) <= radius) found.add(t);
        }
        found.sort(Comparator.comparingDouble(t -> t.getLocation().distanceSquared(me)));

        p.sendMessage(c("&8&m          &r &6Игроки рядом &8(&7до " + radius + " м&8) &8&m          "));
        if (found.isEmpty()) {
            p.sendMessage(c("&7  Поблизости никого нет."));
            return true;
        }
        int shown = 0;
        for (Player t : found) {
            if (shown++ >= max) break;
            Location l = t.getLocation();
            int dist = (int) Math.round(l.distance(me));
            p.sendMessage(c("  &8● " + plugin.format("{rank}", t) + " &f" + t.getName() + " &8- &e" + dist + " м &7"
                    + direction(l.getX() - me.getX(), l.getZ() - me.getZ())
                    + (plugin.sameClan(p, t) ? " &a[клан]" : "")));
        }
        if (found.size() > max) p.sendMessage(c("  &7...и ещё " + (found.size() - max)));
        return true;
    }

    /** Сторона света: север там, где Z уменьшается, восток там, где X растёт. */
    private static String direction(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        if (ang < 0) ang += 360;
        return DIRS[(int) Math.round(ang / 45.0) % 8];
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
