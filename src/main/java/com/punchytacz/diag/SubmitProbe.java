package com.punchytacz.diag;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 每帧统计「物品几何到底被画了几次」。
 * <p>
 * 三个互相独立的计数：
 * <ol>
 *   <li>{@code submit}：同一帧里 {@code submitCustomGeometry} 被调用了几次（按来源分 OURS / OTHER）</li>
 *   <li>{@code draw}：我们那条几何回调在**绘制阶段**真正被执行了几次（一次提交被执行两次 = 双层）</li>
 *   <li>{@code draw}：{@code PunchyApi.drawItem} 回调被问了几次</li>
 * </ol>
 * 每秒打印一次这四项的峰值，避开旧探针 2 秒节流把重复提交滤掉的问题。
 */
public final class SubmitProbe {
    public static volatile boolean enabled;
    /** true 表示当前这次 submit 由本模组发起 */
    public static volatile boolean ours;

    private static int frame;
    private static int ourSubmits;
    private static int otherSubmits;
    private static int ourDraws;
    private static int drawCalls;
    private static long otherVerts;
    private static String otherDesc = "-";

    private static int maxOurSubmits;
    private static int maxOtherSubmits;
    private static int maxOurDraws;
    private static int maxDrawCalls;
    private static long lastLogNs;

    private SubmitProbe() {
    }

    /** 每帧开头调用（TaczRenderer.renderFrame HEAD） */
    public static void beginFrame() {
        if (!enabled) {
            return;
        }
        maxOurSubmits = Math.max(maxOurSubmits, ourSubmits);
        maxOtherSubmits = Math.max(maxOtherSubmits, otherSubmits);
        maxOurDraws = Math.max(maxOurDraws, ourDraws);
        maxDrawCalls = Math.max(maxDrawCalls, drawCalls);
        frame++;
        ourSubmits = 0;
        otherSubmits = 0;
        ourDraws = 0;
        drawCalls = 0;
        otherVerts = 0;
        otherDesc = "-";
        long now = System.nanoTime();
        if (now - lastLogNs > 1_000_000_000L) {
            lastLogNs = now;
            org.slf4j.LoggerFactory.getLogger("punchytacz-count").info(
                    "frame#{} 峰值 | submit ours={} other={} | 几何回调执行={} | drawItem 回调={} | otherVerts={} other={}",
                    frame, maxOurSubmits, maxOtherSubmits, maxOurDraws, maxDrawCalls, otherVerts, otherDesc);
            maxOurSubmits = 0;
            maxOtherSubmits = 0;
            maxOurDraws = 0;
            maxDrawCalls = 0;
        }
    }

    /** 我们那条几何回调每次真正执行时调用 */
    public static void ourDraw() {
        if (enabled) {
            ourDraws++;
        }
    }

    /** PunchyApi.drawItem 每被问一次 */
    public static void drawCall() {
        if (enabled) {
            drawCalls++;
        }
    }

    /** 由 SubmitNodeCollection 的 mixin 调用 */
    public static void submit(PoseStack stack, RenderType renderType,
                              SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (!enabled) {
            return;
        }
        if (ours) {
            ourSubmits++;
            return;
        }
        otherSubmits++;
        CountVertexConsumer counter = new CountVertexConsumer();
        try {
            renderer.render(stack.last(), counter);
        } catch (Throwable ignored) {
        }
        otherVerts = counter.count;
        otherDesc = renderType.toString().replace("net.minecraft.client.renderer.rendertype.", "");
    }
}
