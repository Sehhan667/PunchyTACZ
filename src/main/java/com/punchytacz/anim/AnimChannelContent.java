package com.punchytacz.anim;

import java.util.Arrays;

/** 轨道关键帧数据（对应 TACZ AnimationChannelContent） */
public final class AnimChannelContent {
    public float[] keyframeTimeS;
    /**
     * 与 keyframeTimeS 一一对应。长度 3 或 6 表示三轴值（6 时后三位是 post），
     * 长度 4 或 8 表示四元数（8 时后四位是 post）。
     */
    public float[][] values;
    /** 仅供 CustomInterpolator 使用 */
    public LerpMode[] lerpModes;

    public AnimChannelContent() {
    }

    public AnimChannelContent(AnimChannelContent source) {
        if (source.keyframeTimeS != null) {
            this.keyframeTimeS = Arrays.copyOf(source.keyframeTimeS, source.keyframeTimeS.length);
        }
        if (source.values != null) {
            this.values = Arrays.stream(source.values)
                    .map(v -> Arrays.copyOf(v, v.length))
                    .toArray(float[][]::new);
        }
        if (source.lerpModes != null) {
            this.lerpModes = Arrays.copyOf(source.lerpModes, source.lerpModes.length);
        }
    }

    public enum LerpMode {
        LINEAR, SPHERICAL_LINEAR, CATMULLROM, SPHERICAL_SQUAD
    }
}
