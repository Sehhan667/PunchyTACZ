package com.punchytacz.anim;

/** 对应 TACZ Interpolator */
public interface Interpolator {
    void compile(AnimChannelContent content);

    float[] interpolate(int indexFrom, int indexTo, float alpha);
}
