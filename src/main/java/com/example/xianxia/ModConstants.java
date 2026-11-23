package com.example.xianxia;

import net.minecraft.resources.ResourceLocation;

/**
 * Centralized placeholder identifiers and resource helpers for the Xianxia mod prototype.
 * Update {@link #MOD_ID} and {@link #MOD_NAME} when replacing placeholder metadata.
 */
public final class ModConstants {
    public static final String MOD_ID = "xianxia_placeholder";
    public static final String MOD_NAME = "Xianxia Cultivation Mod (Placeholder)";
    public static final String ITEM_QINGFENG_FLYING_SWORD = "qingfeng_flying_sword";
    public static final ResourceLocation FLIGHT_MODIFIER_ID = id("flying_sword_flight");

    private ModConstants() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
