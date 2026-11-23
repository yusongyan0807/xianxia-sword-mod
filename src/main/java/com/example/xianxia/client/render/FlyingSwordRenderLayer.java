package com.example.xianxia.client.render;

import com.example.xianxia.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Renders the Qingfeng Flying Sword horizontally beneath the player's soles
 * whenever equipped in {@link EquipmentSlot#FEET}.
 */
public class FlyingSwordRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public FlyingSwordRenderLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
        super(renderer);
    }

    @Override
    public void render(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            AbstractClientPlayer player,
            float limbSwing,
            float limbSwingAmount,
            float partialTick,
            float ageInTicks,
            float netHeadYaw,
            float headPitch
    ) {
        ItemStack feetStack = player.getItemBySlot(EquipmentSlot.FEET);
        if (!feetStack.is(ModItems.QINGFENG_FLYING_SWORD.get())
                || player.isInvisible()
                || player.isPassenger()
                || player.isSleeping()) {
            return;
        }

        poseStack.pushPose();
        try {
            float yOffset = 1.59F;
            float zOffset = 0.07F;
            if (player.isCrouching()) {
                yOffset += 0.015F;
                zOffset += 0.25F;
            }

            float pitchTilt = 0.0F;
            float rollTilt = 0.0F;
            if (player.getAbilities().flying) {
                yOffset += Mth.sin(ageInTicks * 0.12F) * 0.025F;
                pitchTilt = Mth.clamp((float) player.getDeltaMovement().y * 8.0F, -6.0F, 6.0F);
                rollTilt = Mth.sin(ageInTicks * 0.08F) * 2.0F;
            }

            poseStack.translate(0.0F, yOffset, zOffset);
            if (rollTilt != 0.0F) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(rollTilt));
            }
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F - pitchTilt));
            poseStack.scale(1.35F, 1.35F, 1.35F);

            Minecraft.getInstance().getItemRenderer().renderStatic(
                    feetStack,
                    ItemDisplayContext.FIXED,
                    packedLight,
                    OverlayTexture.NO_OVERLAY,
                    poseStack,
                    bufferSource,
                    player.level(),
                    player.getId()
            );
        } finally {
            poseStack.popPose();
        }
    }
}
