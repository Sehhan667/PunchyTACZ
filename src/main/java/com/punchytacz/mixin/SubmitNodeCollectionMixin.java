package com.punchytacz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.punchytacz.diag.SubmitProbe;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 观察每一次 submitCustomGeometry：谁提交的、用什么 RenderType、顶点长什么样 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void punchytacz$probe(PoseStack stack, RenderType renderType,
                                  SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo ci) {
        SubmitProbe.probe(stack, renderType, renderer);
    }
}
