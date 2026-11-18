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

    public static Mode mode = Mode.CUTOUT;
    public static Filter filter = Filter.NEAREST;
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
