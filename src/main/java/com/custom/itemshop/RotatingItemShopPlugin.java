package com.custom.itemshop;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class RotatingItemShopPlugin extends JavaPlugin implements Listener, CommandExecutor {

    // GUI Slot Constants
    private static final int[] SINGLE_SLOTS = {10, 11, 12, 14, 15, 16}; // Rows 1-2 area (6 slots)
    private static final int[] COMBO_SLOTS = {29, 31, 33};             // Row 4 (3 slots)
    private static final int SLOT_BALANCE_INFO = 45;
    private static final int SLOT_SELL_HAND = 48;
    private static final int SLOT_SELL_ALL = 50;
    private static final int SLOT_TOP5_ICON = 53;

    // PDC Keys
    private NamespacedKey nonRefundableKey;
    private NamespacedKey shopSlotTypeKey;
    private NamespacedKey shopSlotIndexKey;

    // Configurable Settings (loaded from config.yml)
    private int singleRestockSeconds = 150;
    private int comboRestockSeconds = 300;
    private int autoSaveMinutes = 5;
    private double comboDiscountMultiplier = 0.50;
    private boolean enableSneakSwapShop = true;
    private boolean enableSneakDropBaltop = true;
    private boolean enableActionBar = true;

    // Configurable Pricing Maps
    private final Map<Material, Long> explicitSellPrices = new EnumMap<>(Material.class);
    private final Map<String, Long> armorTierSellPrices = new HashMap<>();
    private final Map<String, Long> blockCategoryPrices = new HashMap<>();
    private final Map<String, Long> itemCategoryPrices = new HashMap<>();

    // Economy & Persistence
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private File balancesFile;
    private FileConfiguration balancesConfig;
    private final NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(Locale.US);

    // Shop State & Tasks
    private final List<ShopOffer> singleOffers = new ArrayList<>();
    private final List<ComboOffer> comboOffers = new ArrayList<>();
    private int singleTimer = 150;
    private int comboTimer = 300;
    private BukkitTask mainTickTask;
    private BukkitTask autoSaveTask;

    // Catalog Pools
    private final List<ShopCatalogEntry> itemCatalog = new ArrayList<>();
    private final Set<Material> technicalUnobtainables = EnumSet.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.BARRIER, Material.BEDROCK, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK,
            Material.COMMAND_BLOCK_MINECART, Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID, Material.JIGSAW, Material.LIGHT,
            Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK, Material.SPAWNER,
            Material.TRIAL_SPAWNER, Material.VAULT, Material.END_PORTAL_FRAME,
            Material.REINFORCED_DEEPSLATE, Material.BUDDING_AMETHYST,
            Material.INFESTED_STONE, Material.INFESTED_COBBLESTONE,
            Material.INFESTED_STONE_BRICKS, Material.INFESTED_MOSSY_STONE_BRICKS,
            Material.INFESTED_CRACKED_STONE_BRICKS, Material.INFESTED_CHISELED_STONE_BRICKS,
            Material.INFESTED_DEEPSLATE, Material.PETRIFIED_OAK_SLAB,
            Material.PLAYER_HEAD, Material.PLAYER_WALL_HEAD
    );

    @Override
    public void onEnable() {
        this.currencyFormat.setMaximumFractionDigits(0);
        this.nonRefundableKey = new NamespacedKey(this, "non_refundable");
        this.shopSlotTypeKey = new NamespacedKey(this, "shop_slot_type");
        this.shopSlotIndexKey = new NamespacedKey(this, "shop_slot_index");

        saveDefaultConfig();
        setupBalancesStorage();
        loadPluginConfiguration(true);

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("shop")).setExecutor(this);
        Objects.requireNonNull(getCommand("baltop")).setExecutor(this);

        getLogger().info("RotatingItemShop enabled for Paper 26.3!");
    }

    @Override
    public void onDisable() {
        if (mainTickTask != null) mainTickTask.cancel();
        if (autoSaveTask != null) autoSaveTask.cancel();
        saveBalances();
    }

    // =========================================================================
    // CONFIGURATION LOADER & RELOADER (config.yml)
    // =========================================================================

    public void loadPluginConfiguration(boolean initialLoad) {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        // 1. Timers & General Settings
        this.singleRestockSeconds = Math.max(10, cfg.getInt("timers.single-item-restock-seconds", 150));
        this.comboRestockSeconds = Math.max(10, cfg.getInt("timers.combo-deal-restock-seconds", 300));
        this.autoSaveMinutes = Math.max(1, cfg.getInt("timers.autosave-interval-minutes", 5));
        this.comboDiscountMultiplier = Math.max(0.05, Math.min(1.0, cfg.getDouble("shop.combo-discount-multiplier", 0.50)));
        this.enableSneakSwapShop = cfg.getBoolean("keybinds.sneak-swap-opens-shop", true);
        this.enableSneakDropBaltop = cfg.getBoolean("keybinds.sneak-drop-opens-top3", true);
        this.enableActionBar = cfg.getBoolean("display.live-actionbar", true);

        // 2. Load Sell Pricing Rules
        loadSellConfiguration(cfg);

        // 3. Build Buy Catalog from Config
        buildBuyCatalogFromConfig(cfg);

        // 4. Reset Timers & Restock
        this.singleTimer = this.singleRestockSeconds;
        this.comboTimer = this.comboRestockSeconds;
        restockSingleItems();
        restockComboDeals();

        // 5. Restart Schedulers
        restartSchedulers();

        if (!initialLoad) {
            getLogger().info("Configuration reloaded from config.yml.");
        }
    }

    private void restartSchedulers() {
        if (mainTickTask != null) mainTickTask.cancel();
        if (autoSaveTask != null) autoSaveTask.cancel();

        mainTickTask = getServer().getScheduler().runTaskTimer(this, this::tickShopLoop, 20L, 20L);
        long saveTicks = autoSaveMinutes * 60L * 20L;
        autoSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveBalances, saveTicks, saveTicks);
    }

    private void loadSellConfiguration(FileConfiguration cfg) {
        explicitSellPrices.clear();
        armorTierSellPrices.clear();
        blockCategoryPrices.clear();
        itemCategoryPrices.clear();

        // Armor Tiers ($150 - $1,000)
        armorTierSellPrices.put("LEATHER", cfg.getLong("sell-prices.armor-tiers.LEATHER", 150L));
        armorTierSellPrices.put("COPPER", cfg.getLong("sell-prices.armor-tiers.COPPER", 250L));
        armorTierSellPrices.put("IRON", cfg.getLong("sell-prices.armor-tiers.IRON", 350L));
        armorTierSellPrices.put("GOLDEN", cfg.getLong("sell-prices.armor-tiers.GOLDEN", 500L));
        armorTierSellPrices.put("DIAMOND", cfg.getLong("sell-prices.armor-tiers.DIAMOND", 750L));
        armorTierSellPrices.put("NETHERITE", cfg.getLong("sell-prices.armor-tiers.NETHERITE", 1000L));

        // Block Categories ($10 - $500)
        blockCategoryPrices.put("diamond-emerald-ore", cfg.getLong("sell-prices.blocks.categories.diamond-emerald-ore", 150L));
        blockCategoryPrices.put("other-ores", cfg.getLong("sell-prices.blocks.categories.other-ores", 75L));
        blockCategoryPrices.put("stone-bricks-terracotta-quartz", cfg.getLong("sell-prices.blocks.categories.stone-bricks-terracotta-quartz", 55L));
        blockCategoryPrices.put("logs-wood", cfg.getLong("sell-prices.blocks.categories.logs-wood", 22L));
        blockCategoryPrices.put("flowers", cfg.getLong("sell-prices.blocks.categories.flowers", 18L));
        blockCategoryPrices.put("common-terrain", cfg.getLong("sell-prices.blocks.categories.common-terrain", 12L));
        blockCategoryPrices.put("default-block", cfg.getLong("sell-prices.blocks.categories.default-block", 15L));

        // Specific Block Overrides
        ConfigurationSection specificBlocks = cfg.getConfigurationSection("sell-prices.blocks.specific");
        if (specificBlocks != null) {
            for (String matKey : specificBlocks.getKeys(false)) {
                Material mat = Material.matchMaterial(matKey);
                if (mat != null && mat.isItem()) {
                    explicitSellPrices.put(mat, specificBlocks.getLong(matKey));
                } else {
                    getLogger().warning("Invalid block material in sell-prices.blocks.specific: " + matKey);
                }
            }
        }

        // Item Categories
        itemCategoryPrices.put("seeds-crops", cfg.getLong("sell-prices.items.categories.seeds-crops", 5L));
        itemCategoryPrices.put("netherite-gear", cfg.getLong("sell-prices.items.categories.netherite-gear", 800L));
        itemCategoryPrices.put("diamond-gear", cfg.getLong("sell-prices.items.categories.diamond-gear", 220L));
        itemCategoryPrices.put("iron-gold-gear", cfg.getLong("sell-prices.items.categories.iron-gold-gear", 45L));
        itemCategoryPrices.put("default-item", cfg.getLong("sell-prices.items.categories.default-item", 8L));

        // Specific Item Overrides
        ConfigurationSection specificItems = cfg.getConfigurationSection("sell-prices.items.specific");
        if (specificItems != null) {
            for (String matKey : specificItems.getKeys(false)) {
                Material mat = Material.matchMaterial(matKey);
                if (mat != null && mat.isItem()) {
                    explicitSellPrices.put(mat, specificItems.getLong(matKey));
                } else {
                    getLogger().warning("Invalid item material in sell-prices.items.specific: " + matKey);
                }
            }
        }
    }

    private void buildBuyCatalogFromConfig(FileConfiguration cfg) {
        itemCatalog.clear();

        // 1. Standard Items & Gear list ("MATERIAL:AMOUNT:PRICE")
        List<String> configuredItems = cfg.getStringList("buy-pool.items");
        for (String entry : configuredItems) {
            String[] parts = entry.split(":");
            if (parts.length != 3) {
                getLogger().warning("Malformed buy-pool.items entry (expected MATERIAL:AMOUNT:PRICE): " + entry);
                continue;
            }
            Material mat = Material.matchMaterial(parts[0].trim());
            if (mat == null || !mat.isItem() || mat.isBlock() || technicalUnobtainables.contains(mat)) {
                getLogger().warning("Skipping invalid or block material in buy-pool.items: " + parts[0]);
                continue;
            }
            try {
                int amount = Math.max(1, Integer.parseInt(parts[1].trim()));
                long price = Math.max(1L, Long.parseLong(parts[2].trim()));
                addStandardCatalogItem(mat, amount, price);
            } catch (NumberFormatException e) {
                getLogger().warning("Invalid number in buy-pool.items entry: " + entry);
            }
        }

        // 2. Dynamic Horse & Nautilus Armor in Buy Rotation
        boolean includeArmor = cfg.getBoolean("buy-pool.include-horse-and-nautilus-armor", true);
        double armorBuyMultiplier = cfg.getDouble("buy-pool.armor-buy-multiplier", 2.0);
        boolean includeSpawnEggs = cfg.getBoolean("buy-pool.include-spawn-eggs", true);
        long defaultEggPrice = cfg.getLong("buy-pool.spawn-eggs.default-price", 950L);
        long rareEggPrice = cfg.getLong("buy-pool.spawn-eggs.rare-price", 2500L);
        Set<String> rareEggKeywords = new HashSet<>();
        for (String kw : cfg.getStringList("buy-pool.spawn-eggs.rare-keywords")) {
            rareEggKeywords.add(kw.toUpperCase(Locale.ROOT));
        }

        for (Material mat : Material.values()) {
            if (!mat.isItem() || mat.isBlock() || technicalUnobtainables.contains(mat)) continue;
            String name = mat.name();

            if (includeArmor && (name.endsWith("_HORSE_ARMOR") || name.endsWith("_NAUTILUS_ARMOR"))) {
                long buyPrice = Math.max(50L, Math.round(getArmorBaseValue(mat) * armorBuyMultiplier));
                addStandardCatalogItem(mat, 1, buyPrice);
            } else if (includeSpawnEggs && name.endsWith("_SPAWN_EGG")) {
                boolean isRare = rareEggKeywords.stream().anyMatch(name::contains);
                addStandardCatalogItem(mat, 1, isRare ? rareEggPrice : defaultEggPrice);
            }
        }

        // 3. Max-Level Enchanted Books from RegistryAccess
        if (cfg.getBoolean("buy-pool.enchanted-books.enabled", true)) {
            long lvl1Price = cfg.getLong("buy-pool.enchanted-books.prices.level-1", 1200L);
            long lvl2Price = cfg.getLong("buy-pool.enchanted-books.prices.level-2", 1400L);
            long lvl3Price = cfg.getLong("buy-pool.enchanted-books.prices.level-3", 1650L);
            long lvl4Price = cfg.getLong("buy-pool.enchanted-books.prices.level-4", 1950L);
            long lvl5Price = cfg.getLong("buy-pool.enchanted-books.prices.level-5-plus", 2400L);

            Set<String> excludedEnchants = new HashSet<>();
            for (String ex : cfg.getStringList("buy-pool.enchanted-books.excluded-enchantments")) {
                excludedEnchants.add(ex.toLowerCase(Locale.ROOT));
            }

            Registry<Enchantment> enchantmentRegistry = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ENCHANTMENT);

            for (Enchantment enchantment : enchantmentRegistry) {
                String keyName = enchantment.getKey().getKey().toLowerCase(Locale.ROOT);
                if (excludedEnchants.contains(keyName)) continue;

                int maxLevel = enchantment.getMaxLevel();
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, 1);
                if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
                    meta.addStoredEnchant(enchantment, maxLevel, true);
                    book.setItemMeta(meta);
                }

                long price = switch (maxLevel) {
                    case 1 -> lvl1Price;
                    case 2 -> lvl2Price;
                    case 3 -> lvl3Price;
                    case 4 -> lvl4Price;
                    default -> lvl5Price;
                };

                String enchantTitle = PlainTextComponentSerializer.plainText()
                        .serialize(enchantment.displayName(maxLevel));
                itemCatalog.add(new ShopCatalogEntry(book, enchantTitle, price));
            }
        }

        // Fallback safety in case config list was completely emptied
        if (itemCatalog.isEmpty()) {
            addStandardCatalogItem(Material.DIAMOND, 1, 250L);
        }
    }

    // =========================================================================
    // 1. CURRENCY & PERSISTENCE (balances.yml)
    // =========================================================================

    private void setupBalancesStorage() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        balancesFile = new File(getDataFolder(), "balances.yml");
        if (!balancesFile.exists()) {
            try {
                balancesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Failed to create balances.yml: " + e.getMessage());
            }
        }
        balancesConfig = YamlConfiguration.loadConfiguration(balancesFile);
        for (String key : balancesConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long amount = balancesConfig.getLong(key, 0L);
                balances.put(uuid, Math.max(0L, amount));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid UUID in balances.yml: " + key);
            }
        }
    }

    private synchronized void saveBalances() {
        if (balancesConfig == null || balancesFile == null) return;
        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            balancesConfig.set(entry.getKey().toString(), entry.getValue());
        }
        try {
            balancesConfig.save(balancesFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save balances.yml: " + e.getMessage());
        }
    }

    public long getBalance(UUID uuid) {
        return balances.computeIfAbsent(uuid, k -> 0L);
    }

    public void addBalance(UUID uuid, long amount) {
        if (amount <= 0) return;
        balances.put(uuid, getBalance(uuid) + amount);
    }

    public boolean withdrawBalance(UUID uuid, long amount) {
        long current = getBalance(uuid);
        if (current < amount) return false;
        balances.put(uuid, current - amount);
        return true;
    }

    private String formatMoney(long amount) {
        return currencyFormat.format(amount);
    }

    private String formatTime(int totalSeconds) {
        int mins = totalSeconds / 60;
        int secs = totalSeconds % 60;
        return String.format("%dm %02ds", mins, secs);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        balances.putIfAbsent(event.getPlayer().getUniqueId(), 0L);
    }

    // =========================================================================
    // RESTOCKING & TICK LOOP
    // =========================================================================

    private void addStandardCatalogItem(Material material, int amount, long price) {
        if (!material.isItem() || material.isBlock() || technicalUnobtainables.contains(material)) {
            return;
        }
        ItemStack stack = new ItemStack(material, amount);
        itemCatalog.add(new ShopCatalogEntry(stack, formatMaterialName(material), price));
    }

    private void restockSingleItems() {
        singleOffers.clear();
        if (itemCatalog.isEmpty()) return;

        List<ShopCatalogEntry> pool = new ArrayList<>(itemCatalog);
        Collections.shuffle(pool, ThreadLocalRandom.current());

        int count = Math.min(SINGLE_SLOTS.length, pool.size());
        for (int i = 0; i < count; i++) {
            ShopCatalogEntry entry = pool.get(i);
            singleOffers.add(new ShopOffer(entry.item().clone(), entry.displayName(), entry.price()));
        }
    }

    private void restockComboDeals() {
        comboOffers.clear();
        if (itemCatalog.isEmpty()) return;

        for (int c = 0; c < COMBO_SLOTS.length; c++) {
            List<ShopCatalogEntry> pool = new ArrayList<>(itemCatalog);
            Collections.shuffle(pool, ThreadLocalRandom.current());

            List<ItemStack> bundleItems = new ArrayList<>(4);
            List<String> itemNames = new ArrayList<>(4);
            long rawTotal = 0L;

            for (int i = 0; i < 4; i++) {
                ShopCatalogEntry entry = pool.get(i % pool.size());
                bundleItems.add(entry.item().clone());
                itemNames.add(entry.item().getAmount() + "x " + entry.displayName());
                rawTotal += entry.price();
            }

            long discountedPrice = Math.max(10L, Math.round(rawTotal * comboDiscountMultiplier));
            Material iconMat = bundleItems.get(0).getType();
            comboOffers.add(new ComboOffer(
                    "Mystery Bundle #" + (c + 1),
                    iconMat,
                    bundleItems,
                    itemNames,
                    rawTotal,
                    discountedPrice
            ));
        }
    }

    private void tickShopLoop() {
        singleTimer--;
        if (singleTimer <= 0) {
            singleTimer = singleRestockSeconds;
            restockSingleItems();
        }

        comboTimer--;
        if (comboTimer <= 0) {
            comboTimer = comboRestockSeconds;
            restockComboDeals();
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            long bal = getBalance(player.getUniqueId());

            if (enableActionBar) {
                Component actionBar = Component.text("Balance: ", NamedTextColor.GRAY)
                        .append(Component.text(formatMoney(bal), NamedTextColor.GREEN, TextDecoration.BOLD))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Items: ", NamedTextColor.GRAY))
                        .append(Component.text(formatTime(singleTimer), NamedTextColor.AQUA))
                        .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Combos: ", NamedTextColor.GRAY))
                        .append(Component.text(formatTime(comboTimer), NamedTextColor.LIGHT_PURPLE));
                player.sendActionBar(actionBar);
            }

            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopGuiHolder) {
                populateShopInventory(player, player.getOpenInventory().getTopInventory());
            }
        }
    }

    // =========================================================================
    // 2. 54-SLOT SHOP GUI (/shop & Sneak + Swap Hands)
    // =========================================================================

    public void openShopGui(Player player) {
        ShopGuiHolder holder = new ShopGuiHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                Component.text("Rotating Item Shop", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
        );
        holder.setInventory(inv);
        populateShopInventory(player, inv);
        player.openInventory(inv);
    }

    private void populateShopInventory(Player player, Inventory inv) {
        long playerBalance = getBalance(player.getUniqueId());

        ItemStack filler = createPane(Material.BLACK_STAINED_GLASS_PANE, Component.empty());
        ItemStack divider = createPane(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 18; i <= 26; i++) inv.setItem(i, divider);
        for (int i = 36; i <= 44; i++) inv.setItem(i, divider);

        // Row 1 Header / Timer Indicator (Slot 4)
        ItemStack singleTimerIcon = new ItemStack(Material.CLOCK);
        ItemMeta singleTimerMeta = singleTimerIcon.getItemMeta();
        singleTimerMeta.displayName(Component.text("Single Items Rotation", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        singleTimerMeta.lore(List.of(
                Component.text("Restocks in: ", NamedTextColor.GRAY)
                        .append(Component.text(formatTime(singleTimer), NamedTextColor.YELLOW))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Your Balance: ", NamedTextColor.GRAY)
                        .append(Component.text(formatMoney(playerBalance), NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        singleTimerIcon.setItemMeta(singleTimerMeta);
        inv.setItem(4, singleTimerIcon);

        // Rows 1-2: 6 Single Items
        for (int i = 0; i < SINGLE_SLOTS.length && i < singleOffers.size(); i++) {
            ShopOffer offer = singleOffers.get(i);
            ItemStack display = offer.item().clone();
            ItemMeta meta = display.getItemMeta();

            List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(Objects.requireNonNull(meta.lore()))
                    : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("Price: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(offer.price()), NamedTextColor.GOLD, TextDecoration.BOLD))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Your Balance: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(playerBalance),
                            playerBalance >= offer.price() ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Restocks in: ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(formatTime(singleTimer), NamedTextColor.AQUA))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(playerBalance >= offer.price() ? "Click to purchase!" : "Insufficient funds!",
                            playerBalance >= offer.price() ? NamedTextColor.YELLOW : NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            meta.getPersistentDataContainer().set(shopSlotTypeKey, PersistentDataType.STRING, "SINGLE");
            meta.getPersistentDataContainer().set(shopSlotIndexKey, PersistentDataType.INTEGER, i);
            display.setItemMeta(meta);
            inv.setItem(SINGLE_SLOTS[i], display);
        }

        // Row 4: 3 Combo Deals (4 items each)
        int discountPercent = (int) Math.round((1.0 - comboDiscountMultiplier) * 100);
        for (int i = 0; i < COMBO_SLOTS.length && i < comboOffers.size(); i++) {
            ComboOffer combo = comboOffers.get(i);
            ItemStack display = new ItemStack(combo.iconMaterial());
            ItemMeta meta = display.getItemMeta();

            meta.displayName(Component.text(combo.title() + " (" + discountPercent + "% OFF!)", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Includes 4 Items:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            for (String itemLine : combo.itemDescriptions()) {
                lore.add(Component.text(" • " + itemLine, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Original Value: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(combo.originalPrice()), NamedTextColor.RED, TextDecoration.STRIKETHROUGH))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Bundle Price: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(combo.discountedPrice()), NamedTextColor.GREEN, TextDecoration.BOLD))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Your Balance: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(playerBalance),
                            playerBalance >= combo.discountedPrice() ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Restocks in: ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(formatTime(comboTimer), NamedTextColor.LIGHT_PURPLE))
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(playerBalance >= combo.discountedPrice() ? "Click to buy bundle!" : "Insufficient funds!",
                            playerBalance >= combo.discountedPrice() ? NamedTextColor.YELLOW : NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(shopSlotTypeKey, PersistentDataType.STRING, "COMBO");
            meta.getPersistentDataContainer().set(shopSlotIndexKey, PersistentDataType.INTEGER, i);
            display.setItemMeta(meta);
            inv.setItem(COMBO_SLOTS[i], display);
        }

        // Row 6 (Bottom Bar)
        ItemStack balanceItem = new ItemStack(Material.SUNFLOWER);
        ItemMeta balMeta = balanceItem.getItemMeta();
        balMeta.displayName(Component.text("Your Wallet", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        balMeta.lore(List.of(
                Component.text("Balance: ", NamedTextColor.GRAY)
                        .append(Component.text(formatMoney(playerBalance), NamedTextColor.GREEN, TextDecoration.BOLD))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Items Restock: ", NamedTextColor.GRAY)
                        .append(Component.text(formatTime(singleTimer), NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Combos Restock: ", NamedTextColor.GRAY)
                        .append(Component.text(formatTime(comboTimer), NamedTextColor.LIGHT_PURPLE))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        balanceItem.setItemMeta(balMeta);
        inv.setItem(SLOT_BALANCE_INFO, balanceItem);

        // Slot 48: Sell Main Hand Item
        ItemStack handItem = player.getInventory().getItemInMainHand();
        long handValue = calculateSellValue(handItem);
        ItemStack sellHandBtn = new ItemStack(Material.GOLD_INGOT);
        ItemMeta sellHandMeta = sellHandBtn.getItemMeta();
        sellHandMeta.displayName(Component.text("Sell Held Item", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> handLore = new ArrayList<>();
        if (handValue > 0) {
            handLore.add(Component.text("Holding: ", NamedTextColor.GRAY)
                    .append(Component.text(handItem.getAmount() + "x " + formatMaterialName(handItem.getType()), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false));
            handLore.add(Component.text("Sell Value: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMoney(handValue), NamedTextColor.GREEN, TextDecoration.BOLD))
                    .decoration(TextDecoration.ITALIC, false));
            handLore.add(Component.empty());
            handLore.add(Component.text("Click to sell held stack!", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        } else {
            handLore.add(Component.text("Hold any eligible item or block", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            handLore.add(Component.text("to sell it for instant cash.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if (isNonRefundable(handItem)) {
                handLore.add(Component.text("Shop items are non-refundable!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            }
        }
        sellHandMeta.lore(handLore);
        sellHandBtn.setItemMeta(sellHandMeta);
        inv.setItem(SLOT_SELL_HAND, sellHandBtn);

        // Slot 50: Sell All Eligible Inventory Items
        long invTotalValue = calculateInventorySellValue(player);
        ItemStack sellAllBtn = new ItemStack(Material.HOPPER);
        ItemMeta sellAllMeta = sellAllBtn.getItemMeta();
        sellAllMeta.displayName(Component.text("Sell Inventory / Drop Item Here", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        sellAllMeta.lore(List.of(
                Component.text("• Click with cursor item to sell that item", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("• Empty-cursor click sells all eligible items", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Eligible Inventory Value: ", NamedTextColor.GRAY)
                        .append(Component.text(formatMoney(invTotalValue), NamedTextColor.GOLD, TextDecoration.BOLD))
                        .decoration(TextDecoration.ITALIC, false)
        ));
        sellAllBtn.setItemMeta(sellAllMeta);
        inv.setItem(SLOT_SELL_ALL, sellAllBtn);

        // Slot 53: Top 5 Richest Players Icon
        ItemStack top5Btn = new ItemStack(Material.BEACON);
        ItemMeta top5Meta = top5Btn.getItemMeta();
        top5Meta.displayName(Component.text("Top 5 Richest Players", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        top5Meta.lore(List.of(
                Component.text("Click to view the Top 5", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("richest players on the server!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        top5Meta.setEnchantmentGlintOverride(true);
        top5Btn.setItemMeta(top5Meta);
        inv.setItem(SLOT_TOP5_ICON, top5Btn);
    }

    // =========================================================================
    // 3 & 6. TOP 5 & TOP 3 LEADERBOARDS (Sorted Descending + Player Heads)
    // =========================================================================

    private List<Map.Entry<UUID, Long>> getTopBalances(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    public void openTop5LeaderboardGui(Player player) {
        Top5GuiHolder holder = new Top5GuiHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                27,
                Component.text("Top 5 Richest Players", NamedTextColor.GOLD, TextDecoration.BOLD)
        );
        holder.setInventory(inv);

        ItemStack filler = createPane(Material.BLACK_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        List<Map.Entry<UUID, Long>> top5 = getTopBalances(5);
        int[] headSlots = {11, 12, 13, 14, 15};

        for (int i = 0; i < top5.size() && i < headSlots.length; i++) {
            Map.Entry<UUID, Long> entry = top5.get(i);
            inv.setItem(headSlots[i], createLeaderboardHead(i + 1, entry.getKey(), entry.getValue()));
        }

        // Back to Shop Button (Slot 22)
        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        backMeta.displayName(Component.text("Back to Shop", NamedTextColor.YELLOW, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        backMeta.lore(List.of(
                Component.text("Return to the Rotating Item Shop", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        backBtn.setItemMeta(backMeta);
        inv.setItem(22, backBtn);

        player.openInventory(inv);
    }

    public void openTop3Leaderboard(Player player) {
        Top3GuiHolder holder = new Top3GuiHolder();
        Inventory inv = Bukkit.createInventory(
                holder,
                27,
                Component.text("Top 3 Richest Players", NamedTextColor.AQUA, TextDecoration.BOLD)
        );
        holder.setInventory(inv);

        ItemStack filler = createPane(Material.BLACK_STAINED_GLASS_PANE, Component.empty());
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        List<Map.Entry<UUID, Long>> top3 = getTopBalances(3);
        int[] podiumSlots = {11, 13, 15}; // #1 at 11, #2 at 13, #3 at 15

        player.sendMessage(Component.text("=== Top 3 Richest Players ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        if (top3.isEmpty()) {
            player.sendMessage(Component.text("No player balances recorded yet.", NamedTextColor.GRAY));
        }

        for (int i = 0; i < top3.size() && i < podiumSlots.length; i++) {
            Map.Entry<UUID, Long> entry = top3.get(i);
            int rank = i + 1;
            inv.setItem(podiumSlots[i], createLeaderboardHead(rank, entry.getKey(), entry.getValue()));

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.getKey());
            String name = offlinePlayer.getName() != null ? offlinePlayer.getName() : entry.getKey().toString().substring(0, 8);
            NamedTextColor rankColor = switch (rank) {
                case 1 -> NamedTextColor.GOLD;
                case 2 -> NamedTextColor.WHITE;
                default -> NamedTextColor.YELLOW;
            };

            player.sendMessage(Component.text("#" + rank + " ", rankColor, TextDecoration.BOLD)
                    .append(Component.text(name, NamedTextColor.AQUA))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(formatMoney(entry.getValue()), NamedTextColor.GREEN, TextDecoration.BOLD)));
        }

        player.openInventory(inv);
    }

    private ItemStack createLeaderboardHead(int rank, UUID uuid, long balance) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            skullMeta.setOwningPlayer(offlinePlayer);

            String playerName = offlinePlayer.getName() != null
                    ? offlinePlayer.getName()
                    : uuid.toString().substring(0, 8);

            NamedTextColor rankColor = switch (rank) {
                case 1 -> NamedTextColor.GOLD;
                case 2 -> NamedTextColor.GRAY;
                case 3 -> NamedTextColor.YELLOW;
                default -> NamedTextColor.AQUA;
            };

            skullMeta.displayName(Component.text("#" + rank + " " + playerName, rankColor, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            skullMeta.lore(List.of(
                    Component.text("Balance: ", NamedTextColor.GRAY)
                            .append(Component.text(formatMoney(balance), NamedTextColor.GREEN, TextDecoration.BOLD))
                            .decoration(TextDecoration.ITALIC, false)
            ));
            head.setItemMeta(skullMeta);
        }
        return head;
    }

    // =========================================================================
    // 4 & 5. CONFIG-DRIVEN SELLING RULES & NON-REFUNDABLE TAG
    // =========================================================================

    private ItemStack applyNonRefundableTag(ItemStack item) {
        ItemStack tagged = item.clone();
        ItemMeta meta = tagged.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(nonRefundableKey, PersistentDataType.BYTE, (byte) 1);
            List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(Objects.requireNonNull(meta.lore()))
                    : new ArrayList<>();
            lore.add(Component.text("Non-Refundable (Shop Item)", NamedTextColor.DARK_RED)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            tagged.setItemMeta(meta);
        }
        return tagged;
    }

    private boolean isNonRefundable(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(nonRefundableKey, PersistentDataType.BYTE);
    }

    public long getUnitSellPrice(Material material) {
        if (material == null || !material.isItem() || technicalUnobtainables.contains(material)) {
            return 0L;
        }
        String name = material.name();
        if (name.contains("SPAWN_EGG") || name.contains("COMMAND_BLOCK") || name.contains("STRUCTURE")) {
            return 0L;
        }

        // 1. Horse & Nautilus Armor ($150 - $1,000 dynamically)
        if (name.endsWith("_HORSE_ARMOR") || name.endsWith("_NAUTILUS_ARMOR")) {
            return getArmorBaseValue(material);
        }

        // 2. Explicit Specific Material Overrides from config.yml
        Long explicitPrice = explicitSellPrices.get(material);
        if (explicitPrice != null) {
            return Math.max(0L, explicitPrice);
        }

        // 3. Block Categories ($10 - $500)
        if (material.isBlock()) {
            if (name.endsWith("_ORE")) {
                if (name.contains("DIAMOND") || name.contains("EMERALD")) {
                    return blockCategoryPrices.getOrDefault("diamond-emerald-ore", 150L);
                }
                return blockCategoryPrices.getOrDefault("other-ores", 75L);
            }
            if (name.contains("TERRACOTTA") || name.contains("STONE_BRICKS") || name.contains("QUARTZ") || name.contains("PRISMARINE")) {
                return blockCategoryPrices.getOrDefault("stone-bricks-terracotta-quartz", 55L);
            }
            if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
                return blockCategoryPrices.getOrDefault("logs-wood", 22L);
            }
            if (name.contains("FLOWER") || name.contains("TULIP") || name.contains("ORCHID")
                    || material == Material.DANDELION || material == Material.POPPY || material == Material.SUNFLOWER) {
                return blockCategoryPrices.getOrDefault("flowers", 18L);
            }
            if (material == Material.DIRT || material == Material.GRASS_BLOCK || material == Material.COBBLESTONE
                    || material == Material.STONE || material == Material.SAND || material == Material.RED_SAND
                    || material == Material.GRAVEL || material == Material.NETHERRACK || material == Material.DEEPSLATE) {
                return blockCategoryPrices.getOrDefault("common-terrain", 12L);
            }
            return blockCategoryPrices.getOrDefault("default-block", 15L);
        }

        // 4. Standard Item Categories ($5 up to $2,500+)
        if (name.endsWith("_SEEDS") || name.contains("SAPLING") || name.equals("WHEAT")
                || name.equals("CARROT") || name.equals("POTATO") || name.equals("BEETROOT")) {
            return itemCategoryPrices.getOrDefault("seeds-crops", 5L);
        }
        if (name.startsWith("NETHERITE_")) {
            return itemCategoryPrices.getOrDefault("netherite-gear", 800L);
        }
        if (name.startsWith("DIAMOND_")) {
            return itemCategoryPrices.getOrDefault("diamond-gear", 220L);
        }
        if (name.startsWith("IRON_") || name.startsWith("GOLDEN_")) {
            return itemCategoryPrices.getOrDefault("iron-gold-gear", 45L);
        }
        return itemCategoryPrices.getOrDefault("default-item", 8L);
    }

    private long getArmorBaseValue(Material material) {
        String name = material.name();
        if (name.startsWith("NETHERITE_")) return armorTierSellPrices.getOrDefault("NETHERITE", 1000L);
        if (name.startsWith("DIAMOND_")) return armorTierSellPrices.getOrDefault("DIAMOND", 750L);
        if (name.startsWith("GOLDEN_")) return armorTierSellPrices.getOrDefault("GOLDEN", 500L);
        if (name.startsWith("IRON_")) return armorTierSellPrices.getOrDefault("IRON", 350L);
        if (name.startsWith("COPPER_")) return armorTierSellPrices.getOrDefault("COPPER", 250L);
        if (name.startsWith("LEATHER_")) return armorTierSellPrices.getOrDefault("LEATHER", 150L);
        return armorTierSellPrices.getOrDefault("LEATHER", 150L);
    }

    private long calculateSellValue(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || isNonRefundable(stack)) {
            return 0L;
        }
        long unit = getUnitSellPrice(stack.getType());
        return unit * stack.getAmount();
    }

    private long calculateInventorySellValue(Player player) {
        long total = 0L;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            total += calculateSellValue(item);
        }
        return total;
    }

    // =========================================================================
    // EVENTS (GUI Clicks, Sneak+Swap Hands, Sneak+Drop Key)
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof Top5GuiHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == 22) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
                openShopGui(player);
            }
            return;
        }

        if (holder instanceof Top3GuiHolder) {
            event.setCancelled(true);
            return;
        }

        if (!(holder instanceof ShopGuiHolder)) return;

        if (event.getClickedInventory() != top) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                long value = calculateSellValue(clicked);
                if (value > 0 && clicked != null) {
                    event.setCurrentItem(null);
                    addBalance(player.getUniqueId(), value);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                    player.sendMessage(Component.text("Sold ", NamedTextColor.GREEN)
                            .append(Component.text(clicked.getAmount() + "x " + formatMaterialName(clicked.getType()), NamedTextColor.WHITE))
                            .append(Component.text(" for ", NamedTextColor.GREEN))
                            .append(Component.text(formatMoney(value), NamedTextColor.GOLD, TextDecoration.BOLD)));
                    populateShopInventory(player, top);
                } else if (isNonRefundable(clicked)) {
                    player.sendMessage(Component.text("Shop items are tagged Non-Refundable and cannot be resold!", NamedTextColor.RED));
                }
            }
            return;
        }

        event.setCancelled(true);
        int slot = event.getRawSlot();

        if (slot == SLOT_TOP5_ICON) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            openTop5LeaderboardGui(player);
            return;
        }

        if (slot == SLOT_SELL_HAND) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            long value = calculateSellValue(hand);
            if (value <= 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                if (isNonRefundable(hand)) {
                    player.sendMessage(Component.text("That item was bought from the shop and is Non-Refundable!", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("You are not holding a sellable item or block!", NamedTextColor.RED));
                }
                return;
            }
            String desc = hand.getAmount() + "x " + formatMaterialName(hand.getType());
            player.getInventory().setItemInMainHand(null);
            addBalance(player.getUniqueId(), value);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            player.sendMessage(Component.text("Sold " + desc + " for ", NamedTextColor.GREEN)
                    .append(Component.text(formatMoney(value), NamedTextColor.GOLD, TextDecoration.BOLD)));
            populateShopInventory(player, top);
            return;
        }

        if (slot == SLOT_SELL_ALL) {
            ItemStack cursor = event.getCursor();
            if (!cursor.getType().isAir()) {
                long cursorVal = calculateSellValue(cursor);
                if (cursorVal <= 0) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    player.sendMessage(Component.text("That item cannot be sold!", NamedTextColor.RED));
                    return;
                }
                String desc = cursor.getAmount() + "x " + formatMaterialName(cursor.getType());
                event.getView().setCursor(null);
                addBalance(player.getUniqueId(), cursorVal);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
                player.sendMessage(Component.text("Sold " + desc + " for ", NamedTextColor.GREEN)
                        .append(Component.text(formatMoney(cursorVal), NamedTextColor.GOLD, TextDecoration.BOLD)));
                populateShopInventory(player, top);
                return;
            }

            long totalSoldValue = 0L;
            int itemsSoldCount = 0;
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                long val = calculateSellValue(item);
                if (val > 0 && item != null) {
                    totalSoldValue += val;
                    itemsSoldCount += item.getAmount();
                    contents[i] = null;
                }
            }

            if (totalSoldValue <= 0) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                player.sendMessage(Component.text("No eligible items found in your inventory to sell!", NamedTextColor.RED));
                return;
            }

            player.getInventory().setStorageContents(contents);
            addBalance(player.getUniqueId(), totalSoldValue);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            player.sendMessage(Component.text("Sold " + itemsSoldCount + " items for ", NamedTextColor.GREEN)
                    .append(Component.text(formatMoney(totalSoldValue), NamedTextColor.GOLD, TextDecoration.BOLD)));
            populateShopInventory(player, top);
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String type = meta.getPersistentDataContainer().get(shopSlotTypeKey, PersistentDataType.STRING);
        Integer index = meta.getPersistentDataContainer().get(shopSlotIndexKey, PersistentDataType.INTEGER);
        if (type == null || index == null) return;

        if ("SINGLE".equals(type) && index >= 0 && index < singleOffers.size()) {
            ShopOffer offer = singleOffers.get(index);
            if (!withdrawBalance(player.getUniqueId(), offer.price())) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                player.sendMessage(Component.text("You need " + formatMoney(offer.price()) + " to buy this!", NamedTextColor.RED));
                return;
            }
            ItemStack purchased = applyNonRefundableTag(offer.item());
            giveOrDrop(player, purchased);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            player.sendMessage(Component.text("Purchased ", NamedTextColor.GREEN)
                    .append(Component.text(offer.item().getAmount() + "x " + offer.displayName(), NamedTextColor.AQUA))
                    .append(Component.text(" for ", NamedTextColor.GREEN))
                    .append(Component.text(formatMoney(offer.price()), NamedTextColor.GOLD, TextDecoration.BOLD)));
            populateShopInventory(player, top);
        } else if ("COMBO".equals(type) && index >= 0 && index < comboOffers.size()) {
            ComboOffer combo = comboOffers.get(index);
            if (!withdrawBalance(player.getUniqueId(), combo.discountedPrice())) {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                player.sendMessage(Component.text("You need " + formatMoney(combo.discountedPrice()) + " to buy this bundle!", NamedTextColor.RED));
                return;
            }
            for (ItemStack bundleItem : combo.items()) {
                giveOrDrop(player, applyNonRefundableTag(bundleItem));
            }
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            player.sendMessage(Component.text("Purchased ", NamedTextColor.GREEN)
                    .append(Component.text(combo.title(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                    .append(Component.text(" for ", NamedTextColor.GREEN))
                    .append(Component.text(formatMoney(combo.discountedPrice()), NamedTextColor.GOLD, TextDecoration.BOLD)));
            populateShopInventory(player, top);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ShopGuiHolder || holder instanceof Top5GuiHolder || holder instanceof Top3GuiHolder) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < event.getView().getTopInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSneakSwapHands(PlayerSwapHandItemsEvent event) {
        if (!enableSneakSwapShop) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            event.setCancelled(true);
            openShopGui(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSneakDropItem(PlayerDropItemEvent event) {
        if (!enableSneakDropBaltop) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            event.setCancelled(true);
            openTop3Leaderboard(player);
        }
    }

    // =========================================================================
    // COMMANDS (/shop, /shop reload, /shop restock, /baltop, /richest)
    // =========================================================================

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if (cmdName.equals("shop")) {
            if (args.length > 0) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (sub.equals("reload")) {
                    if (!sender.hasPermission("rotatingitemshop.admin") && !sender.isOp()) {
                        sender.sendMessage(Component.text("You do not have permission to reload RotatingItemShop.", NamedTextColor.RED));
                        return true;
                    }
                    loadPluginConfiguration(false);
                    sender.sendMessage(Component.text("RotatingItemShop config.yml reloaded and shop restocked!", NamedTextColor.GREEN));
                    return true;
                }
                if (sub.equals("restock")) {
                    if (!sender.hasPermission("rotatingitemshop.admin") && !sender.isOp()) {
                        sender.sendMessage(Component.text("You do not have permission to force a restock.", NamedTextColor.RED));
                        return true;
                    }
                    this.singleTimer = this.singleRestockSeconds;
                    this.comboTimer = this.comboRestockSeconds;
                    restockSingleItems();
                    restockComboDeals();
                    sender.sendMessage(Component.text("Forced restock of all shop items and combo deals!", NamedTextColor.GREEN));
                    return true;
                }
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can open the shop GUI. Use /shop reload from console.", NamedTextColor.RED));
                return true;
            }
            openShopGui(player);
            return true;
        }

        if (cmdName.equals("baltop") || cmdName.equals("richest")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can open the leaderboard GUI.", NamedTextColor.RED));
                return true;
            }
            openTop3Leaderboard(player);
            return true;
        }
        return false;
    }

    // =========================================================================
    // HELPERS & DATA RECORDS
    // =========================================================================

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private ItemStack createPane(Material material, Component name) {
        ItemStack pane = new ItemStack(material);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            pane.setItemMeta(meta);
        }
        return pane;
    }

    private String formatMaterialName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private record ShopCatalogEntry(ItemStack item, String displayName, long price) {}

    private record ShopOffer(ItemStack item, String displayName, long price) {}

    private record ComboOffer(
            String title,
            Material iconMaterial,
            List<ItemStack> items,
            List<String> itemDescriptions,
            long originalPrice,
            long discountedPrice
    ) {}

    private static final class ShopGuiHolder implements InventoryHolder {
        private Inventory inventory;
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private static final class Top5GuiHolder implements InventoryHolder {
        private Inventory inventory;
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }

    private static final class Top3GuiHolder implements InventoryHolder {
        private Inventory inventory;
        public void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override public @NotNull Inventory getInventory() { return inventory; }
    }
}
