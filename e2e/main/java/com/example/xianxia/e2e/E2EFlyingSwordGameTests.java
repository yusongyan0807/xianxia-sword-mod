package com.example.xianxia.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Opaque-box NeoForge GameTest suite for xianxia_placeholder (Minecraft 1.21.1).
 * Uses exclusively public Minecraft/NeoForge APIs and requirement IDs.
 *
 * <p>Player fixture uses a zero-testframework-dependency {@link ServerPlayer} backed by
 * {@link EmbeddedChannel}, {@link NetworkRegistry#configureMockConnection(Connection)}, and
 * {@link net.minecraft.server.players.PlayerList#placeNewPlayer} with deterministic
 * {@code try/finally} cleanup and {@link ServerPlayer#tick()} / {@link ServerPlayer#doTick()} so both
 * {@code instanceof ServerPlayer} and {@code instanceof Player} event guards are exercised.</p>
 */
@GameTestHolder("xianxia_placeholder")
@PrefixGameTestTemplate(false)
public final class E2EFlyingSwordGameTests {

    private static final String MOD_ID = "xianxia_placeholder";
    private static final ResourceLocation SWORD_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "qingfeng_flying_sword");
    private static final ResourceLocation TAB_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "xianxia_tab");

    private E2EFlyingSwordGameTests() {
    }

    // =========================================================================
    // Opaque-Box Lookup & ServerPlayer Fixtures
    // =========================================================================

    private static void checkTrue(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static void checkFalse(GameTestHelper helper, boolean condition, String message) {
        if (condition) {
            helper.fail(message);
        }
    }

    private static Item requireSwordItem(GameTestHelper helper) {
        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(SWORD_ID);
        checkTrue(helper, opt.isPresent(), "Expected item registered under " + SWORD_ID);
        return opt.orElseThrow();
    }

    private static CreativeModeTab requireCreativeTab(GameTestHelper helper) {
        Optional<CreativeModeTab> opt = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(TAB_ID);
        checkTrue(helper, opt.isPresent(), "Expected creative tab registered under " + TAB_ID);
        return opt.orElseThrow();
    }

    private static Collection<ItemStack> buildTabItems(GameTestHelper helper, CreativeModeTab tab, boolean hasPermissions) {
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                FeatureFlags.DEFAULT_FLAGS,
                hasPermissions,
                helper.getLevel().registryAccess()
        ));
        return tab.getDisplayItems();
    }

    private static JsonObject loadLangJson(GameTestHelper helper, String locale) {
        String resourcePath = "assets/" + MOD_ID + "/lang/" + locale + ".json";
        try (InputStream in = E2EFlyingSwordGameTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            checkTrue(helper, in != null, "Missing lang resource on classpath: " + resourcePath);
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (IOException e) {
            helper.fail("Failed to read lang resource " + resourcePath + ": " + e.getMessage());
            return new JsonObject();
        }
    }

    private static ServerPlayer createMockServerPlayer(GameTestHelper helper, GameType gameType) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "e2e_" + suffix);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        try {
            player.setGameMode(gameType);
            tickPlayer(player, 65);
        } catch (Throwable t) {
            try {
                cleanupMockServerPlayer(player);
            } catch (Throwable cleanupErr) {
                t.addSuppressed(cleanupErr);
            }
            throw t;
        }
        return player;
    }

    private static void cleanupMockServerPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        try {
            if (player.connection != null) {
                player.connection.disconnect(Component.literal("e2e cleanup"));
            }
        } finally {
            MinecraftServer server = player.serverLevel().getServer();
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
                server.getPlayerList().remove(player);
            }
        }
    }

    private static void withMockServerPlayer(GameTestHelper helper, GameType gameType, Consumer<ServerPlayer> action) {
        ServerPlayer player = createMockServerPlayer(helper, gameType);
        try {
            action.accept(player);
        } finally {
            cleanupMockServerPlayer(player);
        }
    }

    private static void tickPlayer(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.tick();
            player.doTick();
        }
    }

    private static InteractionResultHolder<ItemStack> useItemInHand(ServerLevel level, Player player, InteractionHand hand) {
        ItemStack inHand = player.getItemInHand(hand);
        InteractionResultHolder<ItemStack> result = inHand.use(level, player, hand);
        ItemStack returned = result.getObject();
        if (returned != inHand) {
            player.setItemInHand(hand, returned);
        }
        return result;
    }

    private static void assertFlightGranted(GameTestHelper helper, Player player, String context) {
        checkTrue(helper, player.mayFly(), context + ": player.mayFly() should be true");
        double val = player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT);
        checkTrue(helper, Math.abs(val - 1.0D) < 1e-6,
                context + ": CREATIVE_FLIGHT attribute should be 1.0, was " + val);
    }

    private static void assertFlightRevoked(GameTestHelper helper, Player player, String context) {
        checkFalse(helper, player.mayFly(), context + ": player.mayFly() should be false");
        double val = player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT);
        checkTrue(helper, val <= 0.0D, context + ": CREATIVE_FLIGHT should be <= 0.0, was " + val);
        checkFalse(helper, player.getAbilities().mayfly, context + ": abilities.mayfly should be false");
        checkFalse(helper, player.getAbilities().flying, context + ": abilities.flying should be false");
    }

    // =========================================================================
    // F2 & F3 Runtime Mod Metadata / Registry Checks (F2-T1-05, F3-T1-05)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void f2_t1_05_modListContainsMetadata(GameTestHelper helper) {
        Optional<? extends ModContainer> container = ModList.get().getModContainerById(MOD_ID);
        checkTrue(helper, container.isPresent(), "ModList must contain container for " + MOD_ID);
        var info = container.orElseThrow().getModInfo();
        checkTrue(helper, MOD_ID.equals(info.getModId()), "ModInfo modId mismatch");
        checkFalse(helper, info.getDisplayName().isBlank(), "ModInfo displayName must be non-blank");
        checkTrue(helper, "0.1.0-alpha".equals(info.getVersion().toString()),
                "Expected version 0.1.0-alpha, got " + info.getVersion());
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f3_t1_05_liveRegistriesAndModLoaded(GameTestHelper helper) {
        checkTrue(helper, ModList.get().isLoaded(MOD_ID), "ModList.isLoaded must be true for " + MOD_ID);
        checkTrue(helper, BuiltInRegistries.ITEM.containsKey(SWORD_ID), "Missing registered item: " + SWORD_ID);
        checkTrue(helper, BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(TAB_ID), "Missing registered tab: " + TAB_ID);
        helper.succeed();
    }

    // =========================================================================
    // F4 — Item & Creative Tab Registration (Tier 1 & Tier 2)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void f4_t1_01_swordItemRegisteredUnderExpectedId(GameTestHelper helper) {
        Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(SWORD_ID);
        checkTrue(helper, opt.isPresent(), "BuiltInRegistries.ITEM must contain " + SWORD_ID);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t1_02_swordItemIsNonAirAndProducesValidStack(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        checkTrue(helper, sword != Items.AIR, "Registered sword item must not be Items.AIR");
        ItemStack stack = sword.getDefaultInstance();
        checkFalse(helper, stack.isEmpty(), "getDefaultInstance() must not be empty");
        checkTrue(helper, stack.getCount() == 1, "Default stack count must be 1");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t1_03_creativeTabRegisteredUnderExpectedId(GameTestHelper helper) {
        Optional<CreativeModeTab> tabOpt = BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(TAB_ID);
        checkTrue(helper, tabOpt.isPresent(), "BuiltInRegistries.CREATIVE_MODE_TAB must contain " + TAB_ID);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t1_04_creativeTabContainsFlyingSwordInDisplayItems(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        Collection<ItemStack> items = buildTabItems(helper, tab, false);
        boolean found = items.stream().anyMatch(s -> s.is(sword));
        checkTrue(helper, found, "CreativeModeTab display items must contain " + SWORD_ID);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t1_05_swordAndTabExposeExpectedDescriptionIds(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        checkTrue(helper, "item.xianxia_placeholder.qingfeng_flying_sword".equals(sword.getDescriptionId()),
                "Unexpected descriptionId: " + sword.getDescriptionId());
        checkFalse(helper, tab.getDisplayName().getString().isBlank(), "Tab display name must be non-blank");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_01_swordDefaultStackIsUnstackableMaxSizeOne(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        ItemStack stack = sword.getDefaultInstance();
        checkTrue(helper, stack.getMaxStackSize() == 1, "Expected maxStackSize == 1");
        checkFalse(helper, stack.isStackable(), "Flying sword must not be stackable");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_02_creativeTabIconIsNonEmptyAndMatchesSword(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        ItemStack icon = tab.getIconItem();
        checkFalse(helper, icon.isEmpty(), "Creative tab icon must not be empty");
        checkTrue(helper, icon.is(sword), "Creative tab icon must match flying sword");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_03_creativeTabDisplayItemsNoAirOrDuplicateSword(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        for (boolean perms : new boolean[]{false, true}) {
            Collection<ItemStack> items = buildTabItems(helper, tab, perms);
            long swordCount = items.stream().filter(s -> s.is(sword)).count();
            boolean anyEmpty = items.stream().anyMatch(ItemStack::isEmpty);
            checkFalse(helper, anyEmpty, "Tab display items must contain no empty stacks (perms=" + perms + ")");
            checkTrue(helper, swordCount == 1, "Expected exactly 1 sword entry in tab (perms=" + perms + "), got " + swordCount);
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_04_unknownItemAndTabIdsReturnEmptyOptional(GameTestHelper helper) {
        checkTrue(helper, BuiltInRegistries.ITEM.getOptional(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "qingfeng_flying_sword_unknown")).isEmpty(),
                "Unknown item ID should return empty Optional");
        checkTrue(helper, BuiltInRegistries.CREATIVE_MODE_TAB.getOptional(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "nonexistent_tab")).isEmpty(),
                "Unknown tab ID should return empty Optional");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_05_swordAndTabTranslationKeysConsistent(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        String itemKey = sword.getDescriptionId();
        checkTrue(helper, "item.xianxia_placeholder.qingfeng_flying_sword".equals(itemKey),
                "Item descriptionId mismatch");
        checkTrue(helper, tab.getDisplayName().getContents() instanceof TranslatableContents,
                "Tab display name should use TranslatableContents");
        String tabKey = ((TranslatableContents) tab.getDisplayName().getContents()).getKey();
        checkFalse(helper, tabKey.isBlank(), "Tab translation key must be non-blank");

        JsonObject enUs = loadLangJson(helper, "en_us");
        JsonObject zhCn = loadLangJson(helper, "zh_cn");
        checkTrue(helper, enUs.has(itemKey) && !enUs.get(itemKey).getAsString().isBlank(),
                "en_us.json missing non-blank key: " + itemKey);
        checkTrue(helper, zhCn.has(itemKey) && !zhCn.get(itemKey).getAsString().isBlank(),
                "zh_cn.json missing non-blank key: " + itemKey);
        checkTrue(helper, enUs.has(tabKey) && !enUs.get(tabKey).getAsString().isBlank(),
                "en_us.json missing non-blank key: " + tabKey);
        checkTrue(helper, zhCn.has(tabKey) && !zhCn.get(tabKey).getAsString().isBlank(),
                "zh_cn.json missing non-blank key: " + tabKey);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f4_t2_06_swordStackEqualityAndCopyPreserveContract(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        ItemStack a = sword.getDefaultInstance();
        ItemStack b = new ItemStack(sword);
        ItemStack copy = a.copy();
        checkTrue(helper, ItemStack.isSameItemSameComponents(a, b), "Default stacks should match item and components");
        checkTrue(helper, copy.getMaxStackSize() == 1 && copy.getCount() == 1, "Copy must preserve count=1 and maxStackSize=1");
        helper.succeed();
    }

    // =========================================================================
    // F5 — Feet-Slot Equippable Contract (Tier 1 & Tier 2)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void f5_t1_01_swordImplementsPublicEquipableForFeet(GameTestHelper helper) {
        ItemStack stack = requireSwordItem(helper).getDefaultInstance();
        Equipable equipable = Equipable.get(stack);
        checkTrue(helper, equipable != null, "Equipable.get(sword) must be non-null");
        checkTrue(helper, equipable.getEquipmentSlot() == EquipmentSlot.FEET,
                "Equipable slot must be EquipmentSlot.FEET");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t1_02_playerResolvesFeetSlotForSword(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            ItemStack stack = requireSwordItem(helper).getDefaultInstance();
            checkTrue(helper, player.getEquipmentSlotForItem(stack) == EquipmentSlot.FEET,
                    "Player.getEquipmentSlotForItem must return FEET");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t1_03_canEquipAcceptsFeetSlot(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            ItemStack stack = requireSwordItem(helper).getDefaultInstance();
            checkTrue(helper, stack.canEquip(EquipmentSlot.FEET, player), "canEquip(FEET, player) must return true");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t1_04_rightClickUseEquipsSwordIntoEmptyFeetSlot(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());

            InteractionResultHolder<ItemStack> result = useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            checkTrue(helper, result.getResult().consumesAction(), "use() should consume action when equipping");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "FEET slot should hold sword after use()");
            checkTrue(helper, player.getMainHandItem().isEmpty(), "MAIN_HAND should be empty after equipping");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t1_05_equipSoundIsPresentAndNonNull(GameTestHelper helper) {
        ItemStack stack = requireSwordItem(helper).getDefaultInstance();
        Equipable equipable = Equipable.get(stack);
        checkTrue(helper, equipable != null && equipable.getEquipSound() != null,
                "Equipable.getEquipSound() must be non-null");
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_01_canEquipRejectsAllNonFeetSlots(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            ItemStack stack = requireSwordItem(helper).getDefaultInstance();
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
            }) {
                checkFalse(helper, stack.canEquip(slot, player), "canEquip must reject slot " + slot);
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_02_rightClickUseWorksFromOffHand(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemInHand(InteractionHand.OFF_HAND, sword.getDefaultInstance());

            InteractionResultHolder<ItemStack> result = useItemInHand(helper.getLevel(), player, InteractionHand.OFF_HAND);
            checkTrue(helper, result.getResult().consumesAction(), "OFF_HAND use() should consume action");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "FEET slot must hold sword");
            checkTrue(helper, player.getOffhandItem().isEmpty(), "OFF_HAND must be empty after equip");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_03_rightClickUseSwapsWithExistingLeatherBoots(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());

            InteractionResultHolder<ItemStack> result = useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            checkTrue(helper, result.getResult().consumesAction(), "Swapping with boots should consume action");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "FEET must now hold sword");
            checkTrue(helper, player.getMainHandItem().is(Items.LEATHER_BOOTS), "MAIN_HAND must receive swapped LEATHER_BOOTS");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_04_rightClickLeatherBootsSwapsEquippedSwordBackToHand(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemSlot(EquipmentSlot.FEET, sword.getDefaultInstance());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEATHER_BOOTS));

            InteractionResultHolder<ItemStack> result = useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            checkTrue(helper, result.getResult().consumesAction(), "Boots use() should swap with equipped sword");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS), "FEET must now hold LEATHER_BOOTS");
            checkTrue(helper, player.getMainHandItem().is(sword), "MAIN_HAND must receive swapped flying sword");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_05_shiftClickQuickMoveEquipsToFeetOnly(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.getInventory().setItem(0, sword.getDefaultInstance()); // hotbar slot 0 == InventoryMenu slot 36

            player.inventoryMenu.quickMoveStack(player, 36);
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "quickMoveStack must equip sword to FEET");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "HEAD must remain empty");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "CHEST must remain empty");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.LEGS).isEmpty(), "LEGS must remain empty");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f5_t2_06_inventoryAddRejectsStackMergingBeyondOne(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            checkTrue(helper, player.getInventory().add(sword.getDefaultInstance()), "First sword add must succeed");
            checkTrue(helper, player.getInventory().add(sword.getDefaultInstance()), "Second sword add must succeed");

            int slotsWithSword = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack s = player.getInventory().getItem(i);
                if (s.is(sword)) {
                    checkTrue(helper, s.getCount() == 1, "Sword stack count must never exceed 1, found " + s.getCount());
                    slotsWithSword++;
                }
            }
            checkTrue(helper, slotsWithSword == 2, "Two swords must occupy two distinct slots, found " + slotsWithSword);
        });
        helper.succeed();
    }

    // =========================================================================
    // F6 — Sword Flight Grant & Clean Unequip Removal (Tier 1 & Tier 2)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void f6_t1_01_survivalWithoutSwordCannotFly(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "F6-T1-01");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t1_02_survivalEquipSwordGrantsFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "F6-T1-02");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t1_03_survivalUnequipSwordRevokesFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "F6-T1-03 equipped");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "F6-T1-03 unequipped");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t1_04_midAirFlyingStateClearedImmediatelyOnUnequip(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "F6-T1-04 equipped");

            player.getAbilities().flying = true;
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 1);
            assertFlightRevoked(helper, player, "F6-T1-04 mid-air unequip");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t1_05_adventureEquipGrantsAndUnequipRevokesFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.ADVENTURE, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "F6-T1-05 adventure equipped");

            player.getAbilities().flying = true;
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 1);
            assertFlightRevoked(helper, player, "F6-T1-05 adventure unequipped");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_01_creativeFlightPreservedOnEquipAndUnequip(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.CREATIVE, player -> {
            player.getAbilities().flying = true;

            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "Creative flight must remain active when equipping sword");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "Creative flight must remain preserved after unequipping sword");
            checkTrue(helper, player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                    "Sword CREATIVE_FLIGHT modifier must be removed on unequip");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_02_spectatorFlightPreservedOnEquipAndUnequip(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SPECTATOR, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "Spectator flight must remain active on equip");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "Spectator flight must remain preserved on unequip");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_03_holdingSwordInHandsDoesNotGrantFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemSlot(EquipmentSlot.MAINHAND, sword.getDefaultInstance());
            player.setItemSlot(EquipmentSlot.OFFHAND, sword.getDefaultInstance());
            tickPlayer(player, 3);
            assertFlightRevoked(helper, player, "F6-T2-03 hands only");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_04_repeatedEquipUnequipCyclingLeavesZeroResidualModifiers(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            for (int cycle = 0; cycle < 5; cycle++) {
                player.setItemSlot(EquipmentSlot.FEET, sword.getDefaultInstance());
                tickPlayer(player, 2);
                assertFlightGranted(helper, player, "Cycle " + cycle + " equipped");
                player.getAbilities().flying = true;

                player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                tickPlayer(player, 2);
                assertFlightRevoked(helper, player, "Cycle " + cycle + " unequipped");
            }
            AttributeInstance attr = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            checkTrue(helper, attr != null && attr.getModifiers().isEmpty(),
                    "Expected zero residual CREATIVE_FLIGHT modifiers after 5 cycles");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_05_fallDamageImmuneWhileEquippedAndRestoredOnUnequip(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);

            float maxHp = player.getMaxHealth();
            player.setHealth(maxHp);
            boolean damagedEquipped = player.causeFallDamage(12.0F, 1.0F, player.damageSources().fall());
            checkFalse(helper, damagedEquipped, "Player must be immune to fall damage while sword is equipped");
            checkTrue(helper, player.getHealth() == maxHp, "Health must stay unchanged during fall immunity");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            player.invulnerableTime = 0;
            boolean damagedUnequipped = player.causeFallDamage(12.0F, 1.0F, player.damageSources().fall());
            checkTrue(helper, damagedUnequipped, "Fall damage must apply again after unequipping sword");
            checkTrue(helper, player.getHealth() < maxHp, "Health must decrease after unequipped fall");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_06_replacingSwordWithVanillaBootsRevokesMidAirFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "F6-T2-06 equipped");

            player.getAbilities().flying = true;
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            tickPlayer(player, 1);
            assertFlightRevoked(helper, player, "F6-T2-06 replaced with DIAMOND_BOOTS");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void f6_t2_07_gamemodeTransitionCreativeToSurvivalRespectsEquippedState(GameTestHelper helper) {
        // Part A: No sword equipped -> switching CREATIVE -> SURVIVAL revokes flight
        withMockServerPlayer(helper, GameType.CREATIVE, playerA -> {
            playerA.getAbilities().flying = true;
            tickPlayer(playerA, 1);
            playerA.setGameMode(GameType.SURVIVAL);
            tickPlayer(playerA, 2);
            assertFlightRevoked(helper, playerA, "F6-T2-07 Part A (no sword)");
        });

        // Part B: Sword equipped -> switching CREATIVE -> SURVIVAL preserves sword flight until unequipped
        withMockServerPlayer(helper, GameType.CREATIVE, playerB -> {
            playerB.setItemSlot(EquipmentSlot.FEET, requireSwordItem(helper).getDefaultInstance());
            tickPlayer(playerB, 2);
            playerB.getAbilities().flying = true;
            playerB.setGameMode(GameType.SURVIVAL);
            tickPlayer(playerB, 2);
            assertFlightGranted(helper, playerB, "F6-T2-07 Part B (sword equipped)");

            playerB.getAbilities().flying = true;
            playerB.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(playerB, 2);
            assertFlightRevoked(helper, playerB, "F6-T2-07 Part B (sword unequipped)");
        });
        helper.succeed();
    }

    // =========================================================================
    // F8 Runtime Prerequisite (F8-T1-05)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void f8_t1_05_serverFeetSlotVisualStateSyncedAndCleared(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);

            player.setItemSlot(EquipmentSlot.FEET, sword.getDefaultInstance());
            tickPlayer(player, 1);
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "setItemSlot must populate FEET");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 1);
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).isEmpty(), "Unequip must clear FEET");

            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 1);
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "use() must populate FEET");
        });
        helper.succeed();
    }

    // =========================================================================
    // Tier 3 — Cross-Feature Pairwise Combinations (T3-01, T3-02, T3-03, T3-08, T3-09)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void t3_01_creativeTabSwordEquipsToFeetViaUse(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        Optional<ItemStack> fromTabOpt = buildTabItems(helper, tab, false).stream()
                .filter(s -> s.is(sword))
                .findFirst();
        checkTrue(helper, fromTabOpt.isPresent(), "Sword missing from xianxia_tab");
        ItemStack fromTab = fromTabOpt.get().copy();

        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, fromTab);
            InteractionResultHolder<ItemStack> result = useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            checkTrue(helper, result.getResult().consumesAction(), "Tab sword use() should equip");
            checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "FEET slot should hold tab sword");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t3_02_rightClickUseEquipGrantsAndBootsSwapRevokesFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "T3-02 equipped via use()");

            player.getAbilities().flying = true;
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEATHER_BOOTS));
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "T3-02 swapped to LEATHER_BOOTS");
            checkTrue(helper, player.getMainHandItem().is(sword), "Swapped sword returned to hand");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t3_03_shiftClickInventoryEquipAndUnequipFlightCycle(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.getInventory().setItem(0, sword.getDefaultInstance());

            player.inventoryMenu.quickMoveStack(player, 36);
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "T3-03 quickMoveStack equip");
            float maxHp = player.getMaxHealth();
            player.setHealth(maxHp);
            checkFalse(helper, player.causeFallDamage(12.0F, 1.0F, player.damageSources().fall()),
                    "T3-03 fall damage must be ignored while equipped");
            checkTrue(helper, player.getHealth() == maxHp,
                    "T3-03 health must stay unchanged while equipped");

            player.getAbilities().flying = true;
            player.inventoryMenu.quickMoveStack(player, 8); // slot 8 is FEET armor slot in InventoryMenu
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "T3-03 quickMoveStack unequip");
            player.invulnerableTime = 0;
            checkTrue(helper, player.causeFallDamage(12.0F, 1.0F, player.damageSources().fall()),
                    "T3-03 fall damage must resume after shift-click unequip");
            checkTrue(helper, player.getHealth() < maxHp, "Health must decrease after unequipped fall");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t3_08_dedicatedServerGameTestZeroClientClassBleed(GameTestHelper helper) {
        checkTrue(helper, FMLEnvironment.dist == Dist.DEDICATED_SERVER,
                "GameTestServer must run on Dist.DEDICATED_SERVER, got " + FMLEnvironment.dist);
        checkTrue(helper, ModList.get().isLoaded(MOD_ID), "Mod must load cleanly on dedicated server");
        requireSwordItem(helper);
        requireCreativeTab(helper);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t3_09_nonArmorItemFeetContractRuntime(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        checkFalse(helper, sword instanceof ArmorItem, "Sword must not extend ArmorItem");
        Equipable eq = Equipable.get(sword.getDefaultInstance());
        checkTrue(helper, eq != null && eq.getEquipmentSlot() == EquipmentSlot.FEET,
                "Sword must expose Equipable(FEET)");
        helper.succeed();
    }

    // =========================================================================
    // Tier 4 — Real-World End-User Scenarios (T4-01 .. T4-05)
    // =========================================================================

    @GameTest(template = "empty3x3x3")
    public static void t4_01_survivalCultivatorFullXianxiaFlightLifecycle(GameTestHelper helper) {
        Item sword = requireSwordItem(helper);
        CreativeModeTab tab = requireCreativeTab(helper);
        Optional<ItemStack> obtainedOpt = buildTabItems(helper, tab, false).stream()
                .filter(s -> s.is(sword))
                .findFirst();
        checkTrue(helper, obtainedOpt.isPresent(), "Sword missing from xianxia_tab");
        ItemStack obtained = obtainedOpt.get().copy();

        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            assertFlightRevoked(helper, player, "T4-01 initial");

            player.setItemInHand(InteractionHand.MAIN_HAND, obtained);
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "T4-01 equipped");

            player.getAbilities().flying = true;
            float maxHp = player.getMaxHealth();
            player.setHealth(maxHp);
            checkFalse(helper, player.causeFallDamage(15.0F, 1.0F, player.damageSources().fall()),
                    "T4-01 fall damage immune during sword flight");
            checkTrue(helper, player.getHealth() == maxHp,
                    "T4-01 health must stay unchanged during sword flight");

            // Unequip mid-air
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "T4-01 mid-air unequip");

            player.invulnerableTime = 0;
            checkTrue(helper, player.causeFallDamage(15.0F, 1.0F, player.damageSources().fall()),
                    "T4-01 fall damage restored after landing without sword");
            checkTrue(helper, player.getHealth() < maxHp, "Health must decrease after unequipped fall");
            AttributeInstance attr = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            checkTrue(helper, attr != null && attr.getModifiers().isEmpty(), "Zero residual modifiers");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t4_02_creativeBuilderEquipUnequipPreservation(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.CREATIVE, player -> {
            player.getAbilities().flying = true;
            Item sword = requireSwordItem(helper);

            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "T4-02 Creative flight preserved on equip");
            checkTrue(helper, Math.abs(player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) - 1.0D) < 1e-6,
                    "T4-02 sword flight attribute active while worn in Creative");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            checkTrue(helper, player.mayFly() && player.getAbilities().mayfly && player.getAbilities().flying,
                    "T4-02 Creative flight preserved on unequip");
            checkTrue(helper, player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                    "T4-02 sword modifier detached on unequip");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t4_03_adventureRealmTraversalAndSlotEnforcement(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.ADVENTURE, player -> {
            Item sword = requireSwordItem(helper);
            ItemStack swordStack = sword.getDefaultInstance();

            for (EquipmentSlot blocked : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                    EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
            }) {
                checkFalse(helper, swordStack.canEquip(blocked, player), "T4-03 blocked slot " + blocked);
            }
            checkTrue(helper, swordStack.canEquip(EquipmentSlot.FEET, player), "T4-03 FEET allowed");

            player.setItemInHand(InteractionHand.MAIN_HAND, swordStack);
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "T4-03 adventure flight");
            player.getAbilities().flying = true;

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEATHER_BOOTS));
            useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "T4-03 boots replacement");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t4_04_armorSwapAndRepeatedEquipEndurance(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.SURVIVAL, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
            player.setItemInHand(InteractionHand.MAIN_HAND, sword.getDefaultInstance());

            for (int i = 0; i < 5; i++) {
                // Hand has sword, FEET has boots -> swap sword onto FEET
                useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
                tickPlayer(player, 2);
                checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(sword), "Cycle " + i + ": FEET must hold sword");
                checkTrue(helper, player.getMainHandItem().is(Items.DIAMOND_BOOTS), "Cycle " + i + ": hand must hold boots");
                checkTrue(helper, player.getMainHandItem().getCount() == 1, "Cycle " + i + ": boots count must be 1");
                assertFlightGranted(helper, player, "T4-04 cycle " + i + " equip");
                player.getAbilities().flying = true;

                // Hand has boots, FEET has sword -> swap boots back onto FEET
                useItemInHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
                tickPlayer(player, 2);
                checkTrue(helper, player.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS), "Cycle " + i + ": FEET must hold boots");
                checkTrue(helper, player.getMainHandItem().is(sword), "Cycle " + i + ": hand must hold sword");
                checkTrue(helper, player.getMainHandItem().getCount() == 1, "Cycle " + i + ": sword count must be 1");
                assertFlightRevoked(helper, player, "T4-04 cycle " + i + " unequip");
            }

            AttributeInstance attr = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            checkTrue(helper, attr != null && attr.getModifiers().isEmpty(), "Zero residual modifiers after 5 swap cycles");
        });
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public static void t4_05_creativeToSurvivalModeTransitionMidFlight(GameTestHelper helper) {
        withMockServerPlayer(helper, GameType.CREATIVE, player -> {
            Item sword = requireSwordItem(helper);
            player.setItemSlot(EquipmentSlot.FEET, sword.getDefaultInstance());
            tickPlayer(player, 2);
            player.getAbilities().flying = true;

            player.setGameMode(GameType.SURVIVAL);
            tickPlayer(player, 2);
            assertFlightGranted(helper, player, "T4-05 retains flight after CREATIVE->SURVIVAL with sword");

            player.getAbilities().flying = true;
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickPlayer(player, 2);
            assertFlightRevoked(helper, player, "T4-05 revokes flight on mid-air unequip in SURVIVAL");
        });
        helper.succeed();
    }
}
