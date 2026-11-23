package com.example.xianxia.event;

import com.example.xianxia.ModConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-side two-layer flight cleanup guard for the flying sword.
 * Ensures Survival and Adventure players lose active flight immediately upon unequipping
 * without affecting Creative/Spectator flight or other creative-flight sources.
 */
@EventBusSubscriber(modid = ModConstants.MOD_ID)
public final class FlyingSwordEvents {

    private FlyingSwordEvents() {
    }

    @SubscribeEvent
    public static void onLivingEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.FEET) {
            return;
        }
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        if (player.isCreative() || player.isSpectator()) {
            return;
        }
        if (hasCreativeFlightModifier(event.getFrom(), EquipmentSlot.FEET)
                && !hasCreativeFlightModifier(event.getTo(), EquipmentSlot.FEET)
                && !hasOtherCreativeFlightSource(player, event.getFrom(), EquipmentSlot.FEET)) {
            clearSurvivalFlight(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.isCreative() || player.isSpectator()) {
            return;
        }
        if (!player.mayFly() && player.getAbilities().flying) {
            clearSurvivalFlight(player);
        }
    }

    private static void clearSurvivalFlight(Player player) {
        player.getAbilities().mayfly = false;
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    private static boolean hasCreativeFlightModifier(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return false;
        }
        final boolean[] found = {false};
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(NeoForgeMod.CREATIVE_FLIGHT) && modifier.amount() > 0.0D) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static boolean hasOtherCreativeFlightSource(Player player, ItemStack removedStack, EquipmentSlot slot) {
        AttributeInstance instance = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (instance == null) {
            return false;
        }
        Set<ResourceLocation> removedIds = new HashSet<>();
        removedStack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(NeoForgeMod.CREATIVE_FLIGHT)) {
                removedIds.add(modifier.id());
            }
        });
        double base = instance.getBaseValue();
        double addValue = 0.0D;
        double addMultBase = 0.0D;
        double multTotal = 1.0D;
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (removedIds.contains(modifier.id())) {
                continue;
            }
            switch (modifier.operation()) {
                case ADD_VALUE -> addValue += modifier.amount();
                case ADD_MULTIPLIED_BASE -> addMultBase += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> multTotal *= (1.0D + modifier.amount());
            }
        }
        double effective = (base + addValue) * (1.0D + addMultBase) * multTotal;
        return effective > 0.0D;
    }
}
