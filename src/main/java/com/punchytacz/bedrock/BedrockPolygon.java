package com.punchytacz.bedrock;

import net.minecraft.core.Direction;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/** 取自 TACZ（GPL-3.0）的 BedrockPolygon */
public class BedrockPolygon {
    public final BedrockVertex[] vertices;
    public final Vector3f normal;

    public static void bakeTransform(BedrockPolygon[] polygons, Matrix4fc transform, BedrockVertex... corners) {
        for (BedrockVertex corner : corners) {
            transform.transformPosition(corner.pos);
        }
        for (BedrockPolygon polygon : polygons) {
            if (polygon != null) {
                transform.transformDirection(polygon.normal);
            }
        }
    }

    public BedrockPolygon(BedrockVertex[] vertices, float u1, float v1, float u2, float v2,
                          float texWidth, float texHeight, boolean mirror, Direction direction) {
        this.vertices = vertices;
        // 基岩 UV 以贴图像素为单位，这里一次换算成 0..1（原实现里的 f/f1 恒为 0，已去掉）
        vertices[0] = vertices[0].remap(u2 / texWidth, v1 / texHeight);
        vertices[1] = vertices[1].remap(u1 / texWidth, v1 / texHeight);
        vertices[2] = vertices[2].remap(u1 / texWidth, v2 / texHeight);
        vertices[3] = vertices[3].remap(u2 / texWidth, v2 / texHeight);
        if (mirror) {
            int i = vertices.length;
            for (int j = 0; j < i / 2; ++j) {
                BedrockVertex bedrockVertex = vertices[j];
                vertices[j] = vertices[i - 1 - j];
                vertices[i - 1 - j] = bedrockVertex;
            }
        }
        this.normal = direction.step();
        if (mirror) {
            this.normal.mul(-1.0F, 1.0F, 1.0F);
        }
    }

    /** 写入静态网格：位置从模型单位（1/16 格）换算成方块 */
    public void bake(QuadMesh mesh) {
        for (BedrockVertex vertex : this.vertices) {
            mesh.vertex(vertex.pos.x() / 16.0F, vertex.pos.y() / 16.0F, vertex.pos.z() / 16.0F,
                    this.normal.x, this.normal.y, this.normal.z, vertex.u, vertex.v);
        }
    }
}
