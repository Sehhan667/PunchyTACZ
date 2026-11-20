package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 对应 TACZ ObjectAnimationSoundChannel：按关键帧时刻收集跨过的音效请求 */
public final class ObjectSoundChannel {
    /**
     * key = 时刻（秒），value = 该时刻的所有音效 id。
     * 用 List 而不是单值：同一时刻写了多条 sound_effects 时，单值会被后者覆盖。
     * （实测包里 melee_1 是 0.0 / 0.4167 / 1.1167 三个时刻，各一条；但格式本身允许同一时刻多条）
     */
    private final TreeMap<Float, java.util.List<String>> keyframes = new TreeMap<>();

    public void put(float timeS, String effect) {
        keyframes.computeIfAbsent(timeS, k -> new java.util.ArrayList<>()).add(effect);
    }

    /** 收集 (from, to] 之间跨过的音效 */
    public void collect(float fromS, float toS, List<String> out) {
        if (keyframes.isEmpty() || fromS == toS) {
            return;
        }
        // LOOP 回绕时分段收集，避免从尾部直接跳到头部漏掉音效
        if (toS < fromS) {
            collectRange(fromS, maxTime() + 1.0E-4F, out);
            collectRange(0F, toS, out);
            return;
        }
        collectRange(fromS, toS, out);
    }

    private float maxTime() {
        return keyframes.lastKey();
    }

    private void collectRange(float fromS, float toS, List<String> out) {
        // 关键：t=0 的音效必须包含进来。原来下界是排他的（false），
        // 而每段动画起步时 from=0，于是 0.0 那一帧的音效**永远不会播**
        // —— 表现为"时有时无"：拔刀只有 0.0 一条 → 完全没声；连击的 0.0 那条丢，中间的还有。
        boolean fromInclusive = fromS <= 0.0F;
        for (Map.Entry<Float, java.util.List<String>> entry
                : keyframes.subMap(fromS, fromInclusive, toS, true).entrySet()) {
            out.addAll(entry.getValue());
        }
    }

    public boolean isEmpty() {
        return keyframes.isEmpty();
    }

    /** 最后一个关键帧的时刻（秒） */
    public float lastTime() {
        return keyframes.isEmpty() ? 0f : keyframes.lastKey();
    }
}
