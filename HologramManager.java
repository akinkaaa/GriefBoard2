package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Голограммы на спавне (невидимые стойки для брони с видимым именем).
 * Список голограмм задаётся в config.yml (holograms.list). Если его нет, используются встроенные
 * голограммы блоков привата и справка о приватах.
 */
public class HologramManager {

    private static final String TAG = "gb_holo";

    private static class Def {
        final String id;
        final double x, y, z;
        final double offset;
        final List<String> lines;

        Def(String id, double x, double y, double z, double offset, List<String> lines) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.offset = offset;
            this.lines = lines;
        }
    }

    private final GriefBoard plugin;
    private final Map<String, List<UUID>> stands = new HashMap<>();
    private final Set<String> forcedChunks = new HashSet<>();
    private BukkitTask task;
    private boolean cleaned = false;

    HologramManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        // мир может загрузиться чуть позже (Multiverse), поэтому проверяем периодически
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::ensure, 40L, 100L);
    }

    void reload() {
        removeAll();
        cleaned = false;
        ensure();
    }

    void disable() {
        if (task != null) task.cancel();
        removeAll();
        World w = world();
        if (w != null) {
            for (String key : forcedChunks) {
                String[] p = key.split(",");
                try {
                    w.setChunkForceLoaded(Integer.parseInt(p[0]), Integer.parseInt(p[1]), false);
                } catch (NumberFormatException ignored) { }
            }
        }
        forcedChunks.clear();
    }

    private World world() {
        return Bukkit.getWorld(plugin.getConfig().getString("holograms.world",
                plugin.getConfig().getString("rtp.lobby-world", "lobby")));
    }

    // ---------- сами голограммы ----------

    private void ensure() {
        World w = world();
        if (w == null) return;
        List<Def> defs = defs();
        if (!cleaned) {
            // удаляем возможные остатки от прошлого запуска и держим чанки загруженными
            for (Def d : defs) {
                Chunk ch = new Location(w, d.x, d.y, d.z).getChunk();
                String key = ch.getX() + "," + ch.getZ();
                if (forcedChunks.add(key)) w.setChunkForceLoaded(ch.getX(), ch.getZ(), true);
                ch.load();
                for (Entity en : ch.getEntities()) {
                    if (en instanceof ArmorStand && en.getScoreboardTags().contains(TAG)) en.remove();
                }
            }
            stands.clear();
            cleaned = true;
        }
        for (Def d : defs) {
            List<UUID> list = stands.get(d.id);
            if (list != null && allValid(list)) continue;
            if (list != null) {
                for (UUID id : list) {
                    Entity en = Bukkit.getEntity(id);
                    if (en != null) en.remove();
                }
            }
            stands.put(d.id, spawn(w, d));
        }
    }

    private boolean allValid(List<UUID> list) {
        for (UUID id : list) {
            Entity en = Bukkit.getEntity(id);
            if (en == null || !en.isValid()) return false;
        }
        return true;
    }

    private List<UUID> spawn(World w, Def d) {
        List<UUID> out = new ArrayList<>();
        double spacing = plugin.getConfig().getDouble("holograms.spacing", 0.27);
        int n = d.lines.size();
        for (int i = 0; i < n; i++) {
            final String text = ChatColor.translateAlternateColorCodes('&', d.lines.get(i));
            if (text.trim().isEmpty()) continue; // пустая строка просто даёт отступ
            double y = d.y + d.offset + (n - 1 - i) * spacing;
            Location loc = new Location(w, d.x, y, d.z);
            ArmorStand st = w.spawn(loc, ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                a.setCollidable(false);
                a.setBasePlate(false);
                a.setArms(false);
                a.setCustomName(text);
                a.setCustomNameVisible(true);
                a.setPersistent(false);
                a.addScoreboardTag(TAG);
            });
            out.add(st.getUniqueId());
        }
        return out;
    }

    private void removeAll() {
        for (List<UUID> list : stands.values()) {
            for (UUID id : list) {
                Entity en = Bukkit.getEntity(id);
                if (en != null) en.remove();
            }
        }
        stands.clear();
    }

    // ---------- список голограмм ----------

    private List<Def> defs() {
        List<Def> out = new ArrayList<>();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("holograms.list");
        double globalOffset = plugin.getConfig().getDouble("holograms.offset-y", 0.9);
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(id);
                if (s == null) continue;
                out.add(new Def(id, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                        s.getDouble("offset-y", globalOffset), s.getStringList("lines")));
            }
            if (!out.isEmpty()) return out;
        }
        return defaults();
    }

    private static List<String> block(String color, String name, String size) {
        return Arrays.asList("&e&lБлок привата", color + "&l" + name, "&fЗона: &e" + size);
    }

    private List<Def> defaults() {
        List<Def> d = new ArrayList<>();
        d.add(new Def("gold_ore", 1.572, 66.5, 46.535, 1.0, block("&6", "Золотая руда", "9×16×9")));
        d.add(new Def("gold_block", -4.422, 66.5, 46.657, 1.0, block("&6", "Золотой блок", "13×16×13")));
        d.add(new Def("diamond_ore", 3.653, 66.5, 46.528, 1.0, block("&b", "Алмазная руда", "17×20×17")));
        d.add(new Def("diamond_block", -2.658, 66.5, 46.542, 1.0, block("&b", "Алмазный блок", "21×24×21")));
        d.add(new Def("emerald_ore", 5.532, 66.5, 46.482, 1.0, block("&a", "Изумрудная руда", "25×24×25")));
        d.add(new Def("emerald_block", -0.478, 66.5, 46.620, 1.0, block("&a", "Изумрудный блок", "29×32×29")));
        d.add(new Def("info", 0.424, 65.7939, 50.986, 0.2, Arrays.asList(
                "&6&l✦ ПРИВАТЫ ✦",
                "&7Защита территории от грифа",
                "",
                "&fПоставь блок привата, и зона вокруг",
                "&fнего станет защищённой: ломать, строить",
                "&fи открывать сундуки сможешь только ты",
                "&fи добавленные тобой игроки.",
                "",
                "&6Золотая руда &f9×16×9 &8| &6блок &f13×16×13",
                "&bАлмазная руда &f17×20×17 &8| &bблок &f21×24×21",
                "&aИзумрудная руда &f25×24×25 &8| &aблок &f29×32×29",
                "",
                "&e/privat add <ник> &7- добавить игрока",
                "&e/privat info &7- информация, &e/privat show &7- границы",
                "&7Снять приват: сломай блок привата")));
        return d;
    }
}
