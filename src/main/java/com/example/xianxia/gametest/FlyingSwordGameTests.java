package com.example.xianxia.gametest;

import com.example.xianxia.ModConstants;
import com.example.xianxia.registry.ModCreativeTabs;
import com.example.xianxia.registry.ModItems;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.List;
import java.util.UUID;

/**
 * NeoForge GameTest suite for Milestone 2 flying sword registration, slot rules, and flight lifecycle.
 */
@GameTestHolder(ModConstants.MOD_ID)
@PrefixGameTestTemplate(false)
public class FlyingSwordGameTests {

    @GameTest(template = "empty3x3x3")
    public void flyingSwordRegisteredAndInCreativeTab(GameTestHelper helper) {
        Item registered = BuiltInRegistries.ITEM
                .getOptional(ModConstants.id(ModConstants.ITEM_QINGFENG_FLYING_SWORD))
                .orElse(null);
        helper.assertTrue(
                registered == ModItems.QINGFENG_FLYING_SWORD.get(),
                "Qingfeng Flying Sword must be registered under xianxia_placeholder:qingfeng_flying_sword"
        );

        CreativeModeTab tab = ModCreativeTabs.XIANXIA_TAB.get();
        tab.buildContents(new CreativeModeTab.ItemDisplayParameters(
                helper.getLevel().enabledFeatures(),
                false,
                helper.getLevel().registryAccess()
        ));
        boolean presentInTab = tab.getDisplayItems().stream()
                .anyMatch(stack -> stack.is(ModItems.QINGFENG_FLYING_SWORD.get()));
        helper.assertTrue(presentInTab, "Qingfeng Flying Sword must appear in XIANXIA_TAB");

        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void flyingSwordFeetSlotRestrictionAndRightClickEquip(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.tick();

        ItemStack sword = new ItemStack(ModItems.QINGFENG_FLYING_SWORD.get());
        helper.assertTrue(sword.getMaxStackSize() == 1, "Flying sword max stack size must be 1");

        helper.assertTrue(sword.canEquip(EquipmentSlot.FEET, player), "Flying sword must allow FEET slot");
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.MAINHAND,
                EquipmentSlot.OFFHAND,
                EquipmentSlot.BODY
        }) {
            helper.assertFalse(sword.canEquip(slot, player), "Flying sword must reject slot " + slot);
        }

