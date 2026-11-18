package com.punchytacz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.punchytacz.TaczRenderer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.3 第一人称手部渲染接入。
 * <p>
 * Punchy 接管时会把整个 {@code submitHandsWithItems} 取消，原版后续的
 * {@code submitArmWithItem} 不会执行（日志已证实），所以渲染放在这里的 HEAD。
 * 该 PoseStack 是相机空间，且尚未叠加 {@code *0.1} 的视角旋转。
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class FirstPersonHandsMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void punchytacz$renderFrame(float partialTicks, PoseStack poseStack, SubmitNodeCollector sink,
                                        PlayerRenderState playerState,
                                        FirstPersonHandsAndItemsRenderState handState, CallbackInfo ci) {
        int light = 0;
        if (playerState != null && playerState.avatarRenderState != null) {
            light = playerState.avatarRenderState.lightCoords;
        }
        TaczRenderer.renderFrame(poseStack, sink, light, partialTicks);
    }

    /** 若能走到原版手部渲染（Punchy 未取消），已由状态机画过就取消掉，避免重复。 */
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void punchytacz$cancelVanillaHand(PlayerRenderState playerState,
                                             FirstPersonHandsAndItemsRenderState handState,
                                             float partialTicks, float xRot, InteractionHand hand,
                                             float swingProgress, ItemStack stack, float equipProgress,
                                             PoseStack poseStack, SubmitNodeCollector sink, int light,
                                             CallbackInfo ci) {
        if (TaczRenderer.shouldCancelVanillaHand()) {
            ci.cancel();
        }
    }
}
