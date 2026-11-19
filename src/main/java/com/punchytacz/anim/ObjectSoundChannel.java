package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 对应 TACZ ObjectAnimationSoundChannel：按关键帧时刻收集跨过的音效请求 */
public final class ObjectSoundChannel {
    /** key = 时刻（秒），value = 音效 id（如 wt:melee/annihil/annihil_inspect_1） */
    private final TreeMap<Float, String> keyframes = new TreeMap<>();

    public void put(float timeS, String effect) {
        keyframes.put(timeS, effect);
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
        for (Map.Entry<Float, String> entry : keyframes.subMap(fromS, false, toS, true).entrySet()) {
            out.add(entry.getValue());
        }
    }

    public boolean isEmpty() {
        return keyframes.isEmpty();
    }
}
