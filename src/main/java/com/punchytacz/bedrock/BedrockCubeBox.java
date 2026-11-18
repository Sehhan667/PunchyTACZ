package com.punchytacz.bedrock;

import net.minecraft.core.Direction;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** 取自 TACZ（GPL-3.0）的盒型 UV cube */
public class BedrockCubeBox implements BedrockCube {
    public final float minX;
    public final float minY;
    public final float minZ;
    public final float maxX;
    public final float maxY;
    public final float maxZ;
    private final BedrockPolygon[] polygons;

    public BedrockCubeBox(float texOffX, float texOffY, float x, float y, float z, float width, float height, float depth,
                          float delta, boolean mirror, float texWidth, float texHeight) {
        this(texOffX, texOffY, x, y, z, width, height, depth, delta, mirror, texWidth, texHeight, null);
    }

    public BedrockCubeBox(float texOffX, float texOffY, float x, float y, float z, float width, float height, float depth,
                          float delta, boolean mirror, float texWidth, float texHeight, Matrix4fc transform) {
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

        if (mirror) {
            float tmp = xEnd;
            xEnd = x;
            x = tmp;
        }

        BedrockVertex vertex1 = new BedrockVertex(x, y, z, 0.0F, 0.0F);
        BedrockVertex vertex2 = new BedrockVertex(xEnd, y, z, 0.0F, 8.0F);
        BedrockVertex vertex3 = new BedrockVertex(xEnd, yEnd, z, 8.0F, 8.0F);
        BedrockVertex vertex4 = new BedrockVertex(x, yEnd, z, 8.0F, 0.0F);
        BedrockVertex vertex5 = new BedrockVertex(x, y, zEnd, 0.0F, 0.0F);
        BedrockVertex vertex6 = new BedrockVertex(xEnd, y, zEnd, 0.0F, 8.0F);
        BedrockVertex vertex7 = new BedrockVertex(xEnd, yEnd, zEnd, 8.0F, 8.0F);
        BedrockVertex vertex8 = new BedrockVertex(x, yEnd, zEnd, 8.0F, 0.0F);

        int dx = (int) width;
        int dy = (int) height;
        int dz = (int) depth;

        float p1 = texOffX + dz;
        float p2 = texOffX + dz + dx;
        float p3 = texOffX + dz + dx + dx;
        float p4 = texOffX + dz + dx + dz;
        float p5 = texOffX + dz + dx + dz + dx;
        float p6 = texOffY + dz;
        float p7 = texOffY + dz + dy;
        float p8 = texOffY;
        float p9 = texOffX;

        this.polygons[2] = new BedrockPolygon(new BedrockVertex[]{vertex6, vertex5, vertex1, vertex2}, p1, p8, p2, p6, texWidth, texHeight, mirror, Direction.DOWN);
        this.polygons[3] = new BedrockPolygon(new BedrockVertex[]{vertex3, vertex4, vertex8, vertex7}, p2, p6, p3, p8, texWidth, texHeight, mirror, Direction.UP);
        this.polygons[1] = new BedrockPolygon(new BedrockVertex[]{vertex1, vertex5, vertex8, vertex4}, p9, p6, p1, p7, texWidth, texHeight, mirror, Direction.WEST);
        this.polygons[4] = new BedrockPolygon(new BedrockVertex[]{vertex2, vertex1, vertex4, vertex3}, p1, p6, p2, p7, texWidth, texHeight, mirror, Direction.NORTH);
        this.polygons[0] = new BedrockPolygon(new BedrockVertex[]{vertex6, vertex2, vertex3, vertex7}, p2, p6, p4, p7, texWidth, texHeight, mirror, Direction.EAST);
        this.polygons[5] = new BedrockPolygon(new BedrockVertex[]{vertex5, vertex6, vertex7, vertex8}, p4, p6, p5, p7, texWidth, texHeight, mirror, Direction.SOUTH);
        if (transform != null) {
            BedrockPolygon.bakeTransform(polygons, transform, vertex1, vertex2, vertex3, vertex4, vertex5, vertex6, vertex7, vertex8);
        }
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
