package com.punchytacz.anim;

/** 对应 TACZ AnimationListenerSupplier */
public interface AnimListenerSupplier {
    AnimListener supplyListeners(String nodeName, AnimChannelType type);
}
