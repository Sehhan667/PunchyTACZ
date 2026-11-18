package com.punchytacz.bedrock;

/** 取自 TACZ（GPL-3.0）pojo 的单面 UV */
public class FaceItem {
    public static final FaceItem EMPTY = new FaceItem(new float[]{0, 0}, new float[]{0, 0});
    private final float[] uv;
    private final float[] uvSize;

    public FaceItem(float[] uv, float[] uvSize) {
        this.uv = uv;
        this.uvSize = uvSize;
    }

    public float[] getUv() {
        return uv;
    }

    public float[] getUvSize() {
        return uvSize;
    }
}
