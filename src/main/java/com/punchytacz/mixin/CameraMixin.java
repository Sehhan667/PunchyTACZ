package com.punchytacz.mixin;

import com.punchytacz.TaczRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 把 camera 骨骼的动画叠加到世界相机（TACZ 的 applyLevelCameraAnimation） */
@Mixin(Camera.class)
public class CameraMixin {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, index = 1)
    private float punchytacz$yaw(float yaw) {
        return yaw + TaczRenderer.cameraEuler()[0];
    }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, index = 2)
    private float punchytacz$pitch(float pitch) {
        return pitch + TaczRenderer.cameraEuler()[1];
    }
}
