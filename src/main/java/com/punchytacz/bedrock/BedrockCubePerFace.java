package com.punchytacz.bedrock;

import net.minecraft.core.Direction;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** 取自 TACZ（GPL-3.0）的逐面 UV cube */
public class BedrockCubePerFace implements BedrockCube {
    public final float minX;
    public final float minY;
    public final float minZ;
    public final float maxX;
    public final float maxY;
    public final float maxZ;
    private final BedrockPolygon[] polygons;

    public BedrockCubePerFace(float x, float y, float z, float width, float height, float depth, float delta,
                              float texWidth, float texHeight, FaceUVsItem faces) {
        this(x, y, z, width, height, depth, delta, texWidth, texHeight, faces, null);
    }

    public BedrockCubePerFace(float x, float y, float z, float width, float height, float depth, float delta,
                              float texWidth, float texHeight, FaceUVsItem faces, Matrix4fc transform) {
        if (transform == null) {
            this.minX = x;
            this.minY = y;
            this.minZ = z;
            this.maxX = x + width;
            this.maxY = y + height;
            this.maxZ = z + depth;
        } else {
            Vector3f min = new Vector3f();
            Vector3f max = new Vector3f();
            transform.transformAab(x, y, z, x + width, y + height, z + depth, min, max);
            this.minX = min.x;
            this.minY = min.y;
            this.minZ = min.z;
            this.maxX = max.x;
            this.maxY = max.y;
            this.maxZ = max.z;
        }
        this.polygons = new BedrockPolygon[6];

        float xEnd = x + width;
        float yEnd = y + height;
        float zEnd = z + depth;
        x = x - delta;
        y = y - delta;
        z = z - delta;
        xEnd = xEnd + delta;
        yEnd = yEnd + delta;
        zEnd = zEnd + delta;

        BedrockVertex vertex1 = new BedrockVertex(x, y, z, 0.0F, 0.0F);
        BedrockVertex vertex2 = new BedrockVertex(xEnd, y, z, 0.0F, 8.0F);
        BedrockVertex vertex3 = new BedrockVertex(xEnd, yEnd, z, 8.0F, 8.0F);
        BedrockVertex vertex4 = new BedrockVertex(x, yEnd, z, 8.0F, 0.0F);
        BedrockVertex vertex5 = new BedrockVertex(x, y, zEnd, 0.0F, 0.0F);
        BedrockVertex vertex6 = new BedrockVertex(xEnd, y, zEnd, 0.0F, 8.0F);
        BedrockVertex vertex7 = new BedrockVertex(xEnd, yEnd, zEnd, 8.0F, 8.0F);
        BedrockVertex vertex8 = new BedrockVertex(x, yEnd, zEnd, 8.0F, 0.0F);

        this.polygons[2] = getTexturedQuad(new BedrockVertex[]{vertex6, vertex5, vertex1, vertex2}, texWidth, texHeight, Direction.DOWN, faces);
        this.polygons[3] = getTexturedQuad(new BedrockVertex[]{vertex3, vertex4, vertex8, vertex7}, texWidth, texHeight, Direction.UP, faces);
        this.polygons[1] = getTexturedQuad(new BedrockVertex[]{vertex1, vertex5, vertex8, vertex4}, texWidth, texHeight, Direction.WEST, faces);
        this.polygons[4] = getTexturedQuad(new BedrockVertex[]{vertex2, vertex1, vertex4, vertex3}, texWidth, texHeight, Direction.NORTH, faces);
        this.polygons[0] = getTexturedQuad(new BedrockVertex[]{vertex6, vertex2, vertex3, vertex7}, texWidth, texHeight, Direction.EAST, faces);
        this.polygons[5] = getTexturedQuad(new BedrockVertex[]{vertex5, vertex6, vertex7, vertex8}, texWidth, texHeight, Direction.SOUTH, faces);
        if (transform != null) {
            BedrockPolygon.bakeTransform(polygons, transform, vertex1, vertex2, vertex3, vertex4, vertex5, vertex6, vertex7, vertex8);
        }
    }

    /** 该方向没有 uv 条目时返回 null（原实现用一块退化面占位，结果是一样的：不产生像素） */
    private BedrockPolygon getTexturedQuad(BedrockVertex[] positionsIn, float texWidth, float texHeight,
                                           Direction direction, FaceUVsItem faces) {
        FaceItem face = faces.getFace(mapLookup(direction));
        if (face == FaceItem.EMPTY) {
            return null;
        }
        float u1 = face.getUv()[0];
        float v1 = face.getUv()[1];
        float u2 = u1 + face.getUvSize()[0];
        float v2 = v1 + face.getUvSize()[1];
        return new BedrockPolygon(positionsIn, u1, v1, u2, v2, texWidth, texHeight, false, direction);
    }

    /**
     * **固定对调 east / west 的 UV 条目**（见 {@code Settings.swapEastWest} 的说明）。
     * 几何位置与朝向不变，只改「查哪个 UV 条目」；关闭开关即恢复原约定。
     */
    private static Direction mapLookup(Direction direction) {
        if (!com.punchytacz.Settings.swapEastWest) {
            return direction;
        }
        return direction == Direction.EAST ? Direction.WEST
                : direction == Direction.WEST ? Direction.EAST : direction;
    }

    @Override
    public void bake(QuadMesh mesh) {
        for (BedrockPolygon polygon : this.polygons) {
            if (polygon != null) {
                polygon.bake(mesh);
            }
        }
    }
}
