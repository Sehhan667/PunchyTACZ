package com.punchytacz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.punchytacz.diag.SubmitProbe;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 另一条提交实现（Punchy 用的很可能就是这条），同样只观察公开接口 */
@Mixin(SubmitNodeStorage.class)
public class SubmitNodeStorageMixin {
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void punchytacz$probe(PoseStack stack, RenderType renderType,
                                  SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo ci) {
        SubmitProbe.probe(stack, renderType, renderer);
    }
}
