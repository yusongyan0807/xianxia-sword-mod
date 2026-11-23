package com.example.xianxia.registry;

import com.example.xianxia.ModConstants;
import com.example.xianxia.item.FlyingSwordItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Item registry for the Xianxia mod prototype.
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModConstants.MOD_ID);

    public static final DeferredItem<FlyingSwordItem> QINGFENG_FLYING_SWORD = ITEMS.registerItem(
            ModConstants.ITEM_QINGFENG_FLYING_SWORD,
            FlyingSwordItem::new,
            new Item.Properties()
                    .stacksTo(1)
                    .rarity(Rarity.RARE)
                    .attributes(FlyingSwordItem.createAttributes())
    );

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
