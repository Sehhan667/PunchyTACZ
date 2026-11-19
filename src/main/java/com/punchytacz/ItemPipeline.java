package com.punchytacz;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;

/**
 * 自建的物品几何 RenderPipeline。
 * <p>
 * 26.3 没有旧版的「立即模式」上传接口（Tesselator/BufferUploader 已删除，一切绘制都要走
 * {@code RenderPass}），但 {@code RenderPipeline.builder(Snippet...)} 是公开的：
 * 拿原版 {@code ENTITY_SNIPPET}（含 entity 顶点格式、Sampler 布局、着色器）当底子，
 * 自己加宏、自己定剔除与深度，等于**着色器行为由我们说了算**。
 * <p>
 * 参照原版 {@code ENTITY_CUTOUT_CULL} 的拼法（RenderPipelines.clinit 3285-3317）：
 * {@code builder(ENTITY_SNIPPET).withLocation(..).withShaderDefine("ALPHA_CUTOUT", 0.1f)
 * .withBindGroupLayout(BindGroupLayouts.SAMPLER1).withColorTargetState(DEFAULT).build()}。
 */
public final class ItemPipeline {
    private static volatile RenderPipeline.Snippet entitySnippet;
    private static RenderPipeline flat;
    private static RenderPipeline bright;
    private static boolean warned;

    private ItemPipeline() {
    }

    /** 原版 ENTITY_SNIPPET（私有字段，反射取一次） */
    static RenderPipeline.Snippet entitySnippet() {
        RenderPipeline.Snippet cached = entitySnippet;
        if (cached != null) {
            return cached;
        }
        try {
            var field = RenderPipelines.class.getDeclaredField("ENTITY_SNIPPET");
            field.setAccessible(true);
            cached = (RenderPipeline.Snippet) field.get(null);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                org.slf4j.LoggerFactory.getLogger("punchytacz")
                        .warn("punchytacz: 取 ENTITY_SNIPPET 失败", t);
            }
        }
        entitySnippet = cached;
        return cached;
    }

    /** NO_CARDINAL_LIGHTING：不做逐面方向光（0.4~1.0 的 per-face 明暗），其余与原版一致 */
    public static RenderPipeline flat() {
        RenderPipeline cached = flat;
        if (cached == null) {
            cached = build("punchytacz:item_flat", "NO_CARDINAL_LIGHTING");
            flat = cached;
        }
        return cached;
    }

    /** EMISSIVE：连光照贴图都不乘，输出 = 贴图 × 顶点色，用于排除所有光照项 */
    public static RenderPipeline bright() {
        RenderPipeline cached = bright;
        if (cached == null) {
            cached = build("punchytacz:item_bright", "EMISSIVE");
            bright = cached;
        }
        return cached;
    }

    private static RenderPipeline build(String name, String define) {
        RenderPipeline.Snippet snippet = entitySnippet();
        // snippet 为 null 时返回 null，由 ItemRenderType 回退到原版管线
        if (snippet == null) {
            return null;
        }
        RenderPipeline.Builder builder = RenderPipeline.builder(snippet)
                .withLocation(name)
                .withShaderDefine("ALPHA_CUTOUT", 0.1F)
                .withShaderDefine(define)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withColorTargetState(ColorTargetState.DEFAULT)
                // 默认值是剔除（原版 *_CULL 都没显式设置），这里显式按配置来
                .withCull(Settings.cull);
        return builder.build();
    }

    /** 配置变化后重建（cull 是烘焙进 pipeline 的） */
    public static void invalidate() {
        flat = null;
        bright = null;
    }
}
