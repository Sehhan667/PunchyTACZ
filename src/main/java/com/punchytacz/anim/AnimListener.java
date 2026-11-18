package com.punchytacz.anim;

/** 对应 TACZ AnimationListener */
public interface AnimListener {
    /**
     * @param values TRANSLATION/SCALE 长度 3；ROTATION 长度 3（欧拉角）或 4（四元数）
     * @param blend  true 表示应当累加而不是覆盖
     */
    void update(float[] values, boolean blend);

    float[] initialValue();

    AnimChannelType getType();
}
