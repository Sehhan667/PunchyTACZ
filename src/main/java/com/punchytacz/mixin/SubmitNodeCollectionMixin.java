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

/**
 * 只挂在 {@link SubmitNodeCollection} 上。
 * <p>
 * {@code SubmitNodeStorage.submitCustomGeometry} 只是转发到 {@code order(0)} 的同一个方法，
 * 两个类都挂会导致每次提交被数两遍（上一版探针就吃了这个亏）。
 */
@Mixin(SubmitNodeCollection.class)
public class SubmitNodeCollectionMixin {
    @Inject(method = "submitCustomGeometry", at = @At("HEAD"))
    private void punchytacz$count(PoseStack stack, RenderType renderType,
                                  SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo ci) {
        SubmitProbe.submit(stack, renderType, renderer);
    }
}
