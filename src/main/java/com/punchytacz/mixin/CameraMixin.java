package com.punchytacz.mixin;

import com.punchytacz.TaczRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** 把 camera 骨骼的动画叠加到世界相机（TACZ 的 applyLevelCameraAnimation）；由 Settings.cameraAnim 控制 */
@Mixin(Camera.class)
public class CameraMixin {
    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, index = 1)
    private float punchytacz$yaw(float yaw) {
        return com.punchytacz.Settings.cameraAnim ? yaw + TaczRenderer.cameraEuler()[0] : yaw;
    }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, index = 2)
    private float punchytacz$pitch(float pitch) {
        return com.punchytacz.Settings.cameraAnim ? pitch + TaczRenderer.cameraEuler()[1] : pitch;
    }
}
