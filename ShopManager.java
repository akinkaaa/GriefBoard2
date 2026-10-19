package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.StringReader;
import java.util.*;

/**
 * NPC-магазин брони: продавец-житель на спавне. ПКМ по нему открывает меню с наборами брони
 * с чарами. Каталог задаётся в config.yml (shop.items), цены в монетах или Cytrix.
 */
public class ShopManager implements Listener {

    private static final String TAG_NPC = "gb_shop_npc";
    private static final String TAG_HOLO = "gb_shop_holo";
    private static final String PREFIX = "&8[&6Магазин&8] ";

    private static final String DEFAULT_YAML =
            "shop:\n" +
            "  enabled: true\n" +
            "  # Где стоит NPC-продавец (мир берётся из rtp.lobby-world). yaw - куда он смотрит.\n" +
            "  npc:\n" +
            "    x: 39.557\n" +
            "    y: 64.0\n" +
            "    z: 66.7\n" +
            "    yaw: 149\n" +
            "  # Каталог (до 28 товаров, порядок как в файле).\n" +
            "  #   price + currency: coins (монеты) или cytrix (донат-валюта)\n" +
            "  #   rarity: common, rare, epic, legendary, mythic (показывается в описании)\n" +
            "  #   pieces - предметы набора (все выдаются за одну цену)\n" +
            "  #   enchants - чары на все предметы, piece-enchants - чары только на helmet, chestplate, leggings, boots\n" +
            "  #   color: [R, G, B] - цвет кожаной брони\n" +
            "  # Названия чар как в игре на английском в нижнем регистре (protection, unbreaking, mending...).\n" +
            "  # Уровни можно ставить выше обычных (защита 5, прочность 5 и т.д.).\n" +
            "  items:\n" +
            "    wanderer:\n" +
            "      name: \"&6&l❖ Плащ скитальца\"\n" +
            "      description: [\"&7Лёгкий кожаный сет цвета дорожной пыли.\", \"&7Для первых шагов по миру.\"]\n" +
            "      rarity: common\n" +
            "      price: 350\n" +
            "      currency: coins\n" +
            "      color: [110, 70, 40]\n" +
            "      pieces: [LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS]\n" +
            "      enchants: {protection: 2, unbreaking: 2}\n" +
            "    hunter:\n" +
            "      name: \"&2&l❖ Лесной охотник\"\n" +
            "      description: [\"&7Зелёный кожаный сет следопытов.\", \"&7Хорошо держит стрелы.\"]\n" +
            "      rarity: common\n" +
            "      price: 550\n" +
            "      currency: coins\n" +
            "      color: [40, 110, 50]\n" +
            "      pieces: [LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS]\n" +
            "      enchants: {projectile_protection: 4, protection: 2, unbreaking: 3}\n" +
            "      piece-enchants:\n" +
            "        boots: {feather_falling: 3}\n" +
            "    recruit:\n" +
            "      name: \"&f&l⚔ Новобранец\"\n" +
            "      description: [\"&7Железный сет для начала пути.\", \"&7Недорого и надёжно.\"]\n" +
            "      rarity: common\n" +
            "      price: 900\n" +
            "      currency: coins\n" +
            "      pieces: [IRON_HELMET, IRON_CHESTPLATE, IRON_LEGGINGS, IRON_BOOTS]\n" +
            "      enchants: {protection: 3, unbreaking: 2}\n" +
            "    chain:\n" +
            "      name: \"&7&l⚔ Кольчуга странника\"\n" +
            "      description: [\"&7Лёгкая кольчуга дальних походов.\", \"&7Прочность III на каждом предмете.\"]\n" +
            "      rarity: common\n" +
            "      price: 1400\n" +
            "      currency: coins\n" +
            "      pieces: [CHAINMAIL_HELMET, CHAINMAIL_CHESTPLATE, CHAINMAIL_LEGGINGS, CHAINMAIL_BOOTS]\n" +
            "      enchants: {protection: 3, unbreaking: 3}\n" +
            "    bastion:\n" +
            "      name: \"&e&l♛ Бастион\"\n" +
            "      description: [\"&7Щит, который трудно разбить.\"]\n" +
            "      rarity: common\n" +
            "      price: 600\n" +
            "      currency: coins\n" +
            "      pieces: [SHIELD]\n" +
            "      enchants: {unbreaking: 3, mending: 1}\n" +
            "    nightwatch:\n" +
            "      name: \"&8&l☠ Ночной дозор\"\n" +
            "      description: [\"&7Чёрный кожаный сет теней.\", \"&7Защита IV и починка в придачу.\"]\n" +
            "      rarity: rare\n" +
            "      price: 1300\n" +
            "      currency: coins\n" +
            "      color: [25, 25, 30]\n" +
            "      pieces: [LEATHER_HELMET, LEATHER_CHESTPLATE, LEATHER_LEGGINGS, LEATHER_BOOTS]\n" +
            "      enchants: {protection: 4, unbreaking: 3, mending: 1}\n" +
            "    gold:\n" +
            "      name: \"&6&l♛ Золото богача\"\n" +
            "      description: [\"&7Блестящий золотой сет.\", \"&7Для тех, кто любит выделяться.\"]\n" +
            "      rarity: rare\n" +
            "      price: 2200\n" +
            "      currency: coins\n" +
            "      pieces: [GOLDEN_HELMET, GOLDEN_CHESTPLATE, GOLDEN_LEGGINGS, GOLDEN_BOOTS]\n" +
            "      enchants: {protection: 4, unbreaking: 3, mending: 1}\n" +
            "    raider:\n" +
            "      name: \"&3&l⚔ Рейдер\"\n" +
            "      description: [\"&7Надёжный алмазный сет для набегов.\", \"&7Защита IV и вечная прочность.\"]\n" +
            "      rarity: rare\n" +
            "      price: 3500\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {protection: 4, unbreaking: 3, mending: 1}\n" +
            "    firegd:\n" +
            "      name: \"&c&l✦ Огненный страж\"\n" +
            "      description: [\"&7Алмаз, закалённый в лаве.\", \"&7Огнеупорность V и шипы II.\"]\n" +
            "      rarity: rare\n" +
            "      price: 3800\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {fire_protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        chestplate: {thorns: 2}\n" +
            "        boots: {feather_falling: 4}\n" +
            "    archer:\n" +
            "      name: \"&a&l✦ Тень лучника\"\n" +
            "      description: [\"&7Стрелы почти не берут этот сет.\", \"&7Защита от снарядов V.\"]\n" +
            "      rarity: rare\n" +
            "      price: 3800\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {projectile_protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        boots: {feather_falling: 4}\n" +
            "    turtle:\n" +
            "      name: \"&2&l❖ Панцирь черепахи\"\n" +
            "      description: [\"&7Шлем для долгих заплывов.\", \"&7Дышать и видеть под водой.\"]\n" +
            "      rarity: rare\n" +
            "      price: 1500\n" +
            "      currency: coins\n" +
            "      pieces: [TURTLE_HELMET]\n" +
            "      enchants: {protection: 4, respiration: 3, aqua_affinity: 1, unbreaking: 3, mending: 1}\n" +
            "    depths:\n" +
            "      name: \"&9&l❖ Шлем глубин\"\n" +
            "      description: [\"&7Алмазный шлем водолаза.\", \"&7Дыхание III и родство с водой.\"]\n" +
            "      rarity: rare\n" +
            "      price: 900\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET]\n" +
            "      enchants: {protection: 4, respiration: 3, aqua_affinity: 1, unbreaking: 3, mending: 1}\n" +
            "    hermes:\n" +
            "      name: \"&b&l❖ Сапоги Гермеса\"\n" +
            "      description: [\"&7Алмазные ботинки скороходов.\", \"&7Ни падение, ни вода не помеха.\"]\n" +
            "      rarity: rare\n" +
            "      price: 1100\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_BOOTS]\n" +
            "      enchants: {protection: 4, feather_falling: 4, depth_strider: 3, unbreaking: 3, mending: 1}\n" +
            "    wings:\n" +
            "      name: \"&d&l✦ Крылья ветра\"\n" +
            "      description: [\"&7Элитры с усиленной прочностью.\", \"&7Лети туда, где тебя не ждут.\"]\n" +
            "      rarity: rare\n" +
            "      price: 2500\n" +
            "      currency: coins\n" +
            "      pieces: [ELYTRA]\n" +
            "      enchants: {unbreaking: 3, mending: 1}\n" +
            "    legion:\n" +
            "      name: \"&b&l♛ Щит легиона\"\n" +
            "      description: [\"&7Щит, выдержавший сотни битв.\", \"&7Прочность V и починка.\"]\n" +
            "      rarity: rare\n" +
            "      price: 1800\n" +
            "      currency: coins\n" +
            "      pieces: [SHIELD]\n" +
            "      enchants: {unbreaking: 5, mending: 1}\n" +
            "    guard:\n" +
            "      name: \"&b&l♛ Страж\"\n" +
            "      description: [\"&7Полный алмазный сет для боя.\", \"&7Максимальная защита и бонусы под водой.\"]\n" +
            "      rarity: epic\n" +
            "      price: 6000\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        helmet: {respiration: 3, aqua_affinity: 1}\n" +
            "        boots: {feather_falling: 4, depth_strider: 3}\n" +
            "    sea:\n" +
            "      name: \"&9&l✦ Мореход\"\n" +
            "      description: [\"&7Сет для тех, кто живёт у воды.\", \"&7Скорость в воде и дыхание III.\"]\n" +
            "      rarity: epic\n" +
            "      price: 5000\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {protection: 4, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        helmet: {respiration: 3, aqua_affinity: 1}\n" +
            "        boots: {depth_strider: 3, feather_falling: 4}\n" +
            "    frost:\n" +
            "      name: \"&b&l✦ Ледяной дозор\"\n" +
            "      description: [\"&7Алмазный сет холодных вершин.\", \"&7Ледоход II позволяет ходить по воде.\"]\n" +
            "      rarity: epic\n" +
            "      price: 5500\n" +
            "      currency: coins\n" +
            "      pieces: [DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS]\n" +
            "      enchants: {protection: 4, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        chestplate: {thorns: 2}\n" +
            "        boots: {frost_walker: 2, feather_falling: 4}\n" +
            "    blast:\n" +
            "      name: \"&c&l⚔ Подрывник\"\n" +
            "      description: [\"&7Незерит против взрывов.\", \"&7Взрывоустойчивость V: TNT не страшен.\"]\n" +
            "      rarity: epic\n" +
            "      price: 90\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS]\n" +
            "      enchants: {blast_protection: 5, unbreaking: 3, mending: 1}\n" +
            "    ghost:\n" +
            "      name: \"&8&l☠ Призрачный охотник\"\n" +
            "      description: [\"&7Незерит, который не берут стрелы.\", \"&7Защита от снарядов V.\"]\n" +
            "      rarity: epic\n" +
            "      price: 90\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS]\n" +
            "      enchants: {projectile_protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        boots: {feather_falling: 4}\n" +
            "    phoenix:\n" +
            "      name: \"&6&l✦ Крылья феникса\"\n" +
            "      description: [\"&7Элитры, переживающие любой полёт.\", \"&7Прочность V и починка.\"]\n" +
            "      rarity: epic\n" +
            "      price: 40\n" +
            "      currency: cytrix\n" +
            "      pieces: [ELYTRA]\n" +
            "      enchants: {unbreaking: 5, mending: 1}\n" +
            "    titan:\n" +
            "      name: \"&5&l♛ Титан\"\n" +
            "      description: [\"&7Незеритовый сет элитных бойцов.\", \"&7Защита V, шипы и скорость души.\"]\n" +
            "      rarity: legendary\n" +
            "      price: 120\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS]\n" +
            "      enchants: {protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        chestplate: {thorns: 3}\n" +
            "        helmet: {respiration: 3, aqua_affinity: 1}\n" +
            "        boots: {feather_falling: 4, soul_speed: 3}\n" +
            "    firetitan:\n" +
            "      name: \"&c&l☠ Огненный титан\"\n" +
            "      description: [\"&7Незерит, рождённый в пламени.\", \"&7Огнеупорность V, шипы III.\"]\n" +
            "      rarity: legendary\n" +
            "      price: 110\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS]\n" +
            "      enchants: {fire_protection: 5, unbreaking: 3, mending: 1}\n" +
            "      piece-enchants:\n" +
            "        chestplate: {thorns: 3}\n" +
            "        boots: {soul_speed: 3, feather_falling: 4}\n" +
            "    crown:\n" +
            "      name: \"&6&l♛ Корона короля\"\n" +
            "      description: [\"&7Незеритовый шлем правителя.\", \"&7Защита V, прочность V, шипы III.\"]\n" +
            "      rarity: legendary\n" +
            "      price: 60\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET]\n" +
            "      enchants: {protection: 5, unbreaking: 5, mending: 1, respiration: 3, aqua_affinity: 1, thorns: 3}\n" +
            "    immortal:\n" +
            "      name: \"&4&l☠ Бессмертный\"\n" +
            "      description: [\"&7Лучший сет сервера.\", \"&7Защита V, прочность V, шипы III.\"]\n" +
            "      rarity: mythic\n" +
            "      price: 250\n" +
            "      currency: cytrix\n" +
            "      pieces: [NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS]\n" +
            "      enchants: {protection: 5, unbreaking: 5, mending: 1, thorns: 3}\n" +
            "      piece-enchants:\n" +
            "        helmet: {respiration: 3, aqua_affinity: 1}\n" +
            "        boots: {feather_falling: 4, soul_speed: 3}\n";

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private static final Map<String, String> MATERIAL_RU = new HashMap<>();
    private static final Map<String, String> PART_RU = new HashMap<>();
    private static final Map<String, String> ENCH_RU = new HashMap<>();
    private static final Map<String, String> RARITY = new HashMap<>();

