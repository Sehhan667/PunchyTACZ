package com.punchytacz.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 取自 TACZ（GPL-3.0）的 BedrockPart；几何在解析期烘焙成 {@link QuadMesh}，渲染期只做矩阵变换 */
public class BedrockPart {
    public final String name;
    /** 解析期临时存放，烘焙后清空 */
    public final List<BedrockCube> cubes = new ArrayList<>();
    public final List<BedrockPart> children = new ArrayList<>();
    /** 烘焙后的静态几何（模型空间），null 表示该骨骼没有 cube */
    public QuadMesh mesh;
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

    /** 解析完成后把 cube 烘焙进静态网格，之后 cubes 不再参与渲染 */
    public void bake() {
        if (cubes.isEmpty()) {
            return;
        }
        QuadMesh baked = new QuadMesh();
        for (BedrockCube cube : cubes) {
            cube.bake(baked);
        }
        this.mesh = baked.isEmpty() ? null : baked;
        cubes.clear();
    }

    public void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
                       int color, Predicate<String> boneFilter) {
        if (!visible) {
            return;
        }
        // scale 为 0 在枪包里就是「隐藏这个骨骼及其子树」的写法（angel / devil 这类备用外观变体就这么切换）。
        // 直接整枝跳过：既不会画出多余的几何，也避免 poseStack.scale(0,0,0) 让法线矩阵退化成 NaN。
        if (xScale == 0.0F || yScale == 0.0F || zScale == 0.0F) {
            return;
        }
        if (mesh == null && children.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        translateAndRotateAndScale(poseStack);
        if (mesh != null && (boneFilter == null || boneFilter.test(name))) {
            emit(poseStack.last(), consumer, light, overlay, color);
        }
        for (BedrockPart part : children) {
            part.render(poseStack, consumer, light, overlay, color, boneFilter);
        }
        poseStack.popPose();
    }

    private void emit(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color) {
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        float[] d = mesh.data();
        int count = mesh.quads();
        // 朝向调试：只画某个方向的面，并把 UV 钉在一个纯白像素上，让顶点纯色不受贴图影响
        final int debugFace = com.punchytacz.Settings.FaceDebug.selectedFace();
        // SOLID 管线：顶点纯白 + UV 钉纯白像素，只验证几何/深度/剔除，排除贴图因素
        final boolean solid = com.punchytacz.Settings.pipeline == com.punchytacz.Settings.Pipeline.SOLID;
        for (int q = 0; q < count; q++) {
            int faceId = mesh.faceId(q);
            if (debugFace >= 0 && faceId != debugFace) {
                continue;
            }
            int quadColor = solid ? 0xFFFFFFFF
                    : debugFace >= 0 ? com.punchytacz.Settings.FaceDebug.colorOf(faceId) : color;
            final boolean pinWhite = debugFace >= 0 || solid;
            int base = q * QuadMesh.FLOATS_PER_QUAD;
            float nx = d[base + 3];
            float ny = d[base + 4];
            float nz = d[base + 5];
            // 法线必须保持单位长度：26.3 的 entity 管线带 PER_FACE_LIGHTING，长度会直接缩放亮度
            float tx = n.m00() * nx + n.m10() * ny + n.m20() * nz;
            float ty = n.m01() * nx + n.m11() * ny + n.m21() * nz;
            float tz = n.m02() * nx + n.m12() * ny + n.m22() * nz;
            float len2 = tx * tx + ty * ty + tz * tz;
            if (len2 > 1.0E-8F) {
                float inv = 1.0F / (float) Math.sqrt(len2);
                tx *= inv;
                ty *= inv;
                tz *= inv;
            }
            for (int v = 0; v < 4; v++) {
                int o = base + v * QuadMesh.FLOATS_PER_VERTEX;
                float x = d[o];
                float y = d[o + 1];
                float z = d[o + 2];
                consumer.addVertex(
                        m.m00() * x + m.m10() * y + m.m20() * z + m.m30(),
                        m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
                        m.m02() * x + m.m12() * y + m.m22() * z + m.m32(),
                        quadColor,
                        pinWhite ? com.punchytacz.Settings.FaceDebug.WHITE_U : d[o + 6],
                        pinWhite ? com.punchytacz.Settings.FaceDebug.WHITE_V : d[o + 7],
                        overlay, light, tx, ty, tz);
            }
        }
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

    public void addChild(BedrockPart part) {
        children.add(part);
        part.parent = this;
    }

    public BedrockPart getParent() {
        return parent;
    }
}
