package com.punchytacz.bedrock;

import net.minecraft.core.Direction;

/**
 * 烘焙后的静态四边形网格。
 * <p>
 * 每个顶点 8 个 float：{@code x,y,z, nx,ny,nz, u,v}，坐标单位是**方块**（已除 16），
 * 法线是模型空间单位向量。顶点按四边形连续存放（{@link #quads()} × 4 个顶点），
 * 另有一份每四边形一个字节的「面朝向」（{@link Direction#ordinal()}），供朝向调试过滤。
 */
public final class QuadMesh {
    public static final int FLOATS_PER_VERTEX = 8;
    public static final int FLOATS_PER_QUAD = 4 * FLOATS_PER_VERTEX;

    private float[] data = new float[FLOATS_PER_QUAD * 16];
    private byte[] faces = new byte[16];
    private int floats;
    private int quads;

    /** 开始写一个新的四边形，记录它的面朝向 */
    public void beginQuad(int faceId) {
        if (this.quads == this.faces.length) {
            this.faces = java.util.Arrays.copyOf(this.faces, Math.max(16, this.quads * 2));
        }
        this.faces[this.quads++] = (byte) faceId;
    }

    public void vertex(float x, float y, float z, float nx, float ny, float nz, float u, float v) {
        if (this.floats + FLOATS_PER_VERTEX > this.data.length) {
            this.data = java.util.Arrays.copyOf(this.data,
                    Math.max(this.data.length * 2, this.floats + FLOATS_PER_VERTEX));
        }
        int o = this.floats;
        this.data[o] = x;
        this.data[o + 1] = y;
        this.data[o + 2] = z;
        this.data[o + 3] = nx;
        this.data[o + 4] = ny;
        this.data[o + 5] = nz;
        this.data[o + 6] = u;
        this.data[o + 7] = v;
        this.floats += FLOATS_PER_VERTEX;
    }

    public float[] data() {
        return this.data;
    }

    public int quads() {
        return this.quads;
    }

    /** 第 q 个四边形的面朝向（Direction.ordinal） */
    public int faceId(int q) {
        return this.faces[q];
    }

    public boolean isEmpty() {
        return this.quads == 0;
    }
}