    static {
        MATERIAL_RU.put("LEATHER", "Кожаный");
        MATERIAL_RU.put("CHAINMAIL", "Кольчужный");
        MATERIAL_RU.put("IRON", "Железный");
        MATERIAL_RU.put("GOLDEN", "Золотой");
        MATERIAL_RU.put("DIAMOND", "Алмазный");
        MATERIAL_RU.put("NETHERITE", "Незеритовый");
        PART_RU.put("HELMET", "шлем");
        PART_RU.put("CHESTPLATE", "нагрудник");
        PART_RU.put("LEGGINGS", "поножи");
        PART_RU.put("BOOTS", "ботинки");
        RARITY.put("common", "&f&lОБЫЧНЫЙ");
        RARITY.put("rare", "&9&lРЕДКИЙ");
        RARITY.put("epic", "&5&lЭПИЧЕСКИЙ");
        RARITY.put("legendary", "&6&lЛЕГЕНДАРНЫЙ");
        RARITY.put("mythic", "&c&lМИФИЧЕСКИЙ");
        ENCH_RU.put("protection", "Защита");
        ENCH_RU.put("unbreaking", "Прочность");
        ENCH_RU.put("mending", "Починка");
        ENCH_RU.put("feather_falling", "Невесомость");
        ENCH_RU.put("depth_strider", "Подводная ходьба");
        ENCH_RU.put("thorns", "Шипы");
        ENCH_RU.put("respiration", "Подводное дыхание");
        ENCH_RU.put("aqua_affinity", "Родство с водой");
        ENCH_RU.put("soul_speed", "Скорость души");
        ENCH_RU.put("blast_protection", "Взрывоустойчивость");
        ENCH_RU.put("fire_protection", "Огнеупорность");
        ENCH_RU.put("projectile_protection", "Защита от снарядов");
        ENCH_RU.put("frost_walker", "Ледоход");
    }

