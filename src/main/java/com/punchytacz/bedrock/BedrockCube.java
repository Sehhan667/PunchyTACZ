package com.punchytacz.bedrock;

/** 取自 TACZ（GPL-3.0）的 BedrockCube，输出阶段改为一次性烘焙 */
public interface BedrockCube {
    /** 把本 cube 的所有面写入静态网格（模型空间，坐标已归一化到方块） */
    void bake(QuadMesh mesh);
}
