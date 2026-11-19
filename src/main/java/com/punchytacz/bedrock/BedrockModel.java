package com.punchytacz.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 基岩版几何体。坐标转换直接照搬 TACZ 的 BedrockModel（GPL-3.0）：
 * x/z 用相对枢轴，y 取反；顶层骨骼 y = 24 - pivotY。
 */
public class BedrockModel {
    public final Map<String, BedrockPart> parts = new LinkedHashMap<>();
    public final List<BedrockPart> roots = new ArrayList<>();
    public final List<BedrockPart> idleViewPath = new ArrayList<>();
    /** camera 骨骼的动画旋转（TACZ 的 CameraAnimationObject） */
    public final org.joml.Quaternionf cameraRotation = new org.joml.Quaternionf();
    public float textureWidth = 64;
    public float textureHeight = 64;

    public static BedrockModel parse(JsonObject json) {
        JsonArray geometries = json.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            throw new IllegalArgumentException("缺少 minecraft:geometry");
        }
        JsonObject geo = geometries.get(0).getAsJsonObject();
        BedrockModel model = new BedrockModel();
        JsonObject desc = geo.has("description") ? geo.getAsJsonObject("description") : new JsonObject();
        model.textureWidth = desc.has("texture_width") ? desc.get("texture_width").getAsFloat() : 64F;
        model.textureHeight = desc.has("texture_height") ? desc.get("texture_height").getAsFloat() : 64F;

