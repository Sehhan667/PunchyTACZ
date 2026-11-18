package com.punchytacz.diag;

import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * 只记录前若干个顶点的空消费者：用来在运行时观察"别人提交的是什么几何"。
 * 不参与绘制，写进去的数据全部丢弃。
 */
public final class SpyVertexConsumer implements VertexConsumer {
    public static final int MAX = 6;

    public final List<float[]> samples = new ArrayList<>(MAX);
    public int count;
    public float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
    public float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
    public int outside;

    private float x, y, z;
    private int color = 0xFFFFFFFF;
    private float u, v;
    private int u1a, u1b;   // overlay
    private int u2a, u2b;   // light
    private float nx, ny, nz;

    @Override
    public VertexConsumer addVertex(float px, float py, float pz) {
        this.x = px;
        this.y = py;
        this.z = pz;
        if (u < minU) minU = u;
        if (u > maxU) maxU = u;
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
        if (u < -0.001f || u > 1.001f || v < -0.001f || v > 1.001f) outside++;
        if (samples.size() < MAX) {
            samples.add(new float[]{px, py, pz, u, v, color, u1a, u1b, u2a, u2b, nx, ny, nz});
        }
        count++;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.color = (a << 24) | (r << 16) | (g << 8) | b;
        return this;
    }

    @Override
    public VertexConsumer setColor(int c) {
        this.color = c;
        return this;
    }

    @Override
    public VertexConsumer setUv(float su, float sv) {
        this.u = su;
        this.v = sv;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int a, int b) {
        this.u1a = a;
        this.u1b = b;
        return this;
    }

    @Override
    public VertexConsumer setUv2(int a, int b) {
        this.u2a = a;
        this.u2b = b;
        return this;
    }

    @Override
    public VertexConsumer setUv3(float a, float b) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float sx, float sy, float sz) {
        this.nx = sx;
        this.ny = sy;
        this.nz = sz;
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float w) {
        return this;
    }
}
