package ru.griefboard;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Приваты: особые блоки (золотая руда ... изумрудный блок) создают защищённую зону вокруг себя.
 * В зоне ломать, строить, открывать сундуки и двери могут только владелец и добавленные игроки.
 * Данные хранятся в plugins/GriefBoard/privates.yml.
 */
public class PrivateManager implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "&8[&6Приват&8] ";

    private static final String[][] DEF_TYPES = {
            {"gold_ore", "GOLD_ORE", "&6Золотая руда", "9", "16", "9"},
            {"gold_block", "GOLD_BLOCK", "&6Золотой блок", "13", "16", "13"},
            {"diamond_ore", "DIAMOND_ORE", "&bАлмазная руда", "17", "20", "17"},
            {"diamond_block", "DIAMOND_BLOCK", "&bАлмазный блок", "21", "24", "21"},
            {"emerald_ore", "EMERALD_ORE", "&aИзумрудная руда", "25", "24", "25"},
            {"emerald_block", "EMERALD_BLOCK", "&aИзумрудный блок", "29", "32", "29"}};

    private static class PType {
        final String key;
        final Material material;
        final String name;
        final int x, y, z;
        final Color color;

        PType(String key, Material material, String name, int x, int y, int z) {
            this.key = key;
            this.material = material;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            String code = name.length() > 1 && name.charAt(0) == '&' ? name.substring(1, 2) : "f";
            switch (code) {
                case "6": this.color = Color.fromRGB(255, 190, 0); break;
                case "b": this.color = Color.fromRGB(70, 230, 255); break;
                case "a": this.color = Color.fromRGB(60, 230, 110); break;
                default: this.color = Color.WHITE; break;
            }
        }

        String size() {
            return x + "×" + y + "×" + z;
        }
    }

    private static class Claim {
        int id;
        UUID owner;
        String ownerName;
        String type;
        String world;
        int x, y, z;
        int minX, minY, minZ, maxX, maxY, maxZ;
        final LinkedHashMap<UUID, String> members = new LinkedHashMap<>();

        boolean contains(String w, int px, int py, int pz) {
            return world.equals(w) && px >= minX && px <= maxX && py >= minY && py <= maxY && pz >= minZ && pz <= maxZ;
        }

        boolean overlaps(String w, int ax, int ay, int az, int bx, int by, int bz) {
            return world.equals(w) && ax <= maxX && bx >= minX && ay <= maxY && by >= minY && az <= maxZ && bz >= minZ;
        }
    }

    private final GriefBoard plugin;
    private File file;
    private YamlConfiguration yml;
    private NamespacedKey keyType;
    private final LinkedHashMap<String, PType> types = new LinkedHashMap<>();
    private final List<Claim> claims = new ArrayList<>();
    private final Map<UUID, Integer> inside = new HashMap<>();
    private int nextId = 1;

    PrivateManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    // ---------- запуск ----------

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "privates.yml");
        yml = YamlConfiguration.loadConfiguration(file);
        keyType = new NamespacedKey(plugin, "privat_type");
        loadTypes();
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("privat") != null) {
            plugin.getCommand("privat").setExecutor(this);
            plugin.getCommand("privat").setTabCompleter(this);
        }
    }

    void reload() {
        loadTypes();
    }

    void disable() {
        save();
    }

    private void loadTypes() {
        types.clear();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("privates.blocks");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection s = cs.getConfigurationSection(key);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("material", ""));
                if (m == null || !m.isBlock()) continue;
                types.put(key, new PType(key, m, s.getString("name", key),
                        Math.max(1, s.getInt("x", 9)), Math.max(1, s.getInt("y", 16)), Math.max(1, s.getInt("z", 9))));
            }
        }
        if (types.isEmpty()) {
            for (String[] d : DEF_TYPES) {
                types.put(d[0], new PType(d[0], Material.valueOf(d[1]), d[2],
                        Integer.parseInt(d[3]), Integer.parseInt(d[4]), Integer.parseInt(d[5])));
            }
        }
    }

    // ---------- хранение ----------

    private void load() {
        claims.clear();
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection root = yml.getConfigurationSection("claims");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Claim c = new Claim();
            try {
                c.id = Integer.parseInt(key);
                c.owner = UUID.fromString(s.getString("owner", ""));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            c.ownerName = s.getString("owner-name", "?");
            c.type = s.getString("type", "");
            c.world = s.getString("world", "world");
            c.x = s.getInt("x");
            c.y = s.getInt("y");
            c.z = s.getInt("z");
            List<Integer> b = s.getIntegerList("bounds");
            if (b.size() != 6) continue;
            c.minX = b.get(0);
            c.minY = b.get(1);
            c.minZ = b.get(2);
            c.maxX = b.get(3);
            c.maxY = b.get(4);
            c.maxZ = b.get(5);
            ConfigurationSection ms = s.getConfigurationSection("members");
            if (ms != null) {
                for (String u : ms.getKeys(false)) {
                    try { c.members.put(UUID.fromString(u), ms.getString(u, "?")); } catch (IllegalArgumentException ignored) { }
                }
            }
            claims.add(c);
            nextId = Math.max(nextId, c.id + 1);
        }
    }

    private void save() {
        yml.set("next-id", nextId);
        yml.set("claims", null);
        for (Claim c : claims) {
            String b = "claims." + c.id + ".";
            yml.set(b + "owner", c.owner.toString());
            yml.set(b + "owner-name", c.ownerName);
            yml.set(b + "type", c.type);
            yml.set(b + "world", c.world);
            yml.set(b + "x", c.x);
            yml.set(b + "y", c.y);
            yml.set(b + "z", c.z);
            yml.set(b + "bounds", Arrays.asList(c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ));
            for (Map.Entry<UUID, String> m : c.members.entrySet()) {
                yml.set(b + "members." + m.getKey(), m.getValue());
            }
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить privates.yml: " + e.getMessage());
        }
    }

    // ---------- поиск ----------

    private Claim claimAt(Location l) {
        if (l == null || l.getWorld() == null) return null;
        String w = l.getWorld().getName();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        for (Claim c : claims) {
            if (c.contains(w, x, y, z)) return c;
        }
        return null;
    }

    /** Приват, чей блок стоит ровно в этой точке. */
    private Claim claimByBlock(Block b) {
        String w = b.getWorld().getName();
        for (Claim c : claims) {
            if (c.world.equals(w) && c.x == b.getX() && c.y == b.getY() && c.z == b.getZ()) return c;
        }
        return null;
    }

    private boolean isMember(Player p, Claim c) {
        return c.owner.equals(p.getUniqueId()) || c.members.containsKey(p.getUniqueId());
    }

    private boolean bypass(Player p) {
        return p.hasPermission("griefboard.privat.bypass");
    }

    private boolean canBuild(Player p, Claim c) {
        return isMember(p, c) || bypass(p);
    }

    private void deny(Player p, Claim c) {
        bar(p, "&cЭто приват игрока &f" + c.ownerName);
    }

    // ---------- предметы ----------

    private ItemStack makeItem(PType t, int amount) {
        ItemStack it = new ItemStack(t.material, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.setDisplayName(c("&6&lБлок привата &8| " + t.name));
        m.setLore(Arrays.asList(
                c("&7Размер зоны: &f" + t.size()),
                c(" "),
                c("&7Поставь блок, и область вокруг"),
                c("&7него станет твоим приватом."),
                c("&7Сломать блок может только владелец.")));
        m.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, t.key);
        m.addEnchant(Enchantment.DURABILITY, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(m);
        return it;
    }

    private String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        ItemMeta m = it.getItemMeta();
        if (m == null) return null;
        return m.getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
    }

    // ---------- создание и снятие привата ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        String typeKey = typeOf(e.getItemInHand());
        if (typeKey != null) {
            createClaim(e, p, b, typeKey);
            return;
        }
        Claim c = claimAt(b.getLocation());
        if (c != null && !canBuild(p, c)) {
            e.setCancelled(true);
            deny(p, c);
        }
    }

    private void createClaim(BlockPlaceEvent e, Player p, Block b, String typeKey) {
        PType t = types.get(typeKey);
        if (t == null) {
            e.setCancelled(true);
            say(p, "&cЭтот тип привата больше не существует.");
            return;
        }
        if (plugin.getConfig().getStringList("privates.disabled-worlds").contains(b.getWorld().getName())) {
            e.setCancelled(true);
            say(p, "&cВ этом мире приваты ставить нельзя.");
            return;
        }
        int max = plugin.getConfig().getInt("privates.max-per-player", 5);
        if (!bypass(p) && countOwned(p.getUniqueId()) >= max) {
            e.setCancelled(true);
            say(p, "&cТы уже поставил максимум приватов (&f" + max + "&c). Сними один, сломав его блок.");
            return;
        }
        int hx = (t.x - 1) / 2, hz = (t.z - 1) / 2, hy = t.y / 2;
        int minX = b.getX() - hx, maxX = b.getX() + (t.x - 1 - hx);
        int minZ = b.getZ() - hz, maxZ = b.getZ() + (t.z - 1 - hz);
        int minY = Math.max(0, b.getY() - hy), maxY = Math.min(255, b.getY() + (t.y - hy) - 1);
        String w = b.getWorld().getName();
        for (Claim o : claims) {
            if (o.overlaps(w, minX, minY, minZ, maxX, maxY, maxZ)) {
                e.setCancelled(true);
                say(p, "&cЗдесь уже есть приват игрока &f" + o.ownerName + "&c. Отойди подальше.");
                return;
            }
        }
        Claim c = new Claim();
        c.id = nextId++;
        c.owner = p.getUniqueId();
        c.ownerName = p.getName();
        c.type = typeKey;
        c.world = w;
        c.x = b.getX();
        c.y = b.getY();
        c.z = b.getZ();
        c.minX = minX;
        c.minY = minY;
        c.minZ = minZ;
        c.maxX = maxX;
        c.maxY = maxY;
        c.maxZ = maxZ;
        claims.add(c);
        save();
        say(p, "&aПриват создан! &7Тип: " + t.name + " &8| &7Зона: &f" + t.size());
        say(p, "&7Добавить игрока: &e/privat add <ник>&7, границы: &e/privat show");
        showBorders(p, c, 8);
    }

    private int countOwned(UUID id) {
        int n = 0;
        for (Claim c : claims) {
            if (c.owner.equals(id)) n++;
        }
        return n;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        Claim own = claimByBlock(b);
        if (own != null) {
            if (own.owner.equals(p.getUniqueId()) || bypass(p)) {
                e.setDropItems(false);
                claims.remove(own);
                save();
                PType t = types.get(own.type);
                if (t != null) {
                    Map<Integer, ItemStack> left = p.getInventory().addItem(makeItem(t, 1));
                    for (ItemStack it : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), it);
                }
                say(p, "&7Приват снят, блок привата возвращён тебе.");
            } else {
                e.setCancelled(true);
                deny(p, own);
            }
            return;
        }
        Claim c = claimAt(b.getLocation());
        if (c != null && !canBuild(p, c)) {
            e.setCancelled(true);
            deny(p, c);
        }
    }

    // ---------- защита территории ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        Claim c = claimAt(b.getLocation());
        if (c == null || canBuild(p, c)) return;
        Material m = b.getType();
        if (e.getAction() == Action.PHYSICAL) {
            String n = m.name();
            if (n.equals("FARMLAND") || n.endsWith("PRESSURE_PLATE") || n.equals("TRIPWIRE") || n.equals("TURTLE_EGG")) {
                e.setCancelled(true);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (isProtectedBlock(m)) {
            e.setCancelled(true);
            deny(p, c);
            return;
        }
        ItemStack hand = e.getItem();
        if (hand != null && isPlaceItem(hand.getType())) {
            e.setCancelled(true);
            deny(p, c);
        }
    }

    private boolean isProtectedBlock(Material m) {
        String n = m.name();
        if (n.equals("ENDER_CHEST") || n.equals("CRAFTING_TABLE")) return false;
        return n.contains("CHEST") || n.equals("BARREL") || n.contains("FURNACE") || n.equals("SMOKER")
                || n.equals("HOPPER") || n.equals("DISPENSER") || n.equals("DROPPER") || n.contains("SHULKER_BOX")
                || n.equals("BREWING_STAND") || n.contains("ANVIL") || n.equals("BEACON") || n.equals("LECTERN")
                || n.equals("JUKEBOX") || n.equals("NOTE_BLOCK") || n.contains("DOOR") || n.contains("TRAPDOOR")
                || n.contains("FENCE_GATE") || n.contains("BUTTON") || n.equals("LEVER") || n.equals("COMPARATOR")
                || n.equals("REPEATER") || n.contains("DAYLIGHT_DETECTOR") || n.contains("CAKE") || n.contains("_BED")
                || n.equals("LOOM") || n.equals("GRINDSTONE") || n.equals("STONECUTTER")
                || n.equals("CARTOGRAPHY_TABLE") || n.equals("SMITHING_TABLE") || n.equals("ENCHANTING_TABLE")
                || n.equals("BELL") || n.equals("COMPOSTER") || n.contains("CAULDRON") || n.contains("FLOWER_POT");
    }

    private boolean isPlaceItem(Material m) {
        String n = m.name();
        return n.equals("ARMOR_STAND") || n.equals("ITEM_FRAME") || n.equals("PAINTING") || n.equals("END_CRYSTAL")
                || n.equals("FLINT_AND_STEEL") || n.equals("FIRE_CHARGE") || n.endsWith("_BOAT")
                || n.contains("MINECART") || n.endsWith("_SPAWN_EGG") || n.endsWith("BUCKET");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (!(en instanceof Hanging || en instanceof ArmorStand || en instanceof StorageMinecart
                || en instanceof HopperMinecart)) return;
        Claim c = claimAt(en.getLocation());
        if (c != null && !canBuild(e.getPlayer(), c)) {
            e.setCancelled(true);
            deny(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        Claim c = claimAt(e.getRightClicked().getLocation());
        if (c != null && !canBuild(e.getPlayer(), c)) {
            e.setCancelled(true);
            deny(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        Claim c = claimAt(e.getEntity().getLocation());
        if (c != null && (p == null || !canBuild(p, c))) {
            e.setCancelled(true);
            if (p != null) deny(p, c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        Claim c = claimAt(e.getEntity().getLocation());
        if (c == null) return;
        if (e instanceof HangingBreakByEntityEvent) {
            Player p = playerOf(((HangingBreakByEntityEvent) e).getRemover());
            if (p != null) {
                if (!canBuild(p, c)) {
                    e.setCancelled(true);
                    deny(p, c);
                }
                return;
            }
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION || e.getCause() == HangingBreakEvent.RemoveCause.ENTITY) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (!(v instanceof ArmorStand || v instanceof Hanging)) return;
        Claim c = claimAt(v.getLocation());
        if (c == null) return;
        Player p = playerOf(e.getDamager());
        if (p == null || !canBuild(p, c)) {
            e.setCancelled(true);
            if (p != null) deny(p, c);
        }
    }

    private Player playerOf(Entity d) {
        if (d instanceof Player) return (Player) d;
        if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Player) {
            return (Player) ((Projectile) d).getShooter();
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Claim c = claimAt(e.getBlock().getLocation());
        if (c != null && !canBuild(e.getPlayer(), c)) {
            e.setCancelled(true);
            deny(e.getPlayer(), c);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Claim c = claimAt(e.getBlockClicked().getLocation());
        if (c != null && !canBuild(e.getPlayer(), c)) {
            e.setCancelled(true);
            deny(e.getPlayer(), c);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!plugin.getConfig().getBoolean("privates.explosion-protect", true)) return;
        e.blockList().removeIf(b -> claimAt(b.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!plugin.getConfig().getBoolean("privates.explosion-protect", true)) return;
        e.blockList().removeIf(b -> claimAt(b.getLocation()) != null);
    }

    /** Мобы (эндермены, иссушители и т.п.) не ломают блоки в приватах. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof FallingBlock) return;
        if (claimAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (claimAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Claim c = claimAt(e.getBlock().getLocation());
        if (c == null) return;
        Player p = e.getPlayer();
        if (p == null || !canBuild(p, c)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Claim to = claimAt(e.getToBlock().getLocation());
        if (to == null) return;
        if (claimAt(e.getBlock().getLocation()) != to) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!pistonAllowed(e.getBlock(), e.getBlocks(), e.getDirection(), true)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!pistonAllowed(e.getBlock(), e.getBlocks(), e.getDirection().getOppositeFace(), false)) e.setCancelled(true);
    }

    /** Поршень снаружи не двигает блоки в чужой приват и из него. */
    private boolean pistonAllowed(Block piston, List<Block> moved, BlockFace dir, boolean extend) {
        Claim pc = claimAt(piston.getLocation());
        for (Block b : moved) {
            if (claimByBlock(b) != null) return false;
            Claim from = claimAt(b.getLocation());
            Claim to = claimAt(b.getRelative(dir).getLocation());
            if ((from != null && from != pc) || (to != null && to != pc)) return false;
        }
        if (extend) {
            Claim head = claimAt(piston.getRelative(dir).getLocation());
            if (head != null && head != pc) return false;
        }
        return true;
    }

    // ---------- вход в приват и выход ----------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom();
        Location t = e.getTo();
        if (t == null) return;
        if (f.getBlockX() == t.getBlockX() && f.getBlockY() == t.getBlockY() && f.getBlockZ() == t.getBlockZ()) return;
        Player p = e.getPlayer();
        Claim c = claimAt(t);
        int id = c == null ? 0 : c.id;
        Integer prev = inside.get(p.getUniqueId());
        if (prev != null && prev == id) return;
        inside.put(p.getUniqueId(), id);
        if (prev == null && id == 0) return;
        if (c != null) {
            bar(p, "&6Приват игрока &f" + c.ownerName + " &8| "
                    + (canBuild(p, c) ? "&aты можешь здесь строить" : "&cчужая территория"));
        } else {
            bar(p, "&7Ты вышел из привата");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inside.remove(e.getPlayer().getUniqueId());
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player) info((Player) sender);
            else help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("give")) {
            give(sender, args);
            return true;
        }
        if (sub.equals("types")) {
            types(sender);
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        switch (sub) {
            case "info": info(p); break;
            case "add": add(p, args); break;
            case "remove": remove(p, args); break;
            case "list": list(p); break;
            case "show": show(p); break;
            case "delete": delete(p, args); break;
            default: help(p); break;
        }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "&6Команды привата:");
        s.sendMessage(c("&e/privat info &7- информация о привате, в котором ты стоишь"));
        s.sendMessage(c("&e/privat add <ник> &7- добавить игрока в свой приват"));
        s.sendMessage(c("&e/privat remove <ник> &7- убрать игрока из привата"));
        s.sendMessage(c("&e/privat list &7- твои приваты, &e/privat show &7- показать границы"));
        s.sendMessage(c("&e/privat types &7- все блоки привата и их размеры"));
        s.sendMessage(c("&7Снять приват: сломай блок привата."));
    }

    private void types(CommandSender s) {
        say(s, "&6Блоки привата:");
        for (PType t : types.values()) {
            s.sendMessage(c("  " + t.name + " &8- &f" + t.size() + " &8(" + t.key + ")"));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("griefboard.admin")) { say(sender, "&cНет прав."); return; }
        if (args.length < 3) { say(sender, "&cИспользуй: &e/privat give <ник> <тип> [количество]"); return; }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) { say(sender, "&cИгрок не в сети."); return; }
        PType t = types.get(args[2].toLowerCase(Locale.ROOT));
        if (t == null) { say(sender, "&cТакого типа нет. Список: &e/privat types"); return; }
        int amount = 1;
        if (args.length >= 4) {
            try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[3]))); } catch (NumberFormatException ex) {
                say(sender, "&cЧисло неверное.");
                return;
            }
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(makeItem(t, amount));
        for (ItemStack it : left.values()) target.getWorld().dropItemNaturally(target.getLocation(), it);
        say(sender, "&aВыдано &f" + amount + "&a: " + t.name + " &aигроку &f" + target.getName());
        if (sender != target) say(target, "&aТебе выдали блок привата: " + t.name);
    }

    private Claim here(Player p) {
        Claim c = claimAt(p.getLocation());
        if (c == null) say(p, "&cВстань внутрь привата.");
        return c;
    }

    private void info(Player p) {
        Claim c = claimAt(p.getLocation());
        if (c == null) {
            say(p, "&7Ты не в привате. Список команд: &e/privat help");
            help(p);
            return;
        }
        PType t = types.get(c.type);
        p.sendMessage(c("&8&m          &r &6Приват #" + c.id + " &8&m          "));
        p.sendMessage(c("&7Владелец: &f" + c.ownerName));
        p.sendMessage(c("&7Тип: " + (t == null ? c.type : t.name + " &8(" + t.size() + ")")));
        p.sendMessage(c("&7Границы: &f" + c.minX + " " + c.minY + " " + c.minZ + " &7- &f" + c.maxX + " " + c.maxY + " " + c.maxZ));
        StringBuilder sb = new StringBuilder();
        for (String n : c.members.values()) {
            if (sb.length() > 0) sb.append("&7, ");
            sb.append("&f").append(n);
        }
        p.sendMessage(c("&7Участники: " + (sb.length() == 0 ? "&8нет" : sb.toString())));
    }

    private void add(Player p, String[] args) {
        Claim c = here(p);
        if (c == null) return;
        if (!c.owner.equals(p.getUniqueId()) && !bypass(p)) { say(p, "&cЭто может только владелец."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/privat add <ник>"); return; }
        int max = plugin.getConfig().getInt("privates.max-members", 10);
        if (c.members.size() >= max) { say(p, "&cМаксимум участников: &f" + max); return; }
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID id;
        String name;
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
            if (!op.hasPlayedBefore()) { say(p, "&cТакой игрок не заходил на сервер."); return; }
            id = op.getUniqueId();
            name = op.getName() != null ? op.getName() : args[1];
        }
        if (id.equals(c.owner)) { say(p, "&cТы и так владелец."); return; }
        if (c.members.containsKey(id)) { say(p, "&cОн уже в привате."); return; }
        c.members.put(id, name);
        save();
        say(p, "&aИгрок &f" + name + " &aдобавлен в приват.");
        if (online != null) say(online, "&aТебя добавили в приват игрока &f" + c.ownerName);
    }

    private void remove(Player p, String[] args) {
        Claim c = here(p);
        if (c == null) return;
        if (!c.owner.equals(p.getUniqueId()) && !bypass(p)) { say(p, "&cЭто может только владелец."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/privat remove <ник>"); return; }
        UUID found = null;
        for (Map.Entry<UUID, String> m : c.members.entrySet()) {
            if (m.getValue().equalsIgnoreCase(args[1])) found = m.getKey();
        }
        if (found == null) { say(p, "&cТакого игрока нет в привате."); return; }
        String name = c.members.remove(found);
        save();
        say(p, "&7Игрок &f" + name + " &7убран из привата.");
    }

    private void list(Player p) {
        int n = 0;
        say(p, "&6Твои приваты:");
        for (Claim c : claims) {
            if (!c.owner.equals(p.getUniqueId())) continue;
            n++;
            PType t = types.get(c.type);
            p.sendMessage(c("  &e#" + c.id + " " + (t == null ? c.type : t.name) + " &8| &f" + c.world + " "
                    + c.x + " " + c.y + " " + c.z + " &8| &7участников: &f" + c.members.size()));
        }
        if (n == 0) p.sendMessage(c("  &8У тебя пока нет приватов."));
    }

    private void show(Player p) {
        Claim c = here(p);
        if (c == null) return;
        say(p, "&7Границы привата показаны на 10 секунд.");
        showBorders(p, c, 10);
    }

    private void delete(Player p, String[] args) {
        if (!p.hasPermission("griefboard.admin")) { say(p, "&cНет прав."); return; }
        Claim c = null;
        if (args.length >= 2) {
            try {
                int id = Integer.parseInt(args[1]);
                for (Claim o : claims) {
                    if (o.id == id) c = o;
                }
            } catch (NumberFormatException ignored) { }
        } else {
            c = claimAt(p.getLocation());
        }
        if (c == null) { say(p, "&cПриват не найден. Используй: &e/privat delete [номер]"); return; }
        claims.remove(c);
        save();
        say(p, "&7Приват &e#" + c.id + " &7игрока &f" + c.ownerName + " &7удалён (сам блок остался на месте).");
    }

    // ---------- границы (частицы) ----------

    private void showBorders(Player p, Claim c, int seconds) {
        World w = Bukkit.getWorld(c.world);
        if (w == null || p.getWorld() != w) return;
        PType t = types.get(c.type);
        final Particle.DustOptions dust = new Particle.DustOptions(t == null ? Color.WHITE : t.color, 1.3f);
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline() || p.getWorld() != w || ticks >= seconds * 20) {
                    cancel();
                    return;
                }
                drawBox(p, w, c, dust);
                ticks += 10;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void drawBox(Player p, World w, Claim c, Particle.DustOptions dust) {
        double x0 = c.minX, x1 = c.maxX + 1, y0 = c.minY, y1 = c.maxY + 1, z0 = c.minZ, z1 = c.maxZ + 1;
        for (double x = x0; x <= x1; x += 1) {
            dot(p, w, x, y0, z0, dust);
            dot(p, w, x, y0, z1, dust);
            dot(p, w, x, y1, z0, dust);
            dot(p, w, x, y1, z1, dust);
        }
        for (double z = z0; z <= z1; z += 1) {
            dot(p, w, x0, y0, z, dust);
            dot(p, w, x1, y0, z, dust);
            dot(p, w, x0, y1, z, dust);
            dot(p, w, x1, y1, z, dust);
        }
        for (double y = y0; y <= y1; y += 1) {
            dot(p, w, x0, y, z0, dust);
            dot(p, w, x1, y, z0, dust);
            dot(p, w, x0, y, z1, dust);
            dot(p, w, x1, y, z1, dust);
        }
    }

    private void dot(Player p, World w, double x, double y, double z, Particle.DustOptions dust) {
        Location l = new Location(w, x, y, z);
        if (l.distanceSquared(p.getLocation()) > 64 * 64) return;
        p.spawnParticle(Particle.REDSTONE, l, 1, 0, 0, 0, 0, dust);
    }

    // ---------- вспомогательное ----------

    private void bar(Player p, String text) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(c(text)));
    }

    private void say(CommandSender s, String text) {
        s.sendMessage(c(PREFIX + text));
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("info", "add", "remove", "list", "show", "types", "give", "delete"));
        } else if (args.length == 2 && Arrays.asList("add", "remove", "give").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player pl : Bukkit.getOnlinePlayers()) out.add(pl.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            out.addAll(types.keySet());
        }
        return out;
    }
}
