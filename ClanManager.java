package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Кланы: роли и права, уровни, цвет клана, меню (/clan menu), дом клана,
 * подсветка тимы, чат клана. Данные хранятся в plugins/GriefBoard/clans.yml.
 */
public class ClanManager implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "&8[&6Клан&8] ";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_]+");

    private static final ChatColor[] COLORS = {
            ChatColor.RED, ChatColor.DARK_RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN,
            ChatColor.DARK_GREEN, ChatColor.AQUA, ChatColor.DARK_AQUA, ChatColor.BLUE, ChatColor.DARK_BLUE,
            ChatColor.LIGHT_PURPLE, ChatColor.DARK_PURPLE, ChatColor.WHITE, ChatColor.GRAY, ChatColor.DARK_GRAY};
    private static final String[] COLOR_RU = {
            "Красный", "Тёмно-красный", "Оранжевый", "Жёлтый", "Зелёный",
            "Тёмно-зелёный", "Голубой", "Бирюзовый", "Синий", "Тёмно-синий",
            "Розовый", "Фиолетовый", "Белый", "Серый", "Тёмно-серый"};
    private static final String[] DYE = {
            "RED", "RED", "ORANGE", "YELLOW", "LIME",
            "GREEN", "LIGHT_BLUE", "CYAN", "BLUE", "BLUE",
            "PINK", "PURPLE", "WHITE", "LIGHT_GRAY", "GRAY"};

    private static final String[] PERMS = {"invite", "kick", "sethome", "home", "color"};
    private static final String[] PERM_RU = {
            "Приглашать игроков", "Выгонять участников", "Ставить дом клана",
            "Телепорт в дом клана", "Менять цвет клана"};
    private static final Material[] PERM_ICON = {
            Material.PAPER, Material.IRON_SWORD, Material.RED_BED, Material.ENDER_PEARL, Material.PAINTING};

    private static class Level {
        final int points;
        final int maxMembers;
        final boolean home;
        final boolean glow;
        final List<String> effects;

        Level(int points, int maxMembers, boolean home, boolean glow, List<String> effects) {
            this.points = points;
            this.maxMembers = maxMembers;
            this.home = home;
            this.glow = glow;
            this.effects = effects;
        }
    }

    private static final List<Level> DEFAULT_LEVELS = Arrays.asList(
            new Level(0, 5, false, false, Collections.<String>emptyList()),
            new Level(100, 8, true, false, Collections.<String>emptyList()),
            new Level(300, 12, true, true, Collections.<String>emptyList()),
            new Level(700, 16, true, true, Arrays.asList("SPEED:0")),
            new Level(1500, 20, true, true, Arrays.asList("SPEED:0", "FAST_DIGGING:0")));

    private static class Clan {
        String name;
        UUID owner;
        long created;
        long points;
        ChatColor color = ChatColor.GOLD;
        String homeWorld;
        double hx, hy, hz;
        float hyaw, hpitch;
        final Set<UUID> officers = new HashSet<>();
        final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();
        final Map<String, Set<String>> perms = new HashMap<>();

        Clan() {
            perms.put("officer", new HashSet<>(Arrays.asList("invite", "kick", "sethome", "home")));
            perms.put("member", new HashSet<>(Collections.singletonList("home")));
        }
    }

    private static class Invite {
        final String clanKey;
        final String from;
        final long expires;

        Invite(String clanKey, String from, long expires) {
            this.clanKey = clanKey;
            this.from = from;
            this.expires = expires;
        }
    }

    private static class MenuHolder implements InventoryHolder {
        final String type;
        Inventory inv;

        MenuHolder(String type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration yml;
    private final Map<String, Clan> clans = new LinkedHashMap<>();
    private final Map<UUID, String> memberClan = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Set<UUID> chatMode = new HashSet<>();
    private final Set<UUID> glowing = new HashSet<>();
    private final Set<UUID> warming = new HashSet<>();
    private final Map<UUID, Long> homeCooldown = new HashMap<>();
    private final Map<String, Long> lastKill = new HashMap<>();
    private int taskId = -1;

    ClanManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    // ---------- запуск / остановка ----------

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "clans.yml");
        yml = YamlConfiguration.loadConfiguration(file);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (String name : new String[]{"clan", "cc"}) {
            if (plugin.getCommand(name) != null) {
                plugin.getCommand(name).setExecutor(this);
                plugin.getCommand(name).setTabCompleter(this);
            }
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 40L, 40L);
    }

    void disable() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (glowing.remove(p.getUniqueId())) p.setGlowing(false);
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) p.closeInventory();
        }
        save();
    }

    /** Название клана игрока или null. */
    String getClanName(UUID id) {
        Clan c = clanOfId(id);
        return c == null ? null : c.name;
    }

    /** Тег клана в цвете клана, например "[Название] ". Пусто, если игрок не в клане. */
    String getClanTag(UUID id) {
        Clan c = clanOfId(id);
        return c == null ? "" : "&8[&" + c.color.getChar() + c.name + "&8] ";
    }

    /** Уровень клана игрока (0, если не в клане). */
    int getClanLevel(UUID id) {
        Clan c = clanOfId(id);
        return c == null ? 0 : levelNum(c);
    }

    // ---------- хранение ----------

    private void load() {
        clans.clear();
        memberClan.clear();
        ConfigurationSection root = yml.getConfigurationSection("clans");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(key);
            if (cs == null) continue;
            Clan c = new Clan();
            c.name = cs.getString("name", key);
            try {
                c.owner = UUID.fromString(cs.getString("owner", ""));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            c.created = cs.getLong("created");
            c.points = cs.getLong("points", 0);
            try {
                c.color = ChatColor.valueOf(cs.getString("color", "GOLD"));
            } catch (IllegalArgumentException ignored) {
                c.color = ChatColor.GOLD;
            }
            for (String u : cs.getStringList("officers")) {
                try { c.officers.add(UUID.fromString(u)); } catch (IllegalArgumentException ignored) { }
            }
            if (cs.isList("perms.officer")) c.perms.put("officer", new HashSet<>(cs.getStringList("perms.officer")));
            if (cs.isList("perms.member")) c.perms.put("member", new HashSet<>(cs.getStringList("perms.member")));
            if (cs.isConfigurationSection("home")) {
                c.homeWorld = cs.getString("home.world");
                c.hx = cs.getDouble("home.x");
                c.hy = cs.getDouble("home.y");
                c.hz = cs.getDouble("home.z");
                c.hyaw = (float) cs.getDouble("home.yaw");
                c.hpitch = (float) cs.getDouble("home.pitch");
            }
            ConfigurationSection ms = cs.getConfigurationSection("members");
            if (ms != null) {
                for (String u : ms.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(u);
                        c.members.put(id, ms.getString(u, "?"));
                        memberClan.put(id, key);
                    } catch (IllegalArgumentException ignored) { }
                }
            }
            clans.put(key, c);
        }
    }

    private void save() {
        yml.set("clans", null);
        for (Map.Entry<String, Clan> e : clans.entrySet()) {
            String b = "clans." + e.getKey() + ".";
            Clan c = e.getValue();
            yml.set(b + "name", c.name);
            yml.set(b + "owner", c.owner.toString());
            yml.set(b + "created", c.created);
            yml.set(b + "points", c.points);
            yml.set(b + "color", c.color.name());
            List<String> off = new ArrayList<>();
            for (UUID u : c.officers) off.add(u.toString());
            yml.set(b + "officers", off);
            yml.set(b + "perms.officer", new ArrayList<>(c.perms.get("officer")));
            yml.set(b + "perms.member", new ArrayList<>(c.perms.get("member")));
            if (c.homeWorld != null) {
                yml.set(b + "home.world", c.homeWorld);
                yml.set(b + "home.x", c.hx);
                yml.set(b + "home.y", c.hy);
                yml.set(b + "home.z", c.hz);
                yml.set(b + "home.yaw", (double) c.hyaw);
                yml.set(b + "home.pitch", (double) c.hpitch);
            }
            for (Map.Entry<UUID, String> m : c.members.entrySet()) {
                yml.set(b + "members." + m.getKey(), m.getValue());
            }
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить clans.yml: " + ex.getMessage());
        }
    }

    // ---------- уровни ----------

    private List<Level> levels() {
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("clans.levels");
        if (cs == null) return DEFAULT_LEVELS;
        List<Integer> keys = new ArrayList<>();
        for (String k : cs.getKeys(false)) {
            try { keys.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) { }
        }
        Collections.sort(keys);
        List<Level> out = new ArrayList<>();
        for (int k : keys) {
            ConfigurationSection s = cs.getConfigurationSection(String.valueOf(k));
            if (s == null) continue;
            out.add(new Level(s.getInt("points", 0), s.getInt("max-members", 5),
                    s.getBoolean("home", false), s.getBoolean("glow", false), s.getStringList("effects")));
        }
        return out.isEmpty() ? DEFAULT_LEVELS : out;
    }

    private int levelNum(Clan c) {
        List<Level> ls = levels();
        int lv = 1;
        for (int i = 0; i < ls.size(); i++) {
            if (c.points >= ls.get(i).points) lv = i + 1;
        }
        return lv;
    }

    private Level level(Clan c) {
        return levels().get(levelNum(c) - 1);
    }

    private void addPoints(Clan c, long amount) {
        int before = levelNum(c);
        c.points = Math.max(0, c.points + amount);
        int after = levelNum(c);
        save();
        if (after > before) {
            Level l = level(c);
            tell(c, "&6&lНОВЫЙ УРОВЕНЬ! &7Клан достиг уровня &e" + after + "&7: до &f" + l.maxMembers + " &7игроков"
                    + (l.home ? ", дом клана" : "") + (l.glow ? ", свечение тимы" : "")
                    + (l.effects.isEmpty() ? "" : ", бонусы за уровень"));
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Clan c = clanOf(p);
        if (c != null && !p.getName().equals(c.members.get(p.getUniqueId()))) {
            c.members.put(p.getUniqueId(), p.getName());
            save();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        chatMode.remove(id);
        warming.remove(id);
        if (glowing.remove(id)) e.getPlayer().setGlowing(false);
    }

    /** Если включён режим /cc, обычный чат идёт только клану. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!chatMode.contains(p.getUniqueId())) return;
        Clan c = clanOf(p);
        if (c == null) {
            chatMode.remove(p.getUniqueId());
            return;
        }
        e.setCancelled(true);
        sendClanChat(c, p, e.getMessage());
    }

    /** Участники одного клана не бьют друг друга (clans.friendly-fire). */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (plugin.getConfig().getBoolean("clans.friendly-fire", false)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        Player attacker = null;
        Entity damager = e.getDamager();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            attacker = (Player) ((Projectile) damager).getShooter();
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        String a = memberClan.get(attacker.getUniqueId());
        if (a != null && a.equals(memberClan.get(victim.getUniqueId()))) {
            e.setCancelled(true);
        }
    }

    /** Очки клана за убийство игрока из другого клана (или без клана). */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;
        Clan kc = clanOf(killer);
        if (kc == null || kc == clanOf(victim)) return;
        int pts = cfgInt("points-per-kill", 10);
        if (pts <= 0) return;
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastKill.get(pair);
        if (last != null && now - last < cfgInt("kill-cooldown-seconds", 600) * 1000L) return;
        lastKill.put(pair, now);
        addPoints(kc, pts);
        say(killer, "&7Клану начислено &e+" + pts + " &7очков.");
    }

    // ---------- подсветка тимы, свечение, бонусы уровня ----------

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshHighlight(p);
            Clan c = clanOf(p);
            boolean shouldGlow = c != null && prefGlow(p) && level(c).glow;
            UUID id = p.getUniqueId();
            if (shouldGlow && !glowing.contains(id)) {
                p.setGlowing(true);
                glowing.add(id);
            } else if (!shouldGlow && glowing.contains(id)) {
                p.setGlowing(false);
                glowing.remove(id);
            }
            if (c != null) applyEffects(p, level(c));
        }
    }

    private void applyEffects(Player p, Level lv) {
        for (String spec : lv.effects) {
            String[] sp = spec.split(":");
            PotionEffectType type = PotionEffectType.getByName(sp[0].trim().toUpperCase(Locale.ROOT));
            if (type == null) continue;
            int amp = 0;
            if (sp.length > 1) {
                try { amp = Integer.parseInt(sp[1].trim()); } catch (NumberFormatException ignored) { }
            }
            PotionEffect existing = p.getPotionEffect(type);
            if (existing != null && existing.getAmplifier() > amp) continue;
            p.addPotionEffect(new PotionEffect(type, 80, amp, true, false, false));
        }
    }

    /**
     * Ники тимы окрашиваются в цвет клана (и видны сквозь стены) только для самого игрока:
     * команда создаётся на его личном скорборде.
     */
    private void refreshHighlight(Player viewer) {
        Scoreboard sb = plugin.boardOf(viewer);
        if (sb == null) return;
        Clan c = clanOf(viewer);
        Team t = sb.getTeam("clanmates");
        if (c == null || !prefHighlight(viewer)) {
            if (t != null) {
                try { t.unregister(); } catch (IllegalStateException ignored) { }
            }
            return;
        }
        if (t == null) t = sb.registerNewTeam("clanmates");
        t.setColor(c.color);
        t.setCanSeeFriendlyInvisibles(true);
        Set<String> want = new HashSet<>();
        for (UUID id : c.members.keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) want.add(online.getName());
        }
        for (String entry : new HashSet<>(t.getEntries())) {
            if (!want.contains(entry)) t.removeEntry(entry);
        }
        for (String name : want) {
            if (!t.hasEntry(name)) t.addEntry(name);
        }
    }

    private boolean prefHighlight(Player p) {
        return yml.getBoolean("prefs." + p.getUniqueId() + ".highlight", true);
    }

    private boolean prefGlow(Player p) {
        return yml.getBoolean("prefs." + p.getUniqueId() + ".glow", false);
    }

    private void setPref(Player p, String key, boolean value) {
        yml.set("prefs." + p.getUniqueId() + "." + key, value);
        save();
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        if (cmd.getName().equalsIgnoreCase("cc")) {
            clanChat(p, args);
            return true;
        }
        if (args.length == 0) {
            if (clanOf(p) != null) openMain(p);
            else help(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu": openMain(p); break;
            case "create": create(p, args); break;
            case "invite": invite(p, args); break;
            case "accept": accept(p); break;
            case "deny": deny(p); break;
            case "leave": leave(p); break;
            case "kick": cmdKick(p, args); break;
            case "promote": cmdPromote(p, args, true); break;
            case "demote": cmdPromote(p, args, false); break;
            case "transfer": cmdTransfer(p, args); break;
            case "disband": disband(p, args); break;
            case "color": cmdColor(p, args); break;
            case "sethome": setHome(p); break;
            case "home": home(p); break;
            case "info": info(p, args); break;
            case "list": list(p); break;
            case "chat": clanChat(p, new String[0]); break;
            case "addpoints": cmdAddPoints(p, args); break;
            default: help(p); break;
        }
        return true;
    }

    private void help(Player p) {
        say(p, "&6Команды клана:");
        p.sendMessage(c("&e/clan menu &7- меню клана (или просто &e/clan&7, если ты в клане)"));
        p.sendMessage(c("&e/clan create <название> &7- создать клан"));
        p.sendMessage(c("&e/clan invite <ник> &7- пригласить, &e/clan accept &7/ &e/clan deny"));
        p.sendMessage(c("&e/clan leave &7- выйти, &e/clan kick <ник> &7- выгнать"));
        p.sendMessage(c("&e/clan promote|demote <ник> &7- офицер, &e/clan transfer <ник> &7- передать клан"));
        p.sendMessage(c("&e/clan color <цвет> &7- цвет клана, &e/clan sethome &7и &e/clan home &7- дом клана"));
        p.sendMessage(c("&e/clan info [клан] &7- информация, &e/clan list &7- топ кланов"));
        p.sendMessage(c("&e/cc [сообщение] &7- чат клана"));
    }

    private void create(Player p, String[] args) {
        if (args.length != 2) { say(p, "&cИспользуй: &e/clan create <название>"); return; }
        if (clanOf(p) != null) { say(p, "&cТы уже в клане. Сначала выйди: &e/clan leave"); return; }
        String name = args[1];
        int min = cfgInt("min-name-length", 3);
        int max = cfgInt("max-name-length", 12);
        if (name.length() < min || name.length() > max || !NAME.matcher(name).matches()) {
            say(p, "&cНазвание от " + min + " до " + max + " символов: буквы, цифры и _.");
            return;
        }
        String key = key(name);
        if (clans.containsKey(key)) { say(p, "&cКлан с таким названием уже есть."); return; }

        Clan c = new Clan();
        c.name = name;
        c.owner = p.getUniqueId();
        c.created = System.currentTimeMillis();
        c.members.put(p.getUniqueId(), p.getName());
        clans.put(key, c);
        memberClan.put(p.getUniqueId(), key);
        save();
        Bukkit.broadcastMessage(c(PREFIX + "&f" + p.getName() + " &7создал клан &e" + name));
        say(p, "&7Открой меню клана: &e/clan menu");
    }

    private void invite(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (!can(c, p.getUniqueId(), "invite")) { say(p, "&cУ тебя нет права приглашать игроков."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/clan invite <ник>"); return; }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) { say(p, "&cИгрок не в сети."); return; }
        if (t.getUniqueId().equals(p.getUniqueId())) { say(p, "&cСебя приглашать не нужно."); return; }
        if (clanOf(t) != null) { say(p, "&cЭтот игрок уже в клане."); return; }
        int max = level(c).maxMembers;
        if (c.members.size() >= max) {
            say(p, "&cВ клане нет мест (максимум " + max + " на этом уровне). Подними уровень клана!");
            return;
        }
        long ttl = cfgInt("invite-seconds", 60) * 1000L;
        invites.put(t.getUniqueId(), new Invite(key(c.name), p.getName(), System.currentTimeMillis() + ttl));
        say(p, "&aПриглашение отправлено игроку &f" + t.getName());
        say(t, "&f" + p.getName() + " &7приглашает тебя в клан &e" + c.name
                + "&7. Принять: &a/clan accept&7, отказаться: &c/clan deny");
    }

    private void accept(Player p) {
        Invite inv = invites.get(p.getUniqueId());
        if (inv == null || inv.expires < System.currentTimeMillis()) {
            invites.remove(p.getUniqueId());
            say(p, "&cУ тебя нет действующих приглашений.");
            return;
        }
        if (clanOf(p) != null) { say(p, "&cТы уже в клане."); return; }
        Clan c = clans.get(inv.clanKey);
        if (c == null) {
            invites.remove(p.getUniqueId());
            say(p, "&cЭтот клан уже распущен.");
            return;
        }
        if (c.members.size() >= level(c).maxMembers) { say(p, "&cВ клане уже нет мест."); return; }

        invites.remove(p.getUniqueId());
        c.members.put(p.getUniqueId(), p.getName());
        memberClan.put(p.getUniqueId(), inv.clanKey);
        save();
        tell(c, "&f" + p.getName() + " &aвступил в клан!");
    }

    private void deny(Player p) {
        if (invites.remove(p.getUniqueId()) != null) say(p, "&7Приглашение отклонено.");
        else say(p, "&cУ тебя нет приглашений.");
    }

    private void leave(Player p) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        UUID id = p.getUniqueId();
        if (c.owner.equals(id)) {
            if (c.members.size() == 1) {
                disbandClan(c);
                say(p, "&7Ты вышел, клан распущен, потому что в нём больше никого не было.");
            } else {
                say(p, "&cТы владелец. Передай клан: &e/clan transfer <ник> &cили распусти: &e/clan disband");
            }
            return;
        }
        removeMember(c, id);
        save();
        say(p, "&7Ты вышел из клана &e" + c.name);
        tell(c, "&f" + p.getName() + " &7покинул клан.");
    }

    private void cmdKick(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/clan kick <ник>"); return; }
        UUID target = findMember(c, args[1]);
        if (target == null) { say(p, "&cТакого игрока нет в клане."); return; }
        doKick(p, c, target);
    }

    private void cmdPromote(Player p, String[] args, boolean up) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/clan " + args[0] + " <ник>"); return; }
        UUID target = findMember(c, args[1]);
        if (target == null) { say(p, "&cТакого игрока нет в клане."); return; }
        doPromote(p, c, target, up);
    }

    private void cmdTransfer(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/clan transfer <ник>"); return; }
        UUID target = findMember(c, args[1]);
        if (target == null) { say(p, "&cТакого игрока нет в клане."); return; }
        doTransfer(p, c, target);
    }

    private void doKick(Player p, Clan c, UUID target) {
        UUID me = p.getUniqueId();
        if (!can(c, me, "kick")) { say(p, "&cУ тебя нет права выгонять участников."); return; }
        if (target.equals(me)) { say(p, "&cСебя выгнать нельзя, используй &e/clan leave"); return; }
        if (target.equals(c.owner)) { say(p, "&cВладельца выгнать нельзя."); return; }
        if (roleLevel(c, target) >= roleLevel(c, me)) { say(p, "&cМожно выгонять только тех, кто ниже тебя по роли."); return; }
        String name = c.members.get(target);
        removeMember(c, target);
        save();
        Player online = Bukkit.getPlayer(target);
        if (online != null) say(online, "&cТебя выгнали из клана &e" + c.name);
        tell(c, "&f" + name + " &7исключён из клана.");
    }

    private void doPromote(Player p, Clan c, UUID target, boolean up) {
        if (!c.owner.equals(p.getUniqueId())) { say(p, "&cМенять роли может только владелец."); return; }
        if (target.equals(c.owner)) { say(p, "&cЭто владелец клана."); return; }
        String name = c.members.get(target);
        if (up) {
            if (!c.officers.add(target)) { say(p, "&cОн уже офицер."); return; }
            tell(c, "&f" + name + " &7назначен офицером.");
        } else {
            if (!c.officers.remove(target)) { say(p, "&cОн не офицер."); return; }
            tell(c, "&f" + name + " &7больше не офицер.");
        }
        save();
    }

    private void doTransfer(Player p, Clan c, UUID target) {
        if (!c.owner.equals(p.getUniqueId())) { say(p, "&cПередать клан может только владелец."); return; }
        if (target.equals(p.getUniqueId())) { say(p, "&cТы и так владелец."); return; }
        c.officers.remove(target);
        c.officers.add(c.owner);
        c.owner = target;
        save();
        tell(c, "&f" + c.members.get(target) + " &7теперь владелец клана.");
    }

    private void disband(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (!c.owner.equals(p.getUniqueId())) { say(p, "&cРаспустить клан может только владелец."); return; }
        if (args.length != 2 || !args[1].equalsIgnoreCase("confirm")) {
            say(p, "&cКлан будет удалён навсегда. Подтверди: &e/clan disband confirm");
            return;
        }
        String name = c.name;
        disbandClan(c);
        Bukkit.broadcastMessage(c(PREFIX + "&7Клан &e" + name + " &7распущен."));
    }

    private void cmdColor(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (!can(c, p.getUniqueId(), "color")) { say(p, "&cУ тебя нет права менять цвет клана."); return; }
        if (args.length != 2) {
            say(p, "&cИспользуй: &e/clan color <цвет>&7. Цвета: &f" + colorNames());
            return;
        }
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].name().equalsIgnoreCase(args[1])) {
                c.color = COLORS[i];
                save();
                tell(c, "&7Цвет клана изменён: &" + COLORS[i].getChar() + COLOR_RU[i]);
                return;
            }
        }
        say(p, "&cТакого цвета нет. Цвета: &f" + colorNames());
    }

    private String colorNames() {
        StringBuilder sb = new StringBuilder();
        for (ChatColor col : COLORS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(col.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private void cmdAddPoints(Player p, String[] args) {
        if (!p.hasPermission("griefboard.admin")) { say(p, "&cНет прав."); return; }
        if (args.length != 3) { say(p, "&cИспользуй: &e/clan addpoints <клан> <очки>"); return; }
        Clan c = clans.get(key(args[1]));
        if (c == null) { say(p, "&cКлан не найден."); return; }
        try {
            addPoints(c, Long.parseLong(args[2]));
            say(p, "&aОчки клана &e" + c.name + " &aтеперь: &f" + c.points);
        } catch (NumberFormatException ex) {
            say(p, "&cЧисло неверное.");
        }
    }

    // ---------- дом клана ----------

    private boolean inLobby(Player p) {
        return p.getWorld().getName().equals(plugin.getConfig().getString("rtp.lobby-world", "lobby"));
    }

    private void setHome(Player p) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (!level(c).home) { say(p, "&cДом клана открывается на более высоком уровне клана."); return; }
        if (!can(c, p.getUniqueId(), "sethome")) { say(p, "&cУ тебя нет права ставить дом клана."); return; }
        if (inLobby(p)) { say(p, "&cНа спавне дом ставить нельзя."); return; }
        Location l = p.getLocation();
        c.homeWorld = l.getWorld().getName();
        c.hx = l.getX();
        c.hy = l.getY();
        c.hz = l.getZ();
        c.hyaw = l.getYaw();
        c.hpitch = l.getPitch();
        save();
        tell(c, "&f" + p.getName() + " &7поставил дом клана.");
    }

    private void home(Player p) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        UUID id = p.getUniqueId();
        if (!level(c).home) { say(p, "&cДом клана открывается на более высоком уровне клана."); return; }
        if (!can(c, id, "home")) { say(p, "&cУ тебя нет права телепортироваться в дом клана."); return; }
        if (c.homeWorld == null) { say(p, "&cДом клана ещё не поставлен: &e/clan sethome"); return; }
        if (inLobby(p)) { say(p, "&cСо спавна в дом клана нельзя, выход в мир только через &e/rtp&c."); return; }
        if (warming.contains(id)) { say(p, "&cТелепорт уже идёт."); return; }
        long cd = cfgInt("home-cooldown-seconds", 60) * 1000L;
        Long last = homeCooldown.get(id);
        if (last != null && System.currentTimeMillis() - last < cd) {
            say(p, "&cПодожди ещё " + ((cd - (System.currentTimeMillis() - last)) / 1000 + 1) + " сек.");
            return;
        }
        int warm = cfgInt("home-warmup-seconds", 5);
        final Location start = p.getLocation();
        warming.add(id);
        say(p, "&7Телепорт через &e" + warm + " &7сек. Не двигайся!");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warming.remove(id);
            if (!p.isOnline()) return;
            if (p.getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 1.5) {
                say(p, "&cТелепорт отменён: ты двигался.");
                return;
            }
            Clan cl = clanOf(p);
            if (cl == null || cl.homeWorld == null) return;
            World w = Bukkit.getWorld(cl.homeWorld);
            if (w == null) return;
            p.teleport(new Location(w, cl.hx, cl.hy, cl.hz, cl.hyaw, cl.hpitch));
            homeCooldown.put(id, System.currentTimeMillis());
        }, warm * 20L);
    }

    // ---------- информация ----------

    private void info(Player p, String[] args) {
        Clan c = args.length >= 2 ? clans.get(key(args[1])) : clanOf(p);
        if (c == null) { say(p, "&cКлан не найден."); return; }
        int num = levelNum(c);
        p.sendMessage(c("&8&m          &r &" + c.color.getChar() + "Клан " + c.name + " &8&m          "));
        p.sendMessage(c("&7Владелец: &f" + c.members.get(c.owner)));
        p.sendMessage(c("&7Уровень: &e" + num + " &8(&f" + c.points + " &7очков&8)"));
        p.sendMessage(c("&7Участники (&f" + c.members.size() + "&7/&f" + level(c).maxMembers + "&7):"));
        for (UUID id : sortedMembers(c)) {
            boolean online = Bukkit.getPlayer(id) != null;
            p.sendMessage(c("  " + (online ? "&a●" : "&8●") + " " + roleName(c, id) + " &f" + c.members.get(id)));
        }
    }

    private void list(Player p) {
        List<Clan> sorted = new ArrayList<>(clans.values());
        sorted.sort((a, b) -> Long.compare(b.points, a.points));
        if (sorted.isEmpty()) { say(p, "&7Кланов пока нет. Создай первый: &e/clan create <название>"); return; }
        p.sendMessage(c("&8&m          &r &6Топ кланов &8&m          "));
        int i = 1;
        for (Clan cl : sorted) {
            if (i > 10) break;
            p.sendMessage(c("&e" + i + ". &" + cl.color.getChar() + cl.name + " &8(&7ур. " + levelNum(cl) + ", "
                    + cl.members.size() + " игр., " + cl.points + " очков&8)"));
            i++;
        }
    }

    // ---------- чат клана ----------

    private void clanChat(Player p, String[] args) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        if (args.length == 0) {
            toggleChat(p);
            return;
        }
        sendClanChat(c, p, String.join(" ", args));
    }

    private void toggleChat(Player p) {
        if (chatMode.remove(p.getUniqueId())) {
            say(p, "&7Режим чата клана выключен, пишешь в общий чат.");
        } else {
            chatMode.add(p.getUniqueId());
            say(p, "&aРежим чата клана включён. Выключить: &e/cc");
        }
    }

    private void sendClanChat(Clan c, Player from, String message) {
        String format = plugin.getConfig().getString("clans.chat-format", "&8[&6Клан&8] &f{player}&8: &e{message}");
        String line = c(format.replace("{player}", from.getName()).replace("{clan}", c.name))
                .replace("{message}", message);
        for (UUID id : c.members.keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) online.sendMessage(line);
        }
        plugin.getLogger().info("[Клан " + c.name + "] " + from.getName() + ": " + message);
    }

    // ---------- МЕНЮ ----------

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName(c(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        meta.setLore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack shine(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            it.setItemMeta(meta);
        }
        return it;
    }

    private int colorIndex(Clan c) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i] == c.color) return i;
        }
        return 2;
    }

    private Material pane(Clan c) {
        return Material.valueOf(DYE[colorIndex(c)] + "_STAINED_GLASS_PANE");
    }

    private String bar(long cur, long from, long to) {
        int total = 10;
        int filled = to <= from ? total : (int) Math.max(0, Math.min(total, (cur - from) * total / (to - from)));
        StringBuilder sb = new StringBuilder("&a");
        for (int i = 0; i < filled; i++) sb.append("█");
        sb.append("&8");
        for (int i = filled; i < total; i++) sb.append("█");
        return sb.toString();
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void openMain(Player p) {
        Clan c = clanOf(p);
        if (c == null) { say(p, "&cТы не в клане."); return; }
        UUID me = p.getUniqueId();
        List<Level> all = levels();
        int num = levelNum(c);
        Level lv = all.get(num - 1);
        String col = "&" + c.color.getChar();

        MenuHolder h = new MenuHolder("main");
        Inventory inv = Bukkit.createInventory(h, 54,
                c.color + "" + ChatColor.BOLD + c.name + ChatColor.DARK_GRAY + " | Меню");
        h.inv = inv;
        ItemStack filler = item(pane(c), " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // информация о клане
        long nextPoints = num < all.size() ? all.get(num).points : c.points;
        String next = num < all.size()
                ? "&8(до уровня " + (num + 1) + ": &f" + (nextPoints - c.points) + " &8очков)"
                : "&8(максимальный уровень)";
        inv.setItem(4, item(Material.valueOf(DYE[colorIndex(c)] + "_BANNER"), col + "&l" + c.name,
                "&7Владелец: &f" + c.members.get(c.owner),
                "&7Уровень: &e" + num + "&7/&e" + all.size(),
                "&7Очки: &f" + c.points + " " + next,
                bar(c.points, all.get(num - 1).points, nextPoints),
                "&7Участники: &f" + c.members.size() + "&7/&f" + lv.maxMembers,
                "&7Цвет: " + col + COLOR_RU[colorIndex(c)],
                "&7Твоя роль: " + roleName(c, me)));

        // участники
        inv.setItem(20, item(Material.PLAYER_HEAD, "&e&lУчастники",
                "&7Список игроков клана,",
                "&7роли и управление ими.",
                " ",
                "&eЛКМ &7- открыть"));

        // уровень клана
        List<String> lvLore = new ArrayList<>();
        lvLore.add("&7Очков: &f" + c.points + " " + next);
        lvLore.add(" ");
        for (int i = 0; i < all.size(); i++) {
            Level l = all.get(i);
            StringBuilder perks = new StringBuilder("до " + l.maxMembers + " игр.");
            if (l.home) perks.append(", дом");
            if (l.glow) perks.append(", свечение");
            if (!l.effects.isEmpty()) perks.append(", бонусы");
            lvLore.add((i + 1 == num ? "&a▶ " : "&7  ") + "&eУр. " + (i + 1) + " &8(" + l.points + ") &7" + perks);
        }
        lvLore.add(" ");
        lvLore.add("&7Очки даются за убийство игрока");
        lvLore.add("&7другого клана: &e+" + cfgInt("points-per-kill", 10));
        inv.setItem(22, item(Material.EXPERIENCE_BOTTLE, "&6&lУровень клана: &e" + num,
                lvLore.toArray(new String[0])));

        // цвет клана
        inv.setItem(24, item(Material.valueOf(DYE[colorIndex(c)] + "_DYE"), col + "&lЦвет клана",
                "&7Сейчас: " + col + COLOR_RU[colorIndex(c)],
                "&7Цвет меню, тега и подсветки тимы.",
                " ",
                can(c, me, "color") ? "&eЛКМ &7- следующий цвет" : "&cНет права менять цвет",
                can(c, me, "color") ? "&eПКМ &7- предыдущий цвет" : " "));

        // подсветка тимы
        boolean hl = prefHighlight(p);
        boolean gl = prefGlow(p);
        inv.setItem(29, item(Material.SPECTRAL_ARROW, "&b&lПодсветка тимы",
                "&7Ники тимы в цвете клана,",
                "&7видны сквозь стены (только тебе).",
                " ",
                "&7Ники: " + (hl ? "&aвключены" : "&cвыключены") + " &8(&eЛКМ&8)",
                lv.glow ? "&7Свечение: " + (gl ? "&aвключено" : "&cвыключено") + " &8(&eПКМ&8)"
                        : "&7Свечение: &8откроется на уровне с пометкой «свечение»",
                lv.glow ? "&8Свечение видят все игроки," : " ",
                lv.glow ? "&8тимы видят его в цвете клана." : " "));

        // дом клана
        String homeState = !lv.home ? "&8откроется на более высоком уровне"
                : c.homeWorld == null ? "&cне поставлен"
                : "&aпоставлен &8(" + c.homeWorld + ", " + (int) c.hx + " " + (int) c.hy + " " + (int) c.hz + ")";
        inv.setItem(31, item(Material.RED_BED, "&c&lДом клана",
                "&7Дом: " + homeState,
                "&7Телепорт занимает " + cfgInt("home-warmup-seconds", 5) + " сек. (не двигайся).",
                " ",
                can(c, me, "home") ? "&eЛКМ &7- телепорт в дом" : "&cНет права на телепорт",
                can(c, me, "sethome") ? "&eПКМ &7- поставить дом здесь" : "&cНет права ставить дом"));

        // чат клана
        inv.setItem(33, item(Material.WRITABLE_BOOK, "&a&lЧат клана",
                "&7Сейчас: " + (chatMode.contains(me) ? "&aвесь твой чат идёт клану" : "&7обычный чат"),
                "&7Написать одно сообщение: &e/cc <текст>",
                " ",
                "&eЛКМ &7- включить или выключить режим"));

        // права ролей
        inv.setItem(38, item(Material.COMPARATOR, "&d&lПрава ролей",
                "&7Что разрешено офицерам и участникам.",
                " ",
                c.owner.equals(me) ? "&eЛКМ &7- открыть" : "&cТолько для владельца"));

        // пригласить
        inv.setItem(40, item(Material.NAME_TAG, "&f&lПригласить игрока",
                "&7Команда: &e/clan invite <ник>",
                "&7Максимум игроков на этом уровне: &f" + lv.maxMembers,
                " ",
                can(c, me, "invite") ? "&eЛКМ &7- показать команду" : "&cНет права приглашать"));

        // выйти
        inv.setItem(42, item(Material.OAK_DOOR, "&c&lВыйти из клана",
                c.owner.equals(me) ? "&7Владелец не может выйти:" : "&7Ты покинешь клан.",
                c.owner.equals(me) ? "&7передай клан или распусти его." : " ",
                " ",
                c.owner.equals(me) ? " " : "&eShift + ПКМ &7- выйти"));

        inv.setItem(49, item(Material.BARRIER, "&c&lЗакрыть"));
        p.openInventory(inv);
    }

    private void openMembers(Player p) {
        Clan c = clanOf(p);
        if (c == null) return;
        UUID me = p.getUniqueId();
        MenuHolder h = new MenuHolder("members");
        Inventory inv = Bukkit.createInventory(h, 54, c.color + "" + ChatColor.BOLD + "Участники " + ChatColor.DARK_GRAY + c.name);
        h.inv = inv;
        ItemStack filler = item(pane(c), " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);

        List<UUID> list = sortedMembers(c);
        for (int i = 0; i < list.size() && i < 45; i++) {
            UUID id = list.get(i);
            boolean online = Bukkit.getPlayer(id) != null;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            if (sm != null) {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(id));
                sm.setDisplayName(c("&f&l" + c.members.get(id)));
                List<String> lore = new ArrayList<>();
                lore.add(c("&7Роль: " + roleName(c, id)));
                lore.add(c(online ? "&aВ сети" : "&8Не в сети"));
                lore.add(c(" "));
                boolean owner = c.owner.equals(me);
                if (owner && !id.equals(c.owner)) {
                    lore.add(c("&eЛКМ &7- назначить офицером"));
                    lore.add(c("&eПКМ &7- снять с офицера"));
                    lore.add(c("&eShift + ПКМ &7- передать клан ему"));
                }
                if (can(c, me, "kick") && !id.equals(me) && roleLevel(c, id) < roleLevel(c, me)) {
                    lore.add(c("&eShift + ЛКМ &7- выгнать из клана"));
                }
                sm.setLore(lore);
                sm.addItemFlags(ItemFlag.values());
                head.setItemMeta(sm);
            }
            inv.setItem(i, head);
        }
        inv.setItem(49, item(Material.ARROW, "&e&lНазад"));
        p.openInventory(inv);
    }

    private void openRights(Player p) {
        Clan c = clanOf(p);
        if (c == null) return;
        if (!c.owner.equals(p.getUniqueId())) { say(p, "&cПрава ролей меняет только владелец."); return; }
        MenuHolder h = new MenuHolder("rights");
        Inventory inv = Bukkit.createInventory(h, 36, c.color + "" + ChatColor.BOLD + "Права ролей " + ChatColor.DARK_GRAY + c.name);
        h.inv = inv;
        ItemStack filler = item(pane(c), " ");
        for (int i = 0; i < 36; i++) inv.setItem(i, filler);

        for (int i = 0; i < PERMS.length; i++) {
            boolean off = c.perms.get("officer").contains(PERMS[i]);
            boolean mem = c.perms.get("member").contains(PERMS[i]);
            ItemStack it = item(PERM_ICON[i], "&f&l" + PERM_RU[i],
                    "&7Офицер: " + (off ? "&a✔ можно" : "&c✘ нельзя") + " &8(&eЛКМ&8)",
                    "&7Участник: " + (mem ? "&a✔ можно" : "&c✘ нельзя") + " &8(&eПКМ&8)",
                    " ",
                    "&8Владелец может всё всегда.");
            inv.setItem(11 + i, (off || mem) ? shine(it) : it);
        }
        inv.setItem(31, item(Material.ARROW, "&e&lНазад"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        ClickType click = e.getClick();
        switch (((MenuHolder) holder).type) {
            case "main": clickMain(p, slot, click); break;
            case "members": clickMembers(p, slot, click); break;
            case "rights": clickRights(p, slot, click); break;
            default: break;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    private boolean isLeft(ClickType t) {
        return t == ClickType.LEFT;
    }

    private boolean isRight(ClickType t) {
        return t == ClickType.RIGHT;
    }

    private void clickMain(Player p, int slot, ClickType click) {
        Clan c = clanOf(p);
        if (c == null) { p.closeInventory(); return; }
        UUID me = p.getUniqueId();
        switch (slot) {
            case 20:
                if (isLeft(click)) later(() -> openMembers(p));
                break;
            case 24:
                if (!can(c, me, "color")) { say(p, "&cУ тебя нет права менять цвет клана."); break; }
                if (isLeft(click) || isRight(click)) {
                    int idx = colorIndex(c) + (isLeft(click) ? 1 : -1);
                    idx = (idx + COLORS.length) % COLORS.length;
                    c.color = COLORS[idx];
                    save();
                    later(() -> openMain(p));
                }
                break;
            case 29:
                if (isLeft(click)) {
                    setPref(p, "highlight", !prefHighlight(p));
                    refreshHighlight(p);
                    later(() -> openMain(p));
                } else if (isRight(click)) {
                    if (!level(c).glow) { say(p, "&cСвечение откроется на более высоком уровне клана."); break; }
                    setPref(p, "glow", !prefGlow(p));
                    later(() -> openMain(p));
                }
                break;
            case 31:
                if (isLeft(click)) {
                    p.closeInventory();
                    home(p);
                } else if (isRight(click)) {
                    setHome(p);
                    later(() -> openMain(p));
                }
                break;
            case 33:
                if (isLeft(click)) {
                    toggleChat(p);
                    later(() -> openMain(p));
                }
                break;
            case 38:
                if (isLeft(click)) {
                    if (c.owner.equals(me)) later(() -> openRights(p));
                    else say(p, "&cПрава ролей меняет только владелец.");
                }
                break;
            case 40:
                if (isLeft(click)) {
                    p.closeInventory();
                    if (can(c, me, "invite")) say(p, "&7Пригласить игрока: &e/clan invite <ник>");
                    else say(p, "&cУ тебя нет права приглашать игроков.");
                }
                break;
            case 42:
                if (click == ClickType.SHIFT_RIGHT) {
                    p.closeInventory();
                    leave(p);
                } else {
                    say(p, "&7Чтобы выйти, нажми &eShift + ПКМ&7.");
                }
                break;
            case 49:
                p.closeInventory();
                break;
            default:
                break;
        }
    }

    private void clickMembers(Player p, int slot, ClickType click) {
        Clan c = clanOf(p);
        if (c == null) { p.closeInventory(); return; }
        if (slot == 49) { later(() -> openMain(p)); return; }
        List<UUID> list = sortedMembers(c);
        if (slot < 0 || slot >= list.size() || slot >= 45) return;
        UUID target = list.get(slot);
        switch (click) {
            case LEFT: doPromote(p, c, target, true); break;
            case RIGHT: doPromote(p, c, target, false); break;
            case SHIFT_LEFT: doKick(p, c, target); break;
            case SHIFT_RIGHT: doTransfer(p, c, target); break;
            default: return;
        }
        later(() -> {
            if (clanOf(p) != null) openMembers(p);
        });
    }

    private void clickRights(Player p, int slot, ClickType click) {
        Clan c = clanOf(p);
        if (c == null) { p.closeInventory(); return; }
        if (slot == 31) { later(() -> openMain(p)); return; }
        if (!c.owner.equals(p.getUniqueId())) { p.closeInventory(); return; }
        int idx = slot - 11;
        if (idx < 0 || idx >= PERMS.length) return;
        String role = isLeft(click) ? "officer" : isRight(click) ? "member" : null;
        if (role == null) return;
        Set<String> set = c.perms.get(role);
        if (!set.remove(PERMS[idx])) set.add(PERMS[idx]);
        save();
        later(() -> openRights(p));
    }

    // ---------- вспомогательное ----------

    private Clan clanOf(Player p) {
        return clanOfId(p.getUniqueId());
    }

    private Clan clanOfId(UUID id) {
        String key = memberClan.get(id);
        return key == null ? null : clans.get(key);
    }

    /** 3 - владелец, 2 - офицер, 1 - участник. */
    private int roleLevel(Clan c, UUID id) {
        if (c.owner.equals(id)) return 3;
        return c.officers.contains(id) ? 2 : 1;
    }

    private String roleName(Clan c, UUID id) {
        int r = roleLevel(c, id);
        return r == 3 ? "&6Владелец" : r == 2 ? "&eОфицер" : "&7Участник";
    }

    private boolean can(Clan c, UUID id, String perm) {
        if (c.owner.equals(id)) return true;
        String role = c.officers.contains(id) ? "officer" : "member";
        return c.perms.get(role).contains(perm);
    }

    private List<UUID> sortedMembers(Clan c) {
        List<UUID> list = new ArrayList<>(c.members.keySet());
        list.sort((a, b) -> Integer.compare(roleLevel(c, b), roleLevel(c, a)));
        return list;
    }

    private UUID findMember(Clan c, String name) {
        for (Map.Entry<UUID, String> m : c.members.entrySet()) {
            if (m.getValue().equalsIgnoreCase(name)) return m.getKey();
        }
        return null;
    }

    private void removeMember(Clan c, UUID id) {
        c.members.remove(id);
        c.officers.remove(id);
        memberClan.remove(id);
        chatMode.remove(id);
    }

    private void disbandClan(Clan c) {
        for (UUID id : new ArrayList<>(c.members.keySet())) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) say(online, "&cКлан &e" + c.name + " &cраспущен.");
            memberClan.remove(id);
            chatMode.remove(id);
        }
        clans.remove(key(c.name));
        save();
    }

    private void tell(Clan c, String text) {
        for (UUID id : c.members.keySet()) {
            Player online = Bukkit.getPlayer(id);
            if (online != null) say(online, text);
        }
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("clans." + key, def);
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // ---------- подсказки по Tab ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("cc")) return out;
        if (args.length == 1) {
            out.addAll(Arrays.asList("menu", "create", "invite", "accept", "deny", "leave", "kick",
                    "promote", "demote", "transfer", "disband", "color", "sethome", "home", "info", "list", "chat"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (Arrays.asList("invite", "kick", "promote", "demote", "transfer").contains(sub)) {
                for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
            } else if (sub.equals("info") || sub.equals("addpoints")) {
                for (Clan cl : clans.values()) out.add(cl.name);
            } else if (sub.equals("color")) {
                for (ChatColor col : COLORS) out.add(col.name().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