    private static class ShopItem {
        String key;
        String name;
        List<String> description = new ArrayList<>();
        String rarity = "common";
        int price;
        boolean cytrix;
        List<Material> pieces = new ArrayList<>();
        final LinkedHashMap<String, Integer> enchants = new LinkedHashMap<>();
        final Map<String, LinkedHashMap<String, Integer>> pieceEnchants = new HashMap<>();
        Color color;
    }

    private static class ShopHolder implements InventoryHolder {
        Inventory inv;
        final List<ShopItem> shown = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final GriefBoard plugin;
    private final List<ShopItem> items = new ArrayList<>();
    private UUID npcId;
    private final List<UUID> holoIds = new ArrayList<>();
    private String forcedChunk;
    private boolean cleaned = false;
    private BukkitTask task;

    ShopManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    // ---------- запуск ----------

    void enable() {
        loadItems();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::ensureNpc, 60L, 100L);
    }

    void reload() {
        loadItems();
        removeNpc();
        cleaned = false;
        ensureNpc();
    }

    void disable() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder) p.closeInventory();
        }
        removeNpc();
        World w = world();
        if (w != null && forcedChunk != null) {
            String[] s = forcedChunk.split(",");
            try {
                w.setChunkForceLoaded(Integer.parseInt(s[0]), Integer.parseInt(s[1]), false);
            } catch (NumberFormatException ignored) { }
        }
    }

    private void loadItems() {
        items.clear();
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection("shop.items");
        if (cs == null) {
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new StringReader(DEFAULT_YAML));
            cs = def.getConfigurationSection("shop.items");
        }
        if (cs == null) return;
        for (String key : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(key);
            if (s == null) continue;
            ShopItem it = new ShopItem();
            it.key = key;
            it.name = s.getString("name", key);
            it.description = s.getStringList("description");
            it.rarity = s.getString("rarity", "common").toLowerCase(Locale.ROOT);
            it.price = Math.max(0, s.getInt("price", 0));
            it.cytrix = "cytrix".equalsIgnoreCase(s.getString("currency", "coins"));
            for (String m : s.getStringList("pieces")) {
                Material mat = Material.matchMaterial(m);
                if (mat != null) it.pieces.add(mat);
            }
            if (it.pieces.isEmpty()) continue;
            readEnchants(s.getConfigurationSection("enchants"), it.enchants);
            ConfigurationSection pe = s.getConfigurationSection("piece-enchants");
            if (pe != null) {
                for (String part : pe.getKeys(false)) {
                    LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
                    readEnchants(pe.getConfigurationSection(part), map);
                    it.pieceEnchants.put(part.toLowerCase(Locale.ROOT), map);
                }
            }
            List<Integer> rgb = s.getIntegerList("color");
            if (rgb.size() == 3) it.color = Color.fromRGB(rgb.get(0), rgb.get(1), rgb.get(2));
            items.add(it);
        }
    }

    private void readEnchants(ConfigurationSection s, Map<String, Integer> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k.toLowerCase(Locale.ROOT), s.getInt(k, 1));
    }

    // ---------- NPC и подпись ----------

    private World world() {
        return Bukkit.getWorld(plugin.getConfig().getString("shop.npc.world",
                plugin.getConfig().getString("rtp.lobby-world", "lobby")));
    }

    private Location npcLocation(World w) {
        return new Location(w,
                plugin.getConfig().getDouble("shop.npc.x", 39.557),
                plugin.getConfig().getDouble("shop.npc.y", 64.0),
                plugin.getConfig().getDouble("shop.npc.z", 66.7),
                (float) plugin.getConfig().getDouble("shop.npc.yaw", 149), 0f);
    }

    private void ensureNpc() {
        if (!plugin.getConfig().getBoolean("shop.enabled", true)) return;
        World w = world();
        if (w == null) return;
        Location loc = npcLocation(w);
        if (!cleaned) {
            Chunk ch = loc.getChunk();
            forcedChunk = ch.getX() + "," + ch.getZ();
            w.setChunkForceLoaded(ch.getX(), ch.getZ(), true);
            ch.load();
            for (Entity e : ch.getEntities()) {
                if (e.getScoreboardTags().contains(TAG_NPC) || e.getScoreboardTags().contains(TAG_HOLO)) e.remove();
            }
            npcId = null;
            holoIds.clear();
            cleaned = true;
        }
        Entity npc = npcId == null ? null : Bukkit.getEntity(npcId);
        boolean holoOk = !holoIds.isEmpty();
        for (UUID id : holoIds) {
            Entity h = Bukkit.getEntity(id);
            if (h == null || !h.isValid()) holoOk = false;
        }
        if (npc != null && npc.isValid() && holoOk) return;
        removeNpc();
        spawnNpc(w, loc);
    }

    private void spawnNpc(World w, Location loc) {
        Villager v = w.spawn(loc, Villager.class, e -> {
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setGravity(false);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setProfession(Villager.Profession.ARMORER);
            e.setVillagerType(Villager.Type.PLAINS);
            e.setVillagerLevel(5);
            e.addScoreboardTag(TAG_NPC);
        });
        npcId = v.getUniqueId();
        String[] lines = {"&6&lМагазин брони", "&7ПКМ - открыть"};
        double y = loc.getY() + 2.3;
        for (String line : lines) {
            final String text = c(line);
            ArmorStand st = w.spawn(new Location(w, loc.getX(), y, loc.getZ()), ArmorStand.class, a -> {
                a.setVisible(false);
                a.setGravity(false);
                a.setMarker(true);
                a.setInvulnerable(true);
                a.setSilent(true);
                a.setCollidable(false);
                a.setCustomName(text);
                a.setCustomNameVisible(true);
                a.setPersistent(false);
                a.addScoreboardTag(TAG_HOLO);
            });
            holoIds.add(st.getUniqueId());
            y -= 0.27;
        }
    }

    private void removeNpc() {
        if (npcId != null) {
            Entity e = Bukkit.getEntity(npcId);
            if (e != null) e.remove();
            npcId = null;
        }
        for (UUID id : holoIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        holoIds.clear();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (!en.getScoreboardTags().contains(TAG_NPC)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        open(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity en = e.getEntity();
        if (en.getScoreboardTags().contains(TAG_NPC) || en.getScoreboardTags().contains(TAG_HOLO)) e.setCancelled(true);
    }

    // ---------- меню ----------

    private ItemStack pane() {
        ItemStack it = new ItemStack(Material.ORANGE_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(" ");
            it.setItemMeta(m);
        }
        return it;
    }

    private void open(Player p) {
        ShopHolder h = new ShopHolder();
        Inventory inv = Bukkit.createInventory(h, 54, c("&6&lМагазин брони"));
        h.inv = inv;
        ItemStack pane = pane();
        for (int i = 0; i < 54; i++) {
            if (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) inv.setItem(i, pane);
        }
        for (int i = 0; i < items.size() && i < SLOTS.length; i++) {
            ShopItem it = items.get(i);
            h.shown.add(it);
            inv.setItem(SLOTS[i], display(it));
        }
        inv.setItem(49, info("&e&lТвои деньги",
                "&7Монеты: &e" + money(plugin.getBalance(p.getUniqueId())),
                "&7Cytrix: &a" + money(plugin.getCytrix(p.getUniqueId())),
                " ",
                "&8Покупка: &aЛКМ &8по набору"));
        inv.setItem(53, info("&c&lЗакрыть"));
        p.openInventory(inv);
    }

    private ItemStack info(String name, String... lore) {
        ItemStack it = new ItemStack(name.contains("Закрыть") ? Material.BARRIER : Material.GOLD_INGOT);
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.setDisplayName(c(name));
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(c(s));
        m.setLore(l);
        it.setItemMeta(m);
        return it;
    }

    /** Предмет-витрина: показывает нагрудник (или первый предмет набора) и описание набора. */
    private ItemStack display(ShopItem s) {
        Material icon = s.pieces.get(0);
        for (Material m : s.pieces) {
            if (m.name().endsWith("CHESTPLATE")) icon = m;
        }
        ItemStack it = build(icon, s, true);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName(c(s.name));
        List<String> lore = new ArrayList<>();
        lore.add(c("&8&m                              "));
        lore.add(c("&7Редкость: " + rarityText(s)));
        for (String d : s.description) lore.add(c(d));
        lore.add(c(" "));
        lore.add(c(s.pieces.size() > 1 ? "&7В набор входит:" : "&7Предмет:"));
        for (Material m : s.pieces) lore.add(c(" &8• &f" + materialName(m)));
        lore.add(c(" "));
        lore.add(c("&7Зачарования:"));
        for (Map.Entry<String, Integer> en : s.enchants.entrySet()) lore.add(c(" &8• &b" + enchName(en.getKey(), en.getValue())));
        for (Map.Entry<String, LinkedHashMap<String, Integer>> pe : s.pieceEnchants.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> en : pe.getValue().entrySet()) {
                if (sb.length() > 0) sb.append("&7, ");
                sb.append("&b").append(enchName(en.getKey(), en.getValue()));
            }
            lore.add(c(" &8• &7" + partRu(pe.getKey()) + ": " + sb));
        }
        lore.add(c(" "));
        lore.add(c("&7Цена: " + priceText(s)));
        lore.add(c("&aЛКМ &7- купить"));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_DYE);
        it.setItemMeta(meta);
        return it;
    }

    /** Настоящий предмет, который получит игрок. */
    private ItemStack build(Material m, ShopItem s, boolean forDisplay) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        String part = partOf(m);
        String title = s.pieces.size() > 1 ? s.name + " &8| &f" + capitalize(PART_RU.getOrDefault(part, "")) : s.name;
        meta.setDisplayName(c(title));
        List<String> lore = new ArrayList<>();
        lore.add(c("&7Редкость: " + rarityText(s)));
        for (String d : s.description) lore.add(c(d));
        lore.add(c("&8Магазин брони"));
        meta.setLore(lore);
        applyEnchants(meta, s.enchants);
        LinkedHashMap<String, Integer> extra = s.pieceEnchants.get(part.toLowerCase(Locale.ROOT));
        if (extra != null) applyEnchants(meta, extra);
        if (meta instanceof LeatherArmorMeta && s.color != null) ((LeatherArmorMeta) meta).setColor(s.color);
        it.setItemMeta(meta);
        return it;
    }

    private void applyEnchants(ItemMeta meta, Map<String, Integer> map) {
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            Enchantment en = Enchantment.getByKey(NamespacedKey.minecraft(e.getKey()));
            if (en != null) meta.addEnchant(en, Math.max(1, e.getValue()), true);
        }
    }

    // ---------- клики и покупка ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getClick() != ClickType.LEFT) return;
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();
        if (slot == 53) {
            p.closeInventory();
            return;
        }
        ShopHolder h = (ShopHolder) holder;
        for (int i = 0; i < h.shown.size() && i < SLOTS.length; i++) {
            if (SLOTS[i] == slot) {
                buy(p, h.shown.get(i));
                final Player fp = p;
                Bukkit.getScheduler().runTask(plugin, () -> open(fp));
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    private void buy(Player p, ShopItem s) {
        int balance = s.cytrix ? plugin.getCytrix(p.getUniqueId()) : plugin.getBalance(p.getUniqueId());
        if (balance < s.price) {
            say(p, "&cНе хватает средств: нужно &f" + priceText(s) + "&c, у тебя &f" + money(balance));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st == null || st.getType() == Material.AIR) free++;
        }
        if (free < s.pieces.size()) {
            say(p, "&cОсвободи место в инвентаре: нужно слотов: &f" + s.pieces.size());
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        if (s.cytrix) plugin.addCytrix(p.getUniqueId(), -s.price);
        else plugin.addBalance(p.getUniqueId(), -s.price);
        for (Material m : s.pieces) p.getInventory().addItem(build(m, s, false));
        say(p, "&aТы купил: " + s.name + " &7за &f" + priceText(s));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }

    // ---------- вспомогательное ----------

    private String rarityText(ShopItem s) {
        return RARITY.getOrDefault(s.rarity, "&f&lОБЫЧНЫЙ");
    }

    private String priceText(ShopItem s) {
        return s.cytrix ? "&a" + money(s.price) + " Cytrix" : "&e" + money(s.price) + " монет";
    }

    private static String partOf(Material m) {
        String n = m.name();
        for (String part : PART_RU.keySet()) {
            if (n.endsWith(part)) return part;
        }
        return "";
    }

    private static String partRu(String partKey) {
        String ru = PART_RU.get(partKey.toUpperCase(Locale.ROOT));
        return ru == null ? partKey : capitalize(ru);
    }

    private static String materialName(Material m) {
        String n = m.name();
        if (n.equals("ELYTRA")) return "Элитры";
        if (n.equals("SHIELD")) return "Щит";
        String part = partOf(m);
        String prefix = part.isEmpty() ? "" : n.substring(0, n.length() - part.length() - 1);
        String mat = MATERIAL_RU.get(prefix);
        if (mat != null && !part.isEmpty()) return mat + " " + PART_RU.get(part);
        return n.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String enchName(String key, int level) {
        String name = ENCH_RU.getOrDefault(key, key.replace('_', ' '));
        if (key.equals("mending") || key.equals("aqua_affinity")) return name;
        return name + " " + roman(level);
    }

    private static String roman(int n) {
        String[] r = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String money(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private void say(Player p, String text) {
        p.sendMessage(c(PREFIX + text));
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
