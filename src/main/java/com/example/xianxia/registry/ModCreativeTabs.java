package com.example.xianxia.registry;

import com.example.xianxia.ModConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative mode tab registry for the Xianxia mod prototype.
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModConstants.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> XIANXIA_TAB = CREATIVE_MODE_TABS.register(
            "xianxia_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + ModConstants.MOD_ID + ".xianxia_tab"))
                    .icon(() -> new ItemStack(ModItems.QINGFENG_FLYING_SWORD.get()))
                    .displayItems((parameters, output) -> output.accept(ModItems.QINGFENG_FLYING_SWORD.get()))
                    .build()
    );

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
