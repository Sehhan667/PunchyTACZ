package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 对应 TACZ ObjectAnimationChannel */
public final class AnimChannel {
    public final AnimChannelType type;
    public String node;
    public AnimChannelContent content;
    public Interpolator interpolator;
    /** 过渡期间由起点通道统一写入，避免目标通道重复覆盖 */
    boolean transitioning = false;
    private final List<AnimListener> listeners = new ArrayList<>();

    public AnimChannel(AnimChannelType type) {
        this.type = type;
        this.content = new AnimChannelContent();
    }

    public AnimChannel(AnimChannelType type, AnimChannelContent content) {
        this.type = type;
        this.content = content;
    }

    public void addListener(AnimListener listener) {
        if (listener.getType() == type) {
            listeners.add(listener);
        } else {
            throw new RuntimeException("trying to add wrong type of listener to channel");
        }
    }

    public List<AnimListener> getListeners() {
        return listeners;
    }

    public float getEndTimeS() {
        if (content.keyframeTimeS == null || content.keyframeTimeS.length == 0) {
            return 0;
        }
        return content.keyframeTimeS[content.keyframeTimeS.length - 1];
    }

    /** @param timeS 绝对时间（秒） */
    public void update(float timeS, boolean blend) {
        if (!transitioning) {
            float[] result = getResult(timeS);
            for (AnimListener listener : listeners) {
                listener.update(result, blend);
            }
        }
    }

    public float[] getResult(float timeS) {
        if (content.keyframeTimeS == null || content.keyframeTimeS.length == 0) {
            return new float[]{0f, 0f, 0f};
        }
        int indexFrom = computeIndex(timeS);
        int indexTo = Math.min(content.keyframeTimeS.length - 1, indexFrom + 1);
        float alpha = computeAlpha(timeS, indexFrom);
        return interpolator.interpolate(indexFrom, indexTo, alpha);
    }

    private int computeIndex(float timeS) {
        int index = Arrays.binarySearch(content.keyframeTimeS, timeS);
        if (index >= 0) {
            return index;
        }
        return Math.max(0, -index - 2);
    }

    private float computeAlpha(float timeS, int indexFrom) {
        if (timeS <= content.keyframeTimeS[0]) {
            return 0f;
        }
        if (timeS >= content.keyframeTimeS[content.keyframeTimeS.length - 1]) {
            return 1f;
        }
        float local = timeS - content.keyframeTimeS[indexFrom];
        float delta = content.keyframeTimeS[indexFrom + 1] - content.keyframeTimeS[indexFrom];
        return delta == 0 ? 0f : local / delta;
    }
}
