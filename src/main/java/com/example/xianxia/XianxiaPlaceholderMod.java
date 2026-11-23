package com.example.xianxia;

import com.example.xianxia.registry.ModCreativeTabs;
import com.example.xianxia.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Main NeoForge 1.21.1 entrypoint for the Xianxia cultivation mod prototype.
 */
@Mod(ModConstants.MOD_ID)
public class XianxiaPlaceholderMod {
    public static final Logger LOGGER = LogUtils.getLogger();

    public XianxiaPlaceholderMod(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        LOGGER.info("Initialized {} ({})", ModConstants.MOD_NAME, ModConstants.MOD_ID);
    }
}
