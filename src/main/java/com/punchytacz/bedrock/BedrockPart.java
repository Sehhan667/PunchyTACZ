package com.punchytacz.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 取自 TACZ（GPL-3.0）的 BedrockPart，去掉 bufferSource/functional renderer 依赖 */
public class BedrockPart {
    public final String name;
    public final List<BedrockCube> cubes = new ArrayList<>();
    public final List<BedrockPart> children = new ArrayList<>();
    /** 相对父骨骼的枢轴（模型单位 1/16 格） */
    public float x;
    public float y;
    public float z;
    /** 绑定旋转（弧度） */
    public float xRot;
    public float yRot;
    public float zRot;
    /** 动画位移（格，非 1/16） */
    public float offsetX;
    public float offsetY;
    public float offsetZ;
    public boolean visible = true;
    public boolean illuminated = false;
    public boolean mirror;
    /** 动画旋转，叠加在绑定旋转之后 */
    public final Quaternionf additionalQuaternion = new Quaternionf(0, 0, 0, 1);
    public float xScale = 1;
    public float yScale = 1;
    public float zScale = 1;
    protected BedrockPart parent;

    public BedrockPart(String name) {
        this.name = name;
    }

    /** 在该骨骼的局部变换上绘制额外内容（TACZ 用它在手骨上画玩家真实手臂） */
    public interface HandHook {
        void render(PoseStack poseStack);
    }

    public HandHook handHook;

    public void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void setRotationAngle(float xRot, float yRot, float zRot) {
        this.xRot = xRot;
        this.yRot = yRot;
        this.zRot = zRot;
    }

    public void resetAnimation() {
        offsetX = offsetY = offsetZ = 0;
        additionalQuaternion.set(0, 0, 0, 1);
        xScale = yScale = zScale = 1;
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
                       float red, float green, float blue, float alpha, Predicate<String> boneFilter) {
        if (!visible) {
            return;
        }
        // scale 为 0 在枪包里就是「隐藏这个骨骼及其子树」的写法（angel / devil 这类备用外观变体就这么切换）。
        // 直接整枝跳过：既不会画出多余的几何，也避免 poseStack.scale(0,0,0) 让法线矩阵退化成 NaN。
        if (xScale == 0.0F || yScale == 0.0F || zScale == 0.0F) {
            return;
        }
        if (cubes.isEmpty() && children.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        translateAndRotateAndScale(poseStack);
        if (handHook != null) {
            poseStack.pushPose();
            // TACZ RightHandRender/LeftHandRender 在手臂前额外翻转一次 Z
            poseStack.rotate(Axis.ZP.rotationDegrees(180F));
            handHook.render(poseStack);
            poseStack.popPose();
        }
        if (boneFilter == null || boneFilter.test(name)) {
            compile(poseStack.last(), consumer, light, overlay, red, green, blue, alpha);
        }
        for (BedrockPart part : children) {
            part.render(poseStack, consumer, light, overlay, red, green, blue, alpha, boneFilter);
        }
        poseStack.popPose();
    }

    public void translateAndRotateAndScale(PoseStack poseStack) {
        poseStack.translate(offsetX, offsetY, offsetZ);
        poseStack.translate(x / 16.0F, y / 16.0F, z / 16.0F);
        if (zRot != 0.0F) {
            poseStack.rotate(Axis.ZP.rotation(zRot));
        }
        if (yRot != 0.0F) {
            poseStack.rotate(Axis.YP.rotation(yRot));
        }
        if (xRot != 0.0F) {
            poseStack.rotate(Axis.XP.rotation(xRot));
        }
        poseStack.rotate(additionalQuaternion);
        poseStack.scale(xScale, yScale, zScale);
    }

    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
                        float red, float green, float blue, float alpha) {
        for (BedrockCube cube : cubes) {
            cube.compile(pose, consumer, light, overlay, red, green, blue, alpha);
        }
    }

    public void addChild(BedrockPart part) {
        children.add(part);
        part.parent = this;
    }

    public BedrockPart getParent() {
        return parent;
    }
}
