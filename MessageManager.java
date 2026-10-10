package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Красивые сообщения в чате: вход (в том числе первый вход с порядковым номером),
 * выход, смерти (по причинам), убийства и серии убийств. Тексты настраиваются в config.yml (messages).
 */
public class MessageManager implements Listener {

    private static final Map<String, String> DEF = new HashMap<>();
    private static final List<String> DEF_FIRST = Arrays.asList(
            "&8&m                                        ",
            " &6&l✦ &eИгрок &f{player} &eприсоединился к нам впервые! &6&l✦",
            " &7Он зашёл &e{number}&7-м по счёту. Добро пожаловать!",
            "&8&m                                        "
    );
    private static final Map<String, String> MOBS = new HashMap<>();

    static {
        DEF.put("join", "&8[&a+&8] {rank} &f{player} &7присоединился к серверу");
        DEF.put("quit", "&8[&c-&8] {rank} &f{player} &7покинул сервер");
        DEF.put("killstreak", "&6&l⚔ &e{killer} &7убил &c{streak} &7игроков подряд!");
        DEF.put("death.player", "&8[&c☠&8] &f{victim} &7был убит игроком &c{killer} &8(осталось &c{hp}❤&8)");
        DEF.put("death.mob", "&8[&c☠&8] &f{victim} &7был убит &c{mob}");
        DEF.put("death.fall", "&8[&c☠&8] &f{victim} &7разбился при падении");
        DEF.put("death.fire", "&8[&c☠&8] &f{victim} &7сгорел заживо");
        DEF.put("death.lava", "&8[&c☠&8] &f{victim} &7утонул в лаве");
        DEF.put("death.drown", "&8[&c☠&8] &f{victim} &7утонул");
        DEF.put("death.void", "&8[&c☠&8] &f{victim} &7упал в бездну");
        DEF.put("death.suffocate", "&8[&c☠&8] &f{victim} &7задохнулся в стене");
        DEF.put("death.starve", "&8[&c☠&8] &f{victim} &7умер от голода");
        DEF.put("death.explosion", "&8[&c☠&8] &f{victim} &7был взорван");
        DEF.put("death.lightning", "&8[&c☠&8] &f{victim} &7поражён молнией");
        DEF.put("death.poison", "&8[&c☠&8] &f{victim} &7умер от яда и магии");
        DEF.put("death.cactus", "&8[&c☠&8] &f{victim} &7накололся на кактус");
        DEF.put("death.magma", "&8[&c☠&8] &f{victim} &7сгорел на магме");
        DEF.put("death.wall", "&8[&c☠&8] &f{victim} &7влетел в стену на элитрах");
        DEF.put("death.block", "&8[&c☠&8] &f{victim} &7был раздавлен упавшим блоком");
        DEF.put("death.suicide", "&8[&c☠&8] &f{victim} &7покончил с собой");
        DEF.put("death.other", "&8[&c☠&8] &f{victim} &7погиб");
        MOBS.put("ZOMBIE", "зомби");
        MOBS.put("ZOMBIE_VILLAGER", "зомби");
        MOBS.put("HUSK", "кадавром");
        MOBS.put("DROWNED", "утопленником");
        MOBS.put("SKELETON", "скелетом");
        MOBS.put("STRAY", "бродягой");
        MOBS.put("WITHER_SKELETON", "скелетом-иссушителем");
        MOBS.put("CREEPER", "крипером");
        MOBS.put("SPIDER", "пауком");
        MOBS.put("CAVE_SPIDER", "пещерным пауком");
        MOBS.put("ENDERMAN", "эндерменом");
        MOBS.put("WITCH", "ведьмой");
        MOBS.put("BLAZE", "ифритом");
        MOBS.put("GHAST", "гастом");
        MOBS.put("SLIME", "слизнем");
        MOBS.put("MAGMA_CUBE", "магмовым кубом");
        MOBS.put("PHANTOM", "фантомом");
        MOBS.put("PILLAGER", "налётчиком");
        MOBS.put("VINDICATOR", "поборником");
        MOBS.put("EVOKER", "заклинателем");
        MOBS.put("RAVAGER", "разорителем");
        MOBS.put("WITHER", "иссушителем");
        MOBS.put("ENDER_DRAGON", "драконом Края");
        MOBS.put("PIGLIN", "пиглином");
        MOBS.put("PIGLIN_BRUTE", "пиглином-громилой");
        MOBS.put("ZOMBIFIED_PIGLIN", "зомби-пиглином");
        MOBS.put("HOGLIN", "хоглином");
        MOBS.put("ZOGLIN", "зоглином");
        MOBS.put("WOLF", "волком");
        MOBS.put("IRON_GOLEM", "железным големом");
        MOBS.put("POLAR_BEAR", "белым медведем");
        MOBS.put("BEE", "пчелой");
        MOBS.put("GUARDIAN", "стражем");
        MOBS.put("ELDER_GUARDIAN", "древним стражем");
        MOBS.put("SHULKER", "шалкером");
        MOBS.put("VEX", "вексом");
        MOBS.put("SILVERFISH", "чешуйницей");
        MOBS.put("ENDERMITE", "эндермитом");
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration joins;
    private final Map<UUID, Integer> streak = new HashMap<>();

    MessageManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "joins.yml");
        joins = YamlConfiguration.loadConfiguration(file);
        if (!joins.contains("count")) {
            // уже игравшие считаются: новые игроки получат номера после них
            int n = 0;
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.hasPlayedBefore()) n++;
            }
            joins.set("count", n);
            save();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void disable() {
        save();
    }

    private void save() {
        try {
            joins.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить joins.yml: " + e.getMessage());
        }
    }

    // ---------- вход и выход ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.setJoinMessage(null);

        String path = "joined." + p.getUniqueId();
        int number;
        boolean first = false;
        if (!joins.contains(path)) {
            number = joins.getInt("count", 0) + 1;
            joins.set("count", number);
            joins.set(path, number);
            save();
            first = !p.hasPlayedBefore();
        } else {
            number = joins.getInt(path);
        }

        if (first) {
            List<String> lines = plugin.getConfig().getStringList("messages.join-first");
            if (lines.isEmpty()) lines = DEF_FIRST;
            for (String line : lines) {
                Bukkit.broadcastMessage(plugin.format(line.replace("{number}", String.valueOf(number)), p));
            }
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.playSound(o.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            }
        } else {
            Bukkit.broadcastMessage(plugin.format(msg("join").replace("{number}", String.valueOf(number)), p));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.setQuitMessage(null);
        streak.remove(p.getUniqueId());
        Bukkit.broadcastMessage(plugin.format(msg("quit"), p));
    }

    // ---------- смерти и убийства ----------

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        e.setDeathMessage(null);

        String key;
        String mob = "";
        String killerName = "";
        String hp = "";
        Player killer = victim.getKiller();
        EntityDamageEvent last = victim.getLastDamageCause();

        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            key = "player";
            killerName = killer.getName();
            hp = String.format(Locale.US, "%.1f", killer.getHealth() / 2.0);
        } else if (last instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) last).getDamager();
            if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Entity) {
                d = (Entity) ((Projectile) d).getShooter();
            }
            if (d instanceof Player && !d.getUniqueId().equals(victim.getUniqueId())) {
                key = "player";
                killerName = d.getName();
                hp = String.format(Locale.US, "%.1f", ((Player) d).getHealth() / 2.0);
                killer = (Player) d;
            } else {
                key = "mob";
                mob = mobName(d);
            }
        } else if (last != null) {
            key = causeKey(last.getCause());
        } else {
            key = "other";
        }

        String text = msg("death." + key)
                .replace("{victim}", victim.getName())
                .replace("{killer}", killerName)
                .replace("{mob}", mob)
                .replace("{hp}", hp);
        Bukkit.broadcastMessage(plugin.colorize(text));

        // серия убийств
        streak.remove(victim.getUniqueId());
        if (killer != null && "player".equals(key)) {
            int n = streak.merge(killer.getUniqueId(), 1, Integer::sum);
            List<Integer> marks = plugin.getConfig().getIntegerList("messages.killstreak-at");
            if (marks.isEmpty()) marks = Arrays.asList(3, 5, 7, 10, 15, 20, 30);
            if (marks.contains(n)) {
                Bukkit.broadcastMessage(plugin.colorize(msg("killstreak")
                        .replace("{killer}", killer.getName())
                        .replace("{streak}", String.valueOf(n))));
            }
        }
    }

    private String causeKey(EntityDamageEvent.DamageCause cause) {
        switch (cause.name()) {
            case "FALL": return "fall";
            case "FIRE":
            case "FIRE_TICK": return "fire";
            case "LAVA": return "lava";
            case "DROWNING": return "drown";
            case "VOID": return "void";
            case "SUFFOCATION": return "suffocate";
            case "STARVATION": return "starve";
            case "BLOCK_EXPLOSION":
            case "ENTITY_EXPLOSION": return "explosion";
            case "LIGHTNING": return "lightning";
            case "POISON":
            case "MAGIC":
            case "WITHER": return "poison";
            case "CONTACT": return "cactus";
            case "HOT_FLOOR": return "magma";
            case "FLY_INTO_WALL": return "wall";
            case "FALLING_BLOCK": return "block";
            case "SUICIDE": return "suicide";
            default: return "other";
        }
    }

    private String mobName(Entity d) {
        if (d.getCustomName() != null) return d.getCustomName();
        String ru = MOBS.get(d.getType().name());
        if (ru != null) return ru;
        return d.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String msg(String key) {
        return plugin.getConfig().getString("messages." + key, DEF.getOrDefault(key, key));
    }
}
