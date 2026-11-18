package com.punchytacz;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 物品几何专用 RenderType。
 * <p>
 * 和 {@code RenderTypes.entityCutout} 唯一的区别是**显式指定采样器**：
 * 这套刀包的 UV 贴图是「每个面只覆盖 1~2 个像素」的色带图，紧邻像素大多是完全透明的
 * 黑色 (0,0,0,0)。默认的 LINEAR 采样会在面内混入这些黑色像素，把整块表面压成暗色；
 * 换成 NEAREST + CLAMP 后每个面只会采到自己那几个像素，颜色才和作者画的一致。
 * <p>
 * {@code RenderSetup.builder(...).withTexture(name, id, supplier)} 允许直接给
 * {@link com.mojang.renderpearl.api.textures.GpuSampler}，不需要改动贴图对象本身
 * （也就能和其他模组共享同一张贴图而不互相影响）。
 */
public final class ItemRenderType {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");
    private static final Map<String, RenderType> CACHE = new HashMap<>();
    private static boolean warned;

    private ItemRenderType() {
    }

    public static RenderType of(Identifier texture) {
        String key = Settings.mode.name() + '|' + Settings.filter.name() + '|' + texture;
        RenderType cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        RenderType made = build(texture);
        CACHE.put(key, made);
        return made;
    }

    /** 资源重载后清空，避免继续引用旧贴图 */
    public static void clear() {
        CACHE.clear();
    }

    private static RenderType build(Identifier texture) {
        try {
            RenderPipeline pipeline = switch (Settings.mode) {
                case CUTOUT -> RenderPipelines.ENTITY_CUTOUT;
                case CUTOUT_CULL -> RenderPipelines.ENTITY_CUTOUT_CULL;
                case TRANSLUCENT -> RenderPipelines.ENTITY_TRANSLUCENT;
                case TRANSLUCENT_CULL -> RenderPipelines.ENTITY_TRANSLUCENT_CULL;
            };
            FilterMode filter = Settings.filter == Settings.Filter.NEAREST ? FilterMode.NEAREST : FilterMode.LINEAR;
            RenderSetup setup = RenderSetup.builder(pipeline)
                    .withTexture("Sampler0", texture, () -> RenderSystem.getSamplerCache().getClampToEdge(filter))
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup();
            // RenderType.create 是包私有的，只能反射
            Method create = RenderType.class.getDeclaredMethod("create", String.class, RenderSetup.class);
            create.setAccessible(true);
            String name = "punchytacz_item/" + Settings.mode.name() + "/" + Settings.filter.name() + "/"
                    + texture.toString().replace(':', '_').replace('/', '_');
            return (RenderType) create.invoke(null, name.toLowerCase(Locale.ROOT), setup);
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("punchytacz: 自建 RenderType 失败，回退到原版 RenderType", t);
            }
            return switch (Settings.mode) {
                case CUTOUT -> RenderTypes.entityCutout(texture);
                case CUTOUT_CULL -> RenderTypes.entityCutoutCull(texture);
                case TRANSLUCENT -> RenderTypes.entityTranslucent(texture);
                case TRANSLUCENT_CULL -> RenderTypes.entityTranslucentCull(texture);
            };
        }
    }
}
