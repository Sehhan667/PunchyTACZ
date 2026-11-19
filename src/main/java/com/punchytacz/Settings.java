package com.punchytacz;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 运行时可改的渲染参数（静态字段，改完立即生效，不需要重启游戏）。
 * <p>
 * 只保留**确实会改变画面**的四个开关。以前那一堆 UV 翻转 / 面重映射 / 法线取反 / 贴图覆盖
 * 都已实测无效，全部删除。
 */
public final class Settings {
    private Settings() {
    }

    /** 几何的绘制方式；这套贴图的 alpha 只有 0/255，CUTOUT 才是正确解 */
    public enum Mode {
        /** Alpha 剪裁 + 写深度，双面（不剔除背面，兼容镜像面） */
        CUTOUT,
        /** Alpha 剪裁 + 写深度 + 剔除背面 */
        CUTOUT_CULL,
        /** 半透明混合、不写深度；会和后面的层叠在一起，只在 CUTOUT 出问题时用 */
        TRANSLUCENT,
        /** 半透明混合 + 剔除背面 */
        TRANSLUCENT_CULL
    }

    /** 贴图采样方式。这套 UV 图每面只有 1~2 像素，必须 NEAREST 才不会混进透明黑 */
    public enum Filter {
        NEAREST,
        LINEAR
    }

    /**
     * 诊断：模型由 left / right 两套镜像几何叠成，检查它们是不是一黑一彩。
     * BOTH = 正常绘制。
     */
    public enum Half {
        BOTH,
        LEFT_ONLY,
        RIGHT_ONLY
    }

    /**
     * 面朝向调试：只绘制指定 {@link net.minecraft.core.Direction} 的面，并给这批面输出纯色。
     * <p>
     * 选中某个方向时，UV 会被钉在贴图里一个**纯白像素**上（见 {@link #WHITE_U}/{@link #WHITE_V}），
     * 这样即使那块贴图是黑的，画面上也能看到纯色，用来判断这个方向的面到底在刀身的哪一侧。
     */
    public enum FaceDebug {
        NONE(-1, 0xFFFFFFFF),
        /** 红 */
        EAST(net.minecraft.core.Direction.EAST.ordinal(), 0xFFFF3030),
        /** 绿 */
        WEST(net.minecraft.core.Direction.WEST.ordinal(), 0xFF30FF30),
        /** 蓝 */
        UP(net.minecraft.core.Direction.UP.ordinal(), 0xFF4040FF),
        /** 黄 */
        DOWN(net.minecraft.core.Direction.DOWN.ordinal(), 0xFFFFFF30),
        /** 青 */
        NORTH(net.minecraft.core.Direction.NORTH.ordinal(), 0xFF30FFFF),
        /** 品红 */
        SOUTH(net.minecraft.core.Direction.SOUTH.ordinal(), 0xFFFF30FF);

        /** observer_uv.png 里坐标 (153,194) 的像素，3x3 邻域全是纯白 (255,255,255,255) */
        public static final float WHITE_U = 0.599609F;
        public static final float WHITE_V = 0.759766F;

        public final int faceId;
        public final int color;

        FaceDebug(int faceId, int color) {
            this.faceId = faceId;
            this.color = color;
        }

        /** 当前要过滤的方向（Direction.ordinal），-1 表示不过滤 */
        public static int selectedFace() {
            return faceDebug.faceId;
        }

        public static int colorOf(int faceId) {
            for (FaceDebug value : values()) {
                if (value.faceId == faceId) {
                    return value.color;
                }
            }
            return 0xFFFFFFFF;
        }
    }

    public static Mode mode = Mode.CUTOUT;
    public static Filter filter = Filter.NEAREST;
    public static Half half = Half.BOTH;
    public static FaceDebug faceDebug = FaceDebug.NONE;
    /** 是否提交武器几何（关掉只剩手臂，用于确认画面上的刀确实是我们画的） */
    public static boolean submitGeo = true;
    public static boolean renderArms = true;
    /** true = 第一人称完全交回 Punchy 渲染（TACZ 状态机不生效） */
    public static boolean punchyRender = false;
    /** 每秒打印一条实际渲染状态 */
    public static boolean logState = true;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("punchytacz.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.isRegularFile(f)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
            mode = readEnum(Mode.class, p.getProperty("mode"), mode);
            filter = readEnum(Filter.class, p.getProperty("filter"), filter);
            half = readEnum(Half.class, p.getProperty("half"), half);
            faceDebug = readEnum(FaceDebug.class, p.getProperty("faceDebug"), faceDebug);
            submitGeo = Boolean.parseBoolean(p.getProperty("submitGeo", String.valueOf(submitGeo)));
            renderArms = Boolean.parseBoolean(p.getProperty("renderArms", String.valueOf(renderArms)));
            punchyRender = Boolean.parseBoolean(p.getProperty("punchyRender", String.valueOf(punchyRender)));
            logState = Boolean.parseBoolean(p.getProperty("logState", String.valueOf(logState)));
        } catch (Throwable ignored) {
            // 配置损坏时保持默认值，不影响启动
        }
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("mode", mode.name());
        p.setProperty("filter", filter.name());
        p.setProperty("half", half.name());
        p.setProperty("faceDebug", faceDebug.name());
        p.setProperty("submitGeo", String.valueOf(submitGeo));
        p.setProperty("renderArms", String.valueOf(renderArms));
        p.setProperty("punchyRender", String.valueOf(punchyRender));
        p.setProperty("logState", String.valueOf(logState));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "PunchyTACZ render settings");
        } catch (Throwable ignored) {
        }
    }
}