        JsonArray bonesJson = geo.getAsJsonArray("bones");
        if (bonesJson == null) {
            return model;
        }
        Map<String, JsonObject> raw = new LinkedHashMap<>();
        for (JsonElement e : bonesJson) {
            JsonObject obj = e.getAsJsonObject();
            raw.put(obj.get("name").getAsString(), obj);
        }
        // 1. 建立骨骼
        for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
            model.parts.put(entry.getKey(), new BedrockPart(entry.getKey()));
        }
        // 2. 枢轴 / 绑定旋转 / 父子
        for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
            JsonObject obj = entry.getValue();
            BedrockPart part = model.parts.get(entry.getKey());
            float[] pivot = vec3(obj.get("pivot"), 0, 0, 0);
            JsonElement parentName = obj.get("parent");
            if (parentName != null && model.parts.containsKey(parentName.getAsString())) {
                BedrockPart parent = model.parts.get(parentName.getAsString());
                float[] parentPivot = vec3(raw.get(parentName.getAsString()).get("pivot"), 0, 0, 0);
                part.setPos(pivot[0] - parentPivot[0], parentPivot[1] - pivot[1], pivot[2] - parentPivot[2]);
                parent.addChild(part);
            } else {
                part.setPos(pivot[0], 24F - pivot[1], pivot[2]);
                model.roots.add(part);
            }
            part.mirror = obj.has("mirror") && obj.get("mirror").getAsBoolean();
            float[] rot = vec3(obj.get("rotation"), 0, 0, 0);
            part.setRotationAngle(rad(rot[0]), rad(rot[1]), rad(rot[2]));
        }
        // 3. cube（origin 依赖绝对 pivot）
        for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
            BedrockPart part = model.parts.get(entry.getKey());
            JsonArray cubes = entry.getValue().getAsJsonArray("cubes");
            if (cubes == null) {
                continue;
            }
            float[] pivot = vec3(entry.getValue().get("pivot"), 0, 0, 0);
            for (JsonElement ce : cubes) {
                JsonObject c = ce.getAsJsonObject();
                float[] origin = vec3(c.get("origin"), 0, 0, 0);
                float[] size = vec3(c.get("size"), 0, 0, 0);
                float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : 0F;
                boolean mirror = c.has("mirror") && c.get("mirror").getAsBoolean();
                JsonElement cubePivotJson = c.get("pivot");
                JsonElement cubeRotationJson = c.get("rotation");
                boolean hasCubeTransform = cubePivotJson != null && cubeRotationJson != null;

                float[] cubePivot = hasCubeTransform ? vec3(cubePivotJson, 0, 0, 0) : null;
                float baseX = hasCubeTransform ? cubePivot[0] : pivot[0];
                float baseY = hasCubeTransform ? cubePivot[1] : pivot[1];
                float baseZ = hasCubeTransform ? cubePivot[2] : pivot[2];
                float x = origin[0] - baseX;
                float y = baseY - origin[1] - size[1];
                float z = origin[2] - baseZ;

                Matrix4f transform = null;
                if (hasCubeTransform) {
                    float[] cubeRot = vec3(cubeRotationJson, 0, 0, 0);
                    transform = new Matrix4f()
                            .translation(cubePivot[0] - pivot[0], pivot[1] - cubePivot[1], cubePivot[2] - pivot[2])
                            .rotate(Axis.ZP.rotation(rad(cubeRot[2])))
                            .rotate(Axis.YP.rotation(rad(cubeRot[1])))
                            .rotate(Axis.XP.rotation(rad(cubeRot[0])));
                }

                JsonElement uvElement = c.get("uv");
                BedrockCube cube;
                if (uvElement != null && uvElement.isJsonObject()) {
                    FaceUVsItem faces = new FaceUVsItem();
                    for (Map.Entry<String, JsonElement> faceEntry : uvElement.getAsJsonObject().entrySet()) {
                        JsonObject face = faceEntry.getValue().getAsJsonObject();
                        faces.set(faceEntry.getKey(), new FaceItem(vec2(face.get("uv"), 0, 0), vec2(face.get("uv_size"), 0, 0)));
                    }
                    cube = new BedrockCubePerFace(x, y, z, size[0], size[1], size[2], inflate,
                            model.textureWidth, model.textureHeight, faces, transform);
                } else {
                    float[] uv = vec2(uvElement, 0, 0);
                    cube = new BedrockCubeBox(uv[0], uv[1], x, y, z, size[0], size[1], size[2],
                            inflate, mirror, model.textureWidth, model.textureHeight, transform);
                }
                part.cubes.add(cube);
            }
        }
        // 4. 一次性烘焙：把 cube 多边形展平成静态顶点数组，之后每帧只做矩阵变换
        for (BedrockPart part : model.parts.values()) {
            part.bake();
        }
        // 5. idle_view 路径（从根到 idle_view）
        BedrockPart idleView = model.parts.get("idle_view");
        if (idleView != null) {
            List<BedrockPart> path = new ArrayList<>();
            for (BedrockPart part = idleView; part != null; part = part.getParent()) {
                path.add(0, part);
            }
            model.idleViewPath.addAll(path);
        }
        return model;
    }

    public void resetAnimation() {
        cameraRotation.identity();
        for (BedrockPart part : parts.values()) {
            part.resetAnimation();
        }
    }

    /** 渲染结束后清除骨骼上的动画变换（对应 TACZ cleanAnimationTransform） */
    public void cleanAnimationTransform() {
        for (BedrockPart part : parts.values()) {
            part.resetAnimation();
        }
    }

    /** @param color 打包好的 ARGB 顶点颜色（通常是 0xFFFFFFFF） */
    public void render(PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
                       int color, Predicate<String> boneFilter) {
        for (BedrockPart part : roots) {
            part.render(poseStack, consumer, light, overlay, color, boneFilter);
        }
    }

    /** 已烘焙的四边形总数，用于日志确认烘焙结果 */
    public int bakedQuads() {
        int total = 0;
        for (BedrockPart part : parts.values()) {
            total += part.mesh == null ? 0 : part.mesh.quads();
        }
        return total;
    }

    private static float rad(float deg) {
        return (float) Math.toRadians(deg);
    }

    private static float[] vec3(JsonElement e, float dx, float dy, float dz) {
        if (e == null || !e.isJsonArray()) {
            return new float[]{dx, dy, dz};
        }
        JsonArray arr = e.getAsJsonArray();
        return new float[]{
                arr.size() > 0 ? arr.get(0).getAsFloat() : dx,
                arr.size() > 1 ? arr.get(1).getAsFloat() : dy,
                arr.size() > 2 ? arr.get(2).getAsFloat() : dz,
        };
    }

    private static float[] vec2(JsonElement e, float dx, float dy) {
        if (e == null || !e.isJsonArray()) {
            return new float[]{dx, dy};
        }
        JsonArray arr = e.getAsJsonArray();
        return new float[]{
                arr.size() > 0 ? arr.get(0).getAsFloat() : dx,
                arr.size() > 1 ? arr.get(1).getAsFloat() : dy,
        };
    }
}
