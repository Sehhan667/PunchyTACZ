package com.punchytacz.anim;

/** 动画队列中的一项（对应 TACZ AnimationPlan） */
public record AnimationPlan(String animationName, ObjectAnim.PlayType playType, float transitionTimeS) {
}
