package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 对应 TACZ ObjectAnimation（一个动画原型） */
public final class ObjectAnim {
    public final String name;
    private final Map<String, List<AnimChannel>> channels = new HashMap<>();
    public PlayType playType = PlayType.PLAY_ONCE_HOLD;
    private float maxEndTimeS = 0f;

    public ObjectAnim(String name) {
        this.name = Objects.requireNonNull(name);
    }

    /** 拷贝原型；新动画不包含任何 listener */
    public ObjectAnim(ObjectAnim source) {
        this.name = source.name;
        this.playType = source.playType;
        this.maxEndTimeS = source.maxEndTimeS;
        for (Map.Entry<String, List<AnimChannel>> entry : source.channels.entrySet()) {
            List<AnimChannel> newList = new ArrayList<>();
            for (AnimChannel channel : entry.getValue()) {
                AnimChannel copy = new AnimChannel(channel.type, channel.content);
                copy.node = channel.node;
                copy.interpolator = channel.interpolator;
                newList.add(copy);
            }
            this.channels.put(entry.getKey(), newList);
        }
    }

    public void addChannel(AnimChannel channel) {
        channels.computeIfAbsent(channel.node, k -> new ArrayList<>()).add(channel);
        if (channel.getEndTimeS() > maxEndTimeS) {
            maxEndTimeS = channel.getEndTimeS();
        }
    }

    public Map<String, List<AnimChannel>> getChannels() {
        return channels;
    }

    public void applyAnimationListeners(AnimListenerSupplier supplier) {
        for (List<AnimChannel> list : channels.values()) {
            for (AnimChannel channel : list) {
                AnimListener listener = supplier.supplyListeners(channel.node, channel.type);
                if (listener != null) {
                    channel.addListener(listener);
                }
            }
        }
    }

    public void update(boolean blend, long progressNs) {
        for (List<AnimChannel> list : channels.values()) {
            for (AnimChannel channel : list) {
                channel.update(progressNs / 1.0E9F, blend);
            }
        }
    }

    public float getMaxEndTimeS() {
        return maxEndTimeS;
    }

    public enum PlayType {
        /** 播放一次，停留在最后一帧 */
        PLAY_ONCE_HOLD,
        /** 播放一次后停止 */
        PLAY_ONCE_STOP,
        /** 循环播放 */
        LOOP
    }
}
