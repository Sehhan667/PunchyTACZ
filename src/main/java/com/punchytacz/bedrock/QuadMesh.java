package com.punchytacz.bedrock;

/**
 * 烘焙后的静态四边形网格。
 * <p>
 * 每个顶点 8 个 float：{@code x,y,z, nx,ny,nz, u,v}，坐标单位是**方块**（已除 16），
 * 法线是模型空间单位向量。顶点按四边形连续存放，{@link #quads()} × 4 个顶点。
 * <p>
 * 模型解析完就烘焙一次，之后每帧只做「矩阵变换 + 写顶点」，不再重建几何对象。
 */
public final class QuadMesh {
    public static final int FLOATS_PER_VERTEX = 8;
    public static final int FLOATS_PER_QUAD = 4 * FLOATS_PER_VERTEX;

    private float[] data = new float[FLOATS_PER_QUAD * 16];
    private int floats;

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
        return this.floats / FLOATS_PER_QUAD;
    }

    public boolean isEmpty() {
        return this.floats == 0;
    }
}