        player.setItemInHand(InteractionHand.MAIN_HAND, sword);
        InteractionResultHolder<ItemStack> result = sword.use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.getResult().consumesAction(), "Right-click use must equip flying sword");
        player.setItemInHand(InteractionHand.MAIN_HAND, result.getObject());
        player.tick();

        helper.assertTrue(
                player.getItemBySlot(EquipmentSlot.FEET).is(ModItems.QINGFENG_FLYING_SWORD.get()),
                "Right-click use must place flying sword in FEET slot"
        );
        helper.assertTrue(
                player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty(),
                "Survival right-click equip must empty main hand"
        );
        helper.assertTrue(player.mayFly(), "Survival player must gain flight after right-click equip");

        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void survivalEquipGrantsFlightAndUnequipClearsFlight(GameTestHelper helper) {
        assertNonCreativeEquipAndUnequipFlight(helper, GameType.SURVIVAL);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void adventureEquipGrantsFlightAndUnequipClearsFlight(GameTestHelper helper) {
        assertNonCreativeEquipAndUnequipFlight(helper, GameType.ADVENTURE);
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void creativeFlightPreservedAcrossEquipAndUnequip(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        GameType.CREATIVE.updatePlayerAbilities(player.getAbilities());
        player.tick();

        helper.assertTrue(player.mayFly(), "Creative player must have flight before equip");
        helper.assertTrue(player.getAbilities().mayfly, "Creative abilities.mayfly must be true before equip");
        player.getAbilities().flying = true;

        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.QINGFENG_FLYING_SWORD.get()));
        player.tick();
        helper.assertTrue(player.mayFly(), "Creative player must keep mayFly while sword equipped");
        helper.assertTrue(player.getAbilities().mayfly, "Creative abilities.mayfly must remain true while equipped");
        helper.assertTrue(player.getAbilities().flying, "Creative mid-air flight must remain active while equipped");

        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.tick();
        helper.assertTrue(player.mayFly(), "Creative player must keep mayFly after unequip");
        helper.assertTrue(player.getAbilities().mayfly, "Creative abilities.mayfly must remain true after unequip");
        helper.assertTrue(player.getAbilities().flying, "Creative mid-air flight must remain active after unequip");

        helper.succeed();
    }

    private static void assertNonCreativeEquipAndUnequipFlight(GameTestHelper helper, GameType gameType) {
        Player player = helper.makeMockPlayer(gameType);
        player.tick();

        helper.assertFalse(player.mayFly(), gameType + " player must not fly before equipping sword");
        helper.assertFalse(player.getAbilities().mayfly, gameType + " abilities.mayfly must start false");
        helper.assertFalse(player.getAbilities().flying, gameType + " abilities.flying must start false");
        helper.assertTrue(
                player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                gameType + " CREATIVE_FLIGHT attribute must start <= 0.0"
        );

        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(ModItems.QINGFENG_FLYING_SWORD.get()));
        player.tick();

        helper.assertTrue(
                player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) >= 1.0D,
                gameType + " CREATIVE_FLIGHT attribute must be >= 1.0 after equip"
        );
        helper.assertTrue(player.mayFly(), gameType + " player must gain mayFly() after equip");

        // Simulate active mid-air flight before unequipping.
        player.getAbilities().flying = true;

        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.tick();

        helper.assertTrue(
                player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                gameType + " CREATIVE_FLIGHT attribute must return <= 0.0 after unequip"
        );
        helper.assertFalse(player.mayFly(), gameType + " player must lose mayFly() after unequip");
        helper.assertFalse(player.getAbilities().mayfly, gameType + " abilities.mayfly must be false after unequip");
        helper.assertFalse(player.getAbilities().flying, gameType + " abilities.flying must be false after unequip");
    }

    @GameTest(template = "empty3x3x3")
    public void nonPlayerLivingEntityEquipDoesNotCrash(GameTestHelper helper) {
        Item swordItem = ModItems.QINGFENG_FLYING_SWORD.get();
        ArmorStand armorStand = helper.spawn(EntityType.ARMOR_STAND, 1, 1, 1);
        Zombie zombie = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, 1, 1, 1);
        try {
            for (LivingEntity entity : new LivingEntity[]{armorStand, zombie}) {
                ItemStack sword = new ItemStack(swordItem);
                helper.assertTrue(sword.canEquip(EquipmentSlot.FEET, entity),
                        entity.getType() + " must allow canEquip(FEET)");
                helper.assertFalse(sword.canEquip(EquipmentSlot.HEAD, entity),
                        entity.getType() + " must reject canEquip(HEAD)");

                entity.setItemSlot(EquipmentSlot.FEET, sword);
                entity.tick();
                helper.assertTrue(entity.getItemBySlot(EquipmentSlot.FEET).is(swordItem),
                        entity.getType() + " must hold sword in FEET");

                entity.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                entity.tick();
                helper.assertTrue(entity.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                        entity.getType() + " FEET slot must clear cleanly");
            }
        } finally {
            armorStand.discard();
            zombie.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void swordToSwordSwapAndBindingCurseAndStrippedAttributes(GameTestHelper helper) {
        Item swordItem = ModItems.QINGFENG_FLYING_SWORD.get();
        ServerPlayer player = createMockServerPlayer(helper, GameType.SURVIVAL);
        try {
            // 1. Identical sword right-click while flying with sword in FEET -> FAIL, flight intact
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
            tickServerPlayer(player, 2);
            player.getAbilities().flying = true;

            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(swordItem));
            InteractionResultHolder<ItemStack> dupResult = useHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(dupResult.getResult() == InteractionResult.FAIL,
                    "Right-clicking identical sword while wearing one must return FAIL");
            helper.assertTrue(player.mayFly() && player.getAbilities().flying,
                    "Identical sword FAIL must keep mid-air flight intact");
            helper.assertTrue(player.getMainHandItem().is(swordItem), "Main hand must retain identical sword");

            // 2. Differently-named sword right-click while flying -> swaps cleanly, flight intact
            ItemStack renamedSword = new ItemStack(swordItem);
            renamedSword.set(DataComponents.CUSTOM_NAME, Component.literal("Refined Qingfeng"));
            player.setItemInHand(InteractionHand.MAIN_HAND, renamedSword);
            InteractionResultHolder<ItemStack> swapResult = useHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(swapResult.getResult().consumesAction(),
                    "Right-clicking differently-named sword must swap");
            tickServerPlayer(player, 2);
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).has(DataComponents.CUSTOM_NAME),
                    "FEET must hold renamed sword after swap");
            helper.assertTrue(player.getMainHandItem().is(swordItem) && !player.getMainHandItem().has(DataComponents.CUSTOM_NAME),
                    "MAIN_HAND must receive original un-renamed sword");
            helper.assertTrue(player.mayFly() && player.getAbilities().flying,
                    "Sword-to-sword swap must preserve active mid-air flight");

            // 3. Offhand LEATHER_BOOTS right-click while sword in FEET -> swaps to OFF_HAND and revokes flight
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.LEATHER_BOOTS));
            InteractionResultHolder<ItemStack> offhandResult = useHand(helper.getLevel(), player, InteractionHand.OFF_HAND);
            helper.assertTrue(offhandResult.getResult().consumesAction(),
                    "Offhand LEATHER_BOOTS right-click must swap with sword in FEET");
            tickServerPlayer(player, 2);
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS),
                    "FEET must hold LEATHER_BOOTS after offhand swap");
            helper.assertTrue(player.getOffhandItem().is(swordItem),
                    "OFF_HAND must receive swapped flying sword");
            helper.assertFalse(player.mayFly(), "Survival mayFly must be false after offhand boots swap");
            helper.assertFalse(player.getAbilities().mayfly, "abilities.mayfly must be false after offhand swap");
            helper.assertFalse(player.getAbilities().flying, "abilities.flying must be false after offhand swap");

            // 4. Curse of Binding boots in FEET: Survival FAIL vs Creative swap
            Holder<Enchantment> bindingCurse = helper.getLevel().registryAccess()
                    .registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.BINDING_CURSE);
            ItemStack cursedBoots = new ItemStack(Items.LEATHER_BOOTS);
            cursedBoots.enchant(bindingCurse, 1);
            player.setItemSlot(EquipmentSlot.FEET, cursedBoots);
            player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(swordItem));
            tickServerPlayer(player, 1);

            InteractionResultHolder<ItemStack> cursedSurvival = useHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(cursedSurvival.getResult() == InteractionResult.FAIL,
                    "Survival sword use() against Curse of Binding boots must return FAIL");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).is(Items.LEATHER_BOOTS),
                    "Curse of Binding boots must remain in FEET in Survival");
            helper.assertFalse(player.mayFly(), "Survival player must not gain flight when blocked by Binding Curse");

            player.setGameMode(GameType.CREATIVE);
            tickServerPlayer(player, 1);
            InteractionResultHolder<ItemStack> cursedCreative = useHand(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(cursedCreative.getResult().consumesAction(),
                    "Creative sword use() must bypass Curse of Binding");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.FEET).is(swordItem),
                    "Creative swap must place flying sword into FEET");

            // 5. Sword with stripped DataComponents.ATTRIBUTE_MODIFIERS = EMPTY grants no flight
            player.setGameMode(GameType.SURVIVAL);
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickServerPlayer(player, 1);

            ItemStack strippedSword = new ItemStack(swordItem);
            strippedSword.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            player.setItemSlot(EquipmentSlot.FEET, strippedSword);
            tickServerPlayer(player, 2);
            helper.assertFalse(player.mayFly(), "Stripped attribute sword must not grant mayFly");
            helper.assertTrue(player.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                    "Stripped attribute sword must yield CREATIVE_FLIGHT <= 0.0");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickServerPlayer(player, 1);
            helper.assertFalse(player.mayFly(), "Unequipping stripped sword must leave mayFly false");
            helper.assertFalse(player.getAbilities().flying, "Unequipping stripped sword must leave flying false");
        } finally {
            cleanupMockServerPlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void nonFeetUnequipAndOtherFlightSourceAndSuppressionAndTickFallback(GameTestHelper helper) {
        Item swordItem = ModItems.QINGFENG_FLYING_SWORD.get();
        ResourceLocation altFlightId = ModConstants.id("tier5_alt_flight");
        ResourceLocation suppressFlightId = ModConstants.id("tier5_suppress_flight");

        ServerPlayer player = createMockServerPlayer(helper, GameType.SURVIVAL);
        try {
            // 1. Clearing MAINHAND sword while wearing sword in FEET preserves flight
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(swordItem));
            tickServerPlayer(player, 2);
            player.getAbilities().flying = true;

            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            tickServerPlayer(player, 1);
            helper.assertTrue(player.mayFly() && player.getAbilities().flying,
                    "Clearing MAINHAND sword must not disrupt FEET sword flight");

            // 2. External positive CREATIVE_FLIGHT source preserves flight across sword unequip
            AttributeInstance flightAttr = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            helper.assertTrue(flightAttr != null, "Player must have CREATIVE_FLIGHT attribute");
            flightAttr.addTransientModifier(new AttributeModifier(altFlightId, 1.0D, AttributeModifier.Operation.ADD_VALUE));
            tickServerPlayer(player, 1);
            player.getAbilities().flying = true;

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickServerPlayer(player, 2);
            helper.assertTrue(player.mayFly(), "Other positive CREATIVE_FLIGHT source must preserve mayFly()");
            helper.assertTrue(player.getAbilities().flying, "Other positive CREATIVE_FLIGHT source must preserve flying");

            // 3. Removing external modifier directly (no equipment change) -> player.doTick() triggers Player.tick() -> onPlayerTickPost fallback
            flightAttr.removeModifier(altFlightId);
            player.doTick();
            helper.assertFalse(player.mayFly(), "mayFly must become false after removing alt flight source");
            helper.assertFalse(player.getAbilities().mayfly, "onPlayerTickPost must clear abilities.mayfly");
            helper.assertFalse(player.getAbilities().flying, "onPlayerTickPost must clear abilities.flying");

            // 4. BooleanAttribute suppression (+1.0 ADD_VALUE + -1.0 ADD_MULTIPLIED_TOTAL) -> immediate clear on LivingEquipmentChangeEvent
            player.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
            tickServerPlayer(player, 1);
            flightAttr.addTransientModifier(new AttributeModifier(altFlightId, 1.0D, AttributeModifier.Operation.ADD_VALUE));
            flightAttr.addTransientModifier(new AttributeModifier(suppressFlightId, -1.0D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            helper.assertFalse(player.mayFly(), "Suppressed CREATIVE_FLIGHT must make mayFly() false");

            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            NeoForge.EVENT_BUS.post(new LivingEquipmentChangeEvent(
                    player, EquipmentSlot.FEET, new ItemStack(swordItem), ItemStack.EMPTY));
            helper.assertFalse(player.getAbilities().mayfly,
                    "hasOtherCreativeFlightSource must detect -1.0 ADD_MULTIPLIED_TOTAL suppression and clear mayfly immediately");
            helper.assertFalse(player.getAbilities().flying,
                    "hasOtherCreativeFlightSource must detect -1.0 ADD_MULTIPLIED_TOTAL suppression and clear flying immediately");

            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            flightAttr.removeModifier(altFlightId);
            flightAttr.removeModifier(suppressFlightId);
            tickServerPlayer(player, 1);
        } finally {
            cleanupMockServerPlayer(player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty3x3x3")
    public void multiModeTransitionsAndDeathRespawnAndFakePlayer(GameTestHelper helper) {
        Item swordItem = ModItems.QINGFENG_FLYING_SWORD.get();

        // 1. Creative use() retains copy in hand + SURVIVAL -> CREATIVE(unequip) -> SURVIVAL + SURVIVAL -> SPECTATOR -> SURVIVAL
        ServerPlayer modePlayer = createMockServerPlayer(helper, GameType.CREATIVE);
        try {
            modePlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(swordItem));
            InteractionResultHolder<ItemStack> creativeUse = useHand(helper.getLevel(), modePlayer, InteractionHand.MAIN_HAND);
            helper.assertTrue(creativeUse.getResult().consumesAction(), "Creative use() must succeed");
            helper.assertTrue(modePlayer.getItemBySlot(EquipmentSlot.FEET).is(swordItem),
                    "Creative use() must equip sword in FEET");
            helper.assertTrue(modePlayer.getMainHandItem().is(swordItem),
                    "Creative use() must retain a copy of sword in hand");

            // SURVIVAL (equipped + flying) -> CREATIVE (unequip) -> SURVIVAL
            modePlayer.setGameMode(GameType.SURVIVAL);
            tickServerPlayer(modePlayer, 2);
            modePlayer.getAbilities().flying = true;

            modePlayer.setGameMode(GameType.CREATIVE);
            modePlayer.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickServerPlayer(modePlayer, 1);

            modePlayer.setGameMode(GameType.SURVIVAL);
            tickServerPlayer(modePlayer, 2);
            helper.assertFalse(modePlayer.mayFly(), "SURVIVAL->CREATIVE(unequip)->SURVIVAL must have mayFly == false");
            helper.assertFalse(modePlayer.getAbilities().mayfly, "abilities.mayfly must be false");
            helper.assertFalse(modePlayer.getAbilities().flying, "abilities.flying must be false");

            // SURVIVAL (equipped + flying) -> SPECTATOR -> SURVIVAL
            modePlayer.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
            tickServerPlayer(modePlayer, 2);
            modePlayer.getAbilities().flying = true;

            modePlayer.setGameMode(GameType.SPECTATOR);
            tickServerPlayer(modePlayer, 2);
            helper.assertTrue(modePlayer.mayFly(), "SPECTATOR must retain mayFly");

            modePlayer.setGameMode(GameType.SURVIVAL);
            tickServerPlayer(modePlayer, 2);
            helper.assertTrue(modePlayer.mayFly(), "SURVIVAL->SPECTATOR->SURVIVAL with sword must retain mayFly");

            modePlayer.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            tickServerPlayer(modePlayer, 2);
            helper.assertFalse(modePlayer.mayFly(), "Unequipping after SPECTATOR->SURVIVAL must revoke mayFly");
            helper.assertFalse(modePlayer.getAbilities().flying, "Unequipping after SPECTATOR->SURVIVAL must clear flying");
        } finally {
            cleanupMockServerPlayer(modePlayer);
        }

        // 2. ServerPlayer death & respawn with RULE_KEEPINVENTORY = false and true
        ServerLevel level = helper.getLevel();
        GameRules.BooleanValue keepInvRule = level.getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean originalKeepInv = keepInvRule.get();
        try {
            // 2a. keepInventory = false
            keepInvRule.set(false, level.getServer());
            ServerPlayer respawnNoKeep = createMockServerPlayer(helper, GameType.SURVIVAL);
            try {
                respawnNoKeep.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
                tickServerPlayer(respawnNoKeep, 2);
                respawnNoKeep.getAbilities().flying = true;

                respawnNoKeep.kill();
                List<ItemEntity> dropped = level.getEntitiesOfClass(
                        ItemEntity.class, new AABB(respawnNoKeep.blockPosition()).inflate(4.0D));
                for (ItemEntity itemEntity : dropped) {
                    itemEntity.discard();
                }

                respawnNoKeep = level.getServer().getPlayerList().respawn(respawnNoKeep, false, Entity.RemovalReason.KILLED);
                respawnNoKeep.connection.player = respawnNoKeep;
                tickServerPlayer(respawnNoKeep, 2);

                helper.assertTrue(respawnNoKeep.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                        "Respawn with keepInventory=false must have empty FEET");
                helper.assertFalse(respawnNoKeep.mayFly(), "Respawn with keepInventory=false must have mayFly == false");
                helper.assertFalse(respawnNoKeep.getAbilities().mayfly, "abilities.mayfly must be false");
                helper.assertFalse(respawnNoKeep.getAbilities().flying, "abilities.flying must be false");
                helper.assertTrue(respawnNoKeep.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) <= 0.0D,
                        "Respawn with keepInventory=false must have zero CREATIVE_FLIGHT");
            } finally {
                cleanupMockServerPlayer(respawnNoKeep);
            }

            // 2b. keepInventory = true
            keepInvRule.set(true, level.getServer());
            ServerPlayer respawnKeep = createMockServerPlayer(helper, GameType.SURVIVAL);
            try {
                respawnKeep.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
                tickServerPlayer(respawnKeep, 2);

                respawnKeep.kill();
                respawnKeep = level.getServer().getPlayerList().respawn(respawnKeep, false, Entity.RemovalReason.KILLED);
                respawnKeep.connection.player = respawnKeep;
                tickServerPlayer(respawnKeep, 2);

                helper.assertTrue(respawnKeep.getItemBySlot(EquipmentSlot.FEET).is(swordItem),
                        "Respawn with keepInventory=true must keep sword in FEET");
                helper.assertTrue(respawnKeep.getAttributeValue(NeoForgeMod.CREATIVE_FLIGHT) >= 1.0D,
                        "Respawn with keepInventory=true must restore CREATIVE_FLIGHT");
                helper.assertTrue(respawnKeep.mayFly(), "Respawn with keepInventory=true must restore mayFly()");

                respawnKeep.getAbilities().flying = true;
                respawnKeep.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
                tickServerPlayer(respawnKeep, 2);
                helper.assertFalse(respawnKeep.mayFly(), "Unequipping after keepInventory respawn must revoke mayFly");
                helper.assertFalse(respawnKeep.getAbilities().mayfly, "abilities.mayfly must be false");
                helper.assertFalse(respawnKeep.getAbilities().flying, "abilities.flying must be false");
            } finally {
                cleanupMockServerPlayer(respawnKeep);
            }
        } finally {
            keepInvRule.set(originalKeepInv, level.getServer());
        }

        // 3. FakePlayer equip/unequip/tick safety
        FakePlayer fakePlayer = FakePlayerFactory.getMinecraft(level);
        try {
            fakePlayer.setItemSlot(EquipmentSlot.FEET, new ItemStack(swordItem));
            fakePlayer.tick();
            helper.assertTrue(fakePlayer.getItemBySlot(EquipmentSlot.FEET).is(swordItem),
                    "FakePlayer must hold sword in FEET");
        } finally {
            fakePlayer.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            fakePlayer.tick();
        }
        helper.assertTrue(fakePlayer.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                "FakePlayer FEET must be empty after cleanup");

        helper.succeed();
    }

    private static ServerPlayer createMockServerPlayer(GameTestHelper helper, GameType gameType) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "t5_" + suffix);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        try {
            player.setGameMode(gameType);
            tickServerPlayer(player, 2);
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
                player.connection.disconnect(Component.literal("tier5 cleanup"));
            }
        } finally {
            MinecraftServer server = player.serverLevel().getServer();
            if (server.getPlayerList().getPlayer(player.getUUID()) == player) {
                server.getPlayerList().remove(player);
            }
        }
    }

    private static void tickServerPlayer(ServerPlayer player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.tick();
            player.doTick();
        }
    }

    private static InteractionResultHolder<ItemStack> useHand(ServerLevel level, Player player, InteractionHand hand) {
        ItemStack inHand = player.getItemInHand(hand);
        InteractionResultHolder<ItemStack> result = inHand.use(level, player, hand);
        ItemStack returned = result.getObject();
        if (returned != inHand) {
            player.setItemInHand(hand, returned);
        }
        return result;
    }
}
