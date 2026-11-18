package com.punchytacz.bedrock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** 取自 TACZ（GPL-3.0）的 BedrockCube */
public interface BedrockCube {
    void compile(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
                 float red, float green, float blue, float alpha);
}
