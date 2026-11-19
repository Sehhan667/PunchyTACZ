package com.punchytacz.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把基岩版动画文件解析成 {@link ObjectAnim} 原型列表。
 * 数值处理逐字移植 TACZ Animations.writeBedrockTranslation/Rotation/Scale：
 * position ×1/16，rotation 度→弧度，scale 原样；pre/post 各占三位。
 */
public final class BedrockAnimationLoader {
    private BedrockAnimationLoader() {
    }

    public static List<ObjectAnim> parse(JsonObject root) {
        List<ObjectAnim> result = new ArrayList<>();
        JsonObject animations = root.has("animations") && root.get("animations").isJsonObject()
                ? root.getAsJsonObject("animations") : null;
        if (animations == null) {
            return result;
        }
        for (Map.Entry<String, JsonElement> animationEntry : animations.entrySet()) {
            if (!animationEntry.getValue().isJsonObject()) {
                continue;
            }
            ObjectAnim animation = new ObjectAnim(animationEntry.getKey());
            JsonObject clip = animationEntry.getValue().getAsJsonObject();
            JsonObject bones = clip.has("bones") && clip.get("bones").isJsonObject()
                    ? clip.getAsJsonObject("bones") : null;
            if (bones != null) {
                for (Map.Entry<String, JsonElement> boneEntry : bones.entrySet()) {
                    if (!boneEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject bone = boneEntry.getValue().getAsJsonObject();
                    String node = boneEntry.getKey();
                    addIfPresent(animation, node, AnimChannelType.TRANSLATION, bone.get("position"));
                    addIfPresent(animation, node, AnimChannelType.ROTATION, bone.get("rotation"));
                    addIfPresent(animation, node, AnimChannelType.SCALE, bone.get("scale"));
                }
            }
            addSoundEffects(animation, clip.get("sound_effects"));
            result.add(animation);
        }
        return result;
    }

    /**
     * 解析 {@code sound_effects}：{@code "0.0": {"effect": "wt:..."}}（也兼容字符串简写）。
     * 播放时与最终动画同名解析（angel 形态播 inspect_angel 的音效表）。
     */
    private static void addSoundEffects(ObjectAnim animation, JsonElement element) {
        if (element == null || !element.isJsonObject() || element.getAsJsonObject().size() == 0) {
            return;
        }
        ObjectSoundChannel channel = new ObjectSoundChannel();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            float time;
            try {
                time = Float.parseFloat(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            JsonElement value = entry.getValue();
            String effect = null;
            if (value != null && value.isJsonObject()) {
                JsonElement effectElement = value.getAsJsonObject().get("effect");
                if (effectElement != null && effectElement.isJsonPrimitive()) {
                    effect = effectElement.getAsString();
                }
            } else if (value != null && value.isJsonPrimitive()) {
                effect = value.getAsString();
            }
            if (effect != null && !effect.isBlank()) {
                channel.put(time, effect);
            }
        }
        if (!channel.isEmpty()) {
            animation.setSoundChannel(channel);
        }
    }

    private static void addIfPresent(ObjectAnim animation, String node, AnimChannelType type, JsonElement frames) {
        if (frames == null) {
            return;
        }
        // 常量通道：直接写 [x, y, z]，视为时间 0 的单个关键帧
        if (frames.isJsonArray()) {
            animation.addChannel(buildChannel(node, type, List.of(new Key(0.0, frames))));
            return;
        }
        // 单个数字也常被用来写常量，尤其是 "scale": 0 —— 这是隐藏骨骼（备用外观变体）的写法。
        // 以前这里直接 return，通道不会创建，该骨骼就留在默认 scale=1，备用变体整份几何被画出来，
        // 叠在主模型上表现为「糊了一层黑、缝隙里透出正常颜色」+ z-fighting。
        if (frames.isJsonPrimitive() && frames.getAsJsonPrimitive().isNumber()) {
            animation.addChannel(buildChannel(node, type, List.of(new Key(0.0, frames))));
            return;
        }
        if (!frames.isJsonObject() || frames.getAsJsonObject().size() == 0) {
            return;
        }
        JsonObject frameObject = frames.getAsJsonObject();
        List<Key> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : frameObject.entrySet()) {
            double time;
            try {
                time = Double.parseDouble(entry.getKey());
            } catch (NumberFormatException e) {
                continue;
            }
            keys.add(new Key(time, entry.getValue()));
        }
        if (keys.isEmpty()) {
            return;
        }
        keys.sort(Comparator.comparingDouble(k -> k.time));
        animation.addChannel(buildChannel(node, type, keys));
    }

    private static AnimChannel buildChannel(String node, AnimChannelType type, List<Key> keys) {
        AnimChannel channel = new AnimChannel(type);
        channel.node = node;
        int count = keys.size();
        channel.content.keyframeTimeS = new float[count];
        channel.content.values = new float[count][];
        channel.content.lerpModes = new AnimChannelContent.LerpMode[count];
        for (int i = 0; i < count; i++) {
            Key key = keys.get(i);
            channel.content.keyframeTimeS[i] = (float) key.time;
            Keyframe keyframe = readKeyframe(key.element);
            channel.content.values[i] = buildValues(type, keyframe);
            channel.content.lerpModes[i] = parseLerpMode(type, keyframe.lerpMode);
        }
        channel.interpolator = new CustomInterpolator();
        channel.interpolator.compile(channel.content);
        return channel;
    }

    private static float[] buildValues(AnimChannelType type, Keyframe keyframe) {
        if (keyframe.pre != null && keyframe.post != null) {
            float[] values = new float[6];
            write(values, 0, convert(type, keyframe.pre));
            write(values, 3, convert(type, keyframe.post));
            return values;
        }
        if (keyframe.pre != null) {
            return convert(type, keyframe.pre);
        }
        if (keyframe.post != null) {
            return convert(type, keyframe.post);
        }
        if (keyframe.data != null) {
            return convert(type, keyframe.data);
        }
        return new float[]{0f, 0f, 0f};
    }

    private static float[] convert(AnimChannelType type, float[] value) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            if (type == AnimChannelType.TRANSLATION) {
                out[i] = value[i] / 16f;
            } else if (type == AnimChannelType.ROTATION) {
                out[i] = (float) Math.toRadians(value[i]);
            } else {
                out[i] = value[i];
            }
        }
        return out;
    }

    private static void write(float[] target, int offset, float[] value) {
        target[offset] = value[0];
        target[offset + 1] = value[1];
        target[offset + 2] = value[2];
    }

    private static AnimChannelContent.LerpMode parseLerpMode(AnimChannelType type, String name) {
        if (name == null) {
            return AnimChannelContent.LerpMode.LINEAR;
        }
        if (type == AnimChannelType.ROTATION) {
            return "catmullrom".equals(name.toLowerCase(Locale.ENGLISH))
                    ? AnimChannelContent.LerpMode.CATMULLROM
                    : AnimChannelContent.LerpMode.LINEAR;
        }
        try {
            return AnimChannelContent.LerpMode.valueOf(name.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            return AnimChannelContent.LerpMode.LINEAR;
        }
    }

    private static Keyframe readKeyframe(JsonElement element) {
        Keyframe keyframe = new Keyframe();
        if (element == null || element.isJsonNull()) {
            return keyframe;
        }
        if (element.isJsonArray()) {
            keyframe.data = readVec(element);
            return keyframe;
        }
        // 单值关键帧（"scale": 0 / "scale": 1）：按三轴广播
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            keyframe.data = readVec(element);
            return keyframe;
        }
        if (!element.isJsonObject()) {
            return keyframe;
        }
        JsonObject object = element.getAsJsonObject();
        keyframe.pre = readVec(object.get("pre"));
        keyframe.post = readVec(object.get("post"));
        keyframe.data = readVec(object.get("data"));
        if (object.has("lerp_mode") && object.get("lerp_mode").isJsonPrimitive()) {
            keyframe.lerpMode = object.get("lerp_mode").getAsString();
        }
        return keyframe;
    }

    private static float[] readVec(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        // 单值关键帧按三轴广播（对应 TACZ 的 Vector3f 反序列化）
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            float value = element.getAsFloat();
            return new float[]{value, value, value};
        }
        if (!element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            return null;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            JsonElement component = i < array.size() ? array.get(i) : null;
            if (component != null && component.isJsonPrimitive() && component.getAsJsonPrimitive().isNumber()) {
                out[i] = component.getAsFloat();
            }
        }
        return out;
    }

    private record Key(double time, JsonElement element) {
    }

    private static final class Keyframe {
        float[] pre;
        float[] post;
        float[] data;
        String lerpMode;
    }
}
