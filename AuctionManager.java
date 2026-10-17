package ru.griefboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Аукцион /ah: игроки выставляют предметы за монеты (баланс), другие их покупают.
 * Меню с листаниями, сортировкой, подтверждением покупки, «Мои лоты» и «Просроченные».
 * Лоты хранятся в plugins/GriefBoard/ah.yml.
 */
public class AuctionManager implements Listener, CommandExecutor, TabCompleter {

    private static final String PREFIX = "&8[&6Аукцион&8] ";
    private static final String[] SORTS = {"Сначала новые", "Сначала дешёвые", "Сначала дорогие"};

    private static class Listing {
        int id;
        UUID seller;
        String sellerName;
        int price;
        long created;
        long expires;
        ItemStack item;
    }

    private static class AhHolder implements InventoryHolder {
        final String type;
        int page;
        int sort;
        int listingId;
        int[] ids = new int[0];
        Inventory inv;

        AhHolder(String type) {
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
    private final Map<Integer, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> expired = new HashMap<>();
    private int nextId = 1;

    AuctionManager(GriefBoard plugin) {
        this.plugin = plugin;
    }

    // ---------- запуск и хранение ----------

    void enable() {
        plugin.getDataFolder().mkdirs();
        file = new File(plugin.getDataFolder(), "ah.yml");
        yml = YamlConfiguration.loadConfiguration(file);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("ah") != null) {
            plugin.getCommand("ah").setExecutor(this);
            plugin.getCommand("ah").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickExpire, 100L, 1200L);
    }

