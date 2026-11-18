package com.punchytacz.diag;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时观察：把同一帧里每一次 submitCustomGeometry 的 RenderType 状态和顶点样本打出来。
 * <p>
 * 只调用对方**公开接口**（把它的回调喂给一个空消费者），不反编译、不读实现。
 * 用来回答"我们和 Punchy 提交的东西到底哪里不同"。
 */
public final class SubmitProbe {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz-probe");

    /** 由 TaczRenderer 在我们自己提交期间置 true，用来区分"谁提交的" */
    public static volatile boolean ours = false;
    public static volatile boolean enabled = false;

    private static long lastOursNs;
    private static long lastOtherNs;

    private SubmitProbe() {
    }

    public static void probe(PoseStack stack, RenderType rt, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (!enabled) {
            return;
        }
        boolean isOurs = ours;
        long now = System.nanoTime();
        long last = isOurs ? lastOursNs : lastOtherNs;
        if (now - last < 2_000_000_000L) {
            return;
        }
        if (isOurs) {
            lastOursNs = now;
        } else {
            lastOtherNs = now;
        }
        try {
            SpyVertexConsumer spy = new SpyVertexConsumer();
            renderer.render(stack.last(), spy);
            StringBuilder sb = new StringBuilder();
            sb.append("punchytacz-probe: who=").append(isOurs ? "OURS" : "OTHER")
                    .append(" rt=").append(rt)
                    .append(" blend=").append(rt.hasBlending());
            try {
                var p = rt.pipeline();
                var d = p.getDepthStencilState();
                sb.append(" cull=").append(p.isCull())
                        .append(" writeDepth=").append(d != null && d.writeDepth());
            } catch (Throwable ignored) {
            }
            sb.append(" verts=").append(spy.count)
                    .append(String.format(java.util.Locale.ROOT,
                            " u=[%.3f,%.3f] v=[%.3f,%.3f] outside=%d",
                            spy.minU, spy.maxU, spy.minV, spy.maxV, spy.outside));
            // spy 采样比真实顶点滞后一个（addVertex 先于各 setter 调用），所以从 1 开始取
            for (int i = 1; i < Math.min(4, spy.samples.size()); i++) {
                float[] s = spy.samples.get(i);
                sb.append(String.format(java.util.Locale.ROOT,
                        " [v%d pos=(%.3f,%.3f,%.3f) uv=(%.4f,%.4f) col=%08X ov=%d,%d light=%d,%d n=(%.2f,%.2f,%.2f)]",
                        i, s[0], s[1], s[2], s[3], s[4], (int) s[5],
                        (int) s[6], (int) s[7], (int) s[8], (int) s[9], s[10], s[11], s[12]));
            }
            LOG.info(sb.toString());
        } catch (Throwable t) {
            LOG.warn("punchytacz-probe: 采样失败 " + t);
        }
    }
}