    void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof AhHolder) p.closeInventory();
        }
        save();
    }

    private void load() {
        listings.clear();
        expired.clear();
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection root = yml.getConfigurationSection("listings");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) continue;
                Listing l = new Listing();
                try {
                    l.id = Integer.parseInt(key);
                    l.seller = UUID.fromString(s.getString("seller", ""));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                l.sellerName = s.getString("seller-name", "?");
                l.price = s.getInt("price");
                l.created = s.getLong("created");
                l.expires = s.getLong("expires");
                l.item = s.getItemStack("item");
                if (l.item == null) continue;
                listings.put(l.id, l);
                nextId = Math.max(nextId, l.id + 1);
            }
        }
        ConfigurationSection ex = yml.getConfigurationSection("expired");
        if (ex != null) {
            for (String key : ex.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    List<ItemStack> items = new ArrayList<>();
                    List<?> raw = ex.getList(key);
                    if (raw != null) {
                        for (Object o : raw) {
                            if (o instanceof ItemStack) items.add((ItemStack) o);
                        }
                    }
                    if (!items.isEmpty()) expired.put(id, items);
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private void save() {
        yml.set("next-id", nextId);
        yml.set("listings", null);
        for (Listing l : listings.values()) {
            String b = "listings." + l.id + ".";
            yml.set(b + "seller", l.seller.toString());
            yml.set(b + "seller-name", l.sellerName);
            yml.set(b + "price", l.price);
            yml.set(b + "created", l.created);
            yml.set(b + "expires", l.expires);
            yml.set(b + "item", l.item);
        }
        yml.set("expired", null);
        for (Map.Entry<UUID, List<ItemStack>> e : expired.entrySet()) {
            if (!e.getValue().isEmpty()) yml.set("expired." + e.getKey(), e.getValue());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить ah.yml: " + ex.getMessage());
        }
    }

    /** Раз в минуту: просроченные лоты уходят в «Просроченные» продавца. */
    private void tickExpire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Listing l : new ArrayList<>(listings.values())) {
            if (l.expires <= now) {
                expire(l);
                changed = true;
            }
        }
        if (changed) save();
    }

    private void expire(Listing l) {
        listings.remove(l.id);
        expired.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
        Player p = Bukkit.getPlayer(l.seller);
        if (p != null) say(p, "&7Срок лота истёк: &f" + itemName(l.item) + "&7. Забери предмет: &e/ah expired");
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        Player p = (Player) sender;
        if (args.length == 0) {
            openMain(p, 0, 0);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell": sell(p, args); break;
            case "my": openMy(p); break;
            case "expired": openExpired(p); break;
            default: help(p); break;
        }
        return true;
    }

    private void help(Player p) {
        say(p, "&6Команды аукциона:");
        p.sendMessage(c("&e/ah &7- открыть аукцион"));
        p.sendMessage(c("&e/ah sell <цена> &7- продать предмет из руки (цена за весь стак)"));
        p.sendMessage(c("&e/ah my &7- твои лоты, &e/ah expired &7- просроченные"));
        p.sendMessage(c("&7Налог с продажи: &f" + cfgInt("tax-percent", 5) + "%&7, срок лота: &f"
                + cfgInt("duration-hours", 48) + " ч&7, максимум лотов: &f" + cfgInt("max-listings", 5)));
    }

    private void sell(Player p, String[] args) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) { say(p, "&cВозьми предмет в руку."); return; }
        if (args.length != 2) { say(p, "&cИспользуй: &e/ah sell <цена> &7(цена за весь стак в руке)"); return; }
        long price;
        try {
            price = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            say(p, "&cЦена должна быть числом.");
            return;
        }
        long min = cfgInt("min-price", 1);
        long max = cfgInt("max-price", 1000000000);
        if (price < min || price > max) { say(p, "&cЦена от &f" + money((int) min) + " &cдо &f" + money((int) max) + "&c."); return; }
        int limit = cfgInt("max-listings", 5);
        int mine = 0;
        for (Listing l : listings.values()) {
            if (l.seller.equals(p.getUniqueId())) mine++;
        }
        if (mine >= limit && !p.hasPermission("griefboard.admin")) {
            say(p, "&cУ тебя уже &f" + limit + " &cлотов. Сними один: &e/ah my");
            return;
        }
        Listing l = new Listing();
        l.id = nextId++;
        l.seller = p.getUniqueId();
        l.sellerName = p.getName();
        l.price = (int) price;
        l.created = System.currentTimeMillis();
        l.expires = l.created + cfgInt("duration-hours", 48) * 3600_000L;
        l.item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        listings.put(l.id, l);
        save();
        say(p, "&aЛот выставлен: &f" + itemName(l.item) + " &7за &e" + money(l.price) + " &7монет. Срок: &f"
                + cfgInt("duration-hours", 48) + " ч");
    }

    // ---------- меню ----------

    private ItemStack icon(Material m, String name, String... lore) {
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

    private ItemStack withLore(ItemStack base, String... extra) {
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
        for (String s : extra) lore.add(c(s));
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void fillBottom(Inventory inv, int size) {
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = size - 9; i < size; i++) inv.setItem(i, pane);
    }

    private List<Listing> sorted(int sort) {
        List<Listing> list = new ArrayList<>(listings.values());
        if (sort == 1) list.sort(Comparator.comparingInt((Listing l) -> l.price).thenComparingLong(l -> -l.created));
        else if (sort == 2) list.sort(Comparator.comparingInt((Listing l) -> -l.price).thenComparingLong(l -> -l.created));
        else list.sort(Comparator.comparingLong((Listing l) -> -l.created));
        return list;
    }

    private void openMain(Player p, int page, int sort) {
        tickExpire();
        List<Listing> all = sorted(sort);
        int pages = Math.max(1, (all.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));

        AhHolder h = new AhHolder("main");
        h.page = page;
        h.sort = sort;
        Inventory inv = Bukkit.createInventory(h, 54, c("&6&lАукцион &8| &7стр. " + (page + 1) + "/" + pages));
        h.inv = inv;

        int from = page * 45;
        int n = Math.min(45, all.size() - from);
        h.ids = new int[Math.max(0, n)];
        for (int i = 0; i < n; i++) {
            Listing l = all.get(from + i);
            h.ids[i] = l.id;
            inv.setItem(i, withLore(l.item,
                    "&8&m                    ",
                    "&7Цена: &e" + money(l.price) + " &7монет",
                    "&7Продавец: &f" + l.sellerName,
                    "&7Осталось: &f" + timeLeft(l.expires),
                    " ",
                    l.seller.equals(p.getUniqueId()) ? "&8Это твой лот" : "&aЛКМ &7- купить"));
        }
        fillBottom(inv, 54);
        if (page > 0) inv.setItem(45, icon(Material.ARROW, "&e&l← Назад", "&7Страница " + page));
        inv.setItem(46, icon(Material.HOPPER, "&b&lСортировка", "&7Сейчас: &f" + SORTS[sort], " ", "&eЛКМ &7- сменить"));
        inv.setItem(47, icon(Material.CHEST, "&6&lМои лоты", "&7Снять свои лоты с продажи", " ", "&eЛКМ &7- открыть"));
        int exp = expired.getOrDefault(p.getUniqueId(), Collections.<ItemStack>emptyList()).size();
        inv.setItem(48, icon(Material.ENDER_CHEST, "&c&lПросроченные", "&7Предметы, которые не купили: &f" + exp, " ", "&eЛКМ &7- открыть"));
        inv.setItem(49, icon(Material.GOLD_INGOT, "&e&lТвой баланс", "&7Монеты: &e" + money(plugin.getBalance(p.getUniqueId())),
                "&7Налог с продажи: &f" + cfgInt("tax-percent", 5) + "%", "&7Выставить предмет: &e/ah sell <цена>"));
        inv.setItem(50, icon(Material.CLOCK, "&a&lОбновить", "&eЛКМ &7- обновить список"));
        if (page < pages - 1) inv.setItem(53, icon(Material.ARROW, "&e&lВперёд →", "&7Страница " + (page + 2)));
        p.openInventory(inv);
    }

    private void openConfirm(Player p, Listing l) {
        AhHolder h = new AhHolder("confirm");
        h.listingId = l.id;
        Inventory inv = Bukkit.createInventory(h, 27, c("&a&lПодтверди покупку"));
        h.inv = inv;
        ItemStack pane = icon(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(13, withLore(l.item, "&8&m                    ", "&7Цена: &e" + money(l.price) + " &7монет",
                "&7Продавец: &f" + l.sellerName));
        inv.setItem(11, icon(Material.LIME_STAINED_GLASS_PANE, "&a&lКупить",
                "&7Спишется: &e" + money(l.price) + " &7монет", "&7У тебя: &e" + money(plugin.getBalance(p.getUniqueId())), " ", "&eЛКМ &7- подтвердить"));
        inv.setItem(15, icon(Material.RED_STAINED_GLASS_PANE, "&c&lОтмена", "&eЛКМ &7- вернуться"));
        p.openInventory(inv);
    }

    private void openMy(Player p) {
        AhHolder h = new AhHolder("my");
        Inventory inv = Bukkit.createInventory(h, 54, c("&6&lМои лоты"));
        h.inv = inv;
        List<Integer> ids = new ArrayList<>();
        for (Listing l : sorted(0)) {
            if (!l.seller.equals(p.getUniqueId()) || ids.size() >= 45) continue;
            ids.add(l.id);
            inv.setItem(ids.size() - 1, withLore(l.item, "&8&m                    ",
                    "&7Цена: &e" + money(l.price) + " &7монет", "&7Осталось: &f" + timeLeft(l.expires), " ",
                    "&cЛКМ &7- снять с продажи"));
        }
        h.ids = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) h.ids[i] = ids.get(i);
        fillBottom(inv, 54);
        inv.setItem(49, icon(Material.ARROW, "&e&lНазад к аукциону"));
        p.openInventory(inv);
    }

    private void openExpired(Player p) {
        AhHolder h = new AhHolder("expired");
        Inventory inv = Bukkit.createInventory(h, 54, c("&c&lПросроченные лоты"));
        h.inv = inv;
        List<ItemStack> list = expired.getOrDefault(p.getUniqueId(), new ArrayList<ItemStack>());
        for (int i = 0; i < list.size() && i < 45; i++) {
            inv.setItem(i, withLore(list.get(i), "&8&m                    ", "&aЛКМ &7- забрать"));
        }
        fillBottom(inv, 54);
        inv.setItem(49, icon(Material.ARROW, "&e&lНазад к аукциону"));
        p.openInventory(inv);
    }

    // ---------- клики ----------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof AhHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (e.getClick() != org.bukkit.event.inventory.ClickType.LEFT) return;
        Player p = (Player) e.getWhoClicked();
        AhHolder h = (AhHolder) holder;
        int slot = e.getRawSlot();
        switch (h.type) {
            case "main": clickMain(p, h, slot); break;
            case "confirm": clickConfirm(p, h, slot); break;
            case "my": clickMy(p, h, slot); break;
            case "expired": clickExpired(p, slot); break;
            default: break;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AhHolder) e.setCancelled(true);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private void clickMain(Player p, AhHolder h, int slot) {
        if (slot < 45) {
            if (slot >= h.ids.length) return;
            Listing l = listings.get(h.ids[slot]);
            if (l == null) { say(p, "&cЭтот лот уже продан или снят."); later(() -> openMain(p, h.page, h.sort)); return; }
            if (l.seller.equals(p.getUniqueId())) { say(p, "&7Это твой лот. Снять его можно в &e«Мои лоты»&7."); return; }
            later(() -> openConfirm(p, l));
            return;
        }
        switch (slot) {
            case 45: later(() -> openMain(p, h.page - 1, h.sort)); break;
            case 53: later(() -> openMain(p, h.page + 1, h.sort)); break;
            case 46: later(() -> openMain(p, 0, (h.sort + 1) % SORTS.length)); break;
            case 47: later(() -> openMy(p)); break;
            case 48: later(() -> openExpired(p)); break;
            case 50: later(() -> openMain(p, h.page, h.sort)); break;
            default: break;
        }
    }

    private void clickConfirm(Player p, AhHolder h, int slot) {
        if (slot == 11) {
            buy(p, h.listingId);
            later(() -> openMain(p, 0, 0));
        } else if (slot == 15) {
            later(() -> openMain(p, 0, 0));
        }
    }

    private void clickMy(Player p, AhHolder h, int slot) {
        if (slot == 49) { later(() -> openMain(p, 0, 0)); return; }
        if (slot < 0 || slot >= h.ids.length) return;
        Listing l = listings.get(h.ids[slot]);
        if (l == null || !l.seller.equals(p.getUniqueId())) { later(() -> openMy(p)); return; }
        listings.remove(l.id);
        if (hasSpace(p, l.item)) {
            p.getInventory().addItem(l.item.clone());
            say(p, "&7Лот снят, предмет возвращён: &f" + itemName(l.item));
        } else {
            expired.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(l.item);
            say(p, "&7Лот снят. Инвентарь полон, предмет ждёт в &e/ah expired");
        }
        save();
        later(() -> openMy(p));
    }

    private void clickExpired(Player p, int slot) {
        if (slot == 49) { later(() -> openMain(p, 0, 0)); return; }
        List<ItemStack> list = expired.get(p.getUniqueId());
        if (list == null || slot < 0 || slot >= list.size() || slot >= 45) return;
        ItemStack it = list.get(slot);
        if (!hasSpace(p, it)) { say(p, "&cОсвободи место в инвентаре."); return; }
        list.remove(slot);
        if (list.isEmpty()) expired.remove(p.getUniqueId());
        p.getInventory().addItem(it.clone());
        save();
        say(p, "&aТы забрал: &f" + itemName(it));
        later(() -> openExpired(p));
    }

    // ---------- покупка ----------

    private void buy(Player p, int id) {
        Listing l = listings.get(id);
        if (l == null) { say(p, "&cЭтот лот уже продан или снят."); return; }
        if (l.expires <= System.currentTimeMillis()) { expire(l); save(); say(p, "&cСрок этого лота истёк."); return; }
        if (l.seller.equals(p.getUniqueId())) { say(p, "&cНельзя купить свой лот."); return; }
        int bal = plugin.getBalance(p.getUniqueId());
        if (bal < l.price) { say(p, "&cНе хватает монет: нужно &e" + money(l.price) + "&c, у тебя &e" + money(bal)); return; }
        if (!hasSpace(p, l.item)) { say(p, "&cОсвободи место в инвентаре."); return; }

        int tax = (int) Math.floor(l.price * cfgInt("tax-percent", 5) / 100.0);
        plugin.addBalance(p.getUniqueId(), -l.price);
        plugin.addBalance(l.seller, l.price - tax);
        listings.remove(id);
        save();
        p.getInventory().addItem(l.item.clone());
        say(p, "&aТы купил &f" + itemName(l.item) + " &aза &e" + money(l.price) + " &aмонет.");
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            say(seller, "&aТвой лот куплен: &f" + itemName(l.item) + " &7(" + p.getName() + "). Получено &e"
                    + money(l.price - tax) + " &7монет, налог &f" + money(tax));
        }
    }

    private boolean hasSpace(Player p, ItemStack it) {
        PlayerInventory inv = p.getInventory();
        int need = it.getAmount();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() == Material.AIR) free += it.getMaxStackSize();
            else if (s.isSimilar(it)) free += Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (free >= need) return true;
        }
        return free >= need;
    }

    // ---------- вспомогательное ----------

    private String timeLeft(long expires) {
        long ms = Math.max(0, expires - System.currentTimeMillis());
        long h = ms / 3600_000L;
        long m = (ms / 60_000L) % 60;
        return h + "ч " + m + "м";
    }

    private String itemName(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? ChatColor.stripColor(meta.getDisplayName())
                : it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return name + " x" + it.getAmount();
    }

    private static String money(int n) {
        return String.format(Locale.US, "%,d", n).replace(',', ' ');
    }

    private int cfgInt(String key, int def) {
        return plugin.getConfig().getInt("ah." + key, def);
    }

    private void say(Player p, String text) {
        p.sendMessage(c(PREFIX + text));
    }

    private static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.addAll(Arrays.asList("sell", "my", "expired", "help"));
        return out;
    }
}
