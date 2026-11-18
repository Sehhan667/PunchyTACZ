package com.punchytacz;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 运行时可改的渲染参数。
 * <p>
 * 全部是静态字段，渲染每帧直接读：改完点保存（或关闭配置界面）立刻生效，**不需要重启游戏**。
 * 目的是把"刀身发黑 / 深度冲突"的所有可疑变量一次放进界面里逐个试。
 */
public final class Settings {
    private Settings() {
    }

    /** 26.3 里可用的 entity 系 RenderType（覆盖"写不写深度 / 混不混合"的各种组合） */
    public enum Mode {
        CUTOUT,
        CUTOUT_CULL,
        TRANSLUCENT,
        TRANSLUCENT_CULL,
        SOLID,
        TRANSLUCENT_EMISSIVE,
        /** 自建 RenderType：单面剪裁（ENTITY_CUTOUT_CULL + 自带贴图绑定） */
        CUSTOM_CULL,
        /** 自建 RenderType：半透明 + 上传排序，验证阶段分发是否影响结果 */
        CUSTOM_TRANSLUCENT
    }

    public static Mode mode = Mode.TRANSLUCENT;
    /** 运行时 UV 翻转（在顶点写出时生效，不需要重新解析模型） */
    public static boolean flipU = false;
    public static boolean flipV = false;
    /** 运行时 UV 平移，单位 = 贴图 UV 单位（0.5 = 半个 2px 条带），用来在明暗条带之间跳 */
    public static float offsetU = 0.0F;
    public static float offsetV = 0.0F;
    /** 是否提交武器几何；关掉可以确认画面上那层是谁画的 */
    public static boolean submitGeo = true;
    /** 是否画手臂 */
    public static boolean renderArms = true;
    /** true = 第一人称交回 Punchy 渲染（TACZ 状态机不生效） */
    public static boolean punchyRender = false;
    /** 每秒打印一条实际渲染状态（blend / cull / writeDepth） */
    public static boolean logState = true;
    /** 贴图覆盖：非空则用它替代包内声明的贴图，例如 wt:melee/observer_uv_s */
    public static String textureOverride = "";

    /**
     * 面 → UV 条目的重映射。用户实测：板子的外表面拿到内表面的 UV（外面黑、内面正常），
     * 说明查 UV 的方向错了，这里直接对调验证。
     */
    public enum FaceMap {
        NONE,
        UD,
        NS,
        EW,
        UD_NS,
        UD_NS_EW
    }

    public static FaceMap faceMap = FaceMap.NONE;

    /** 法线取反：26.3 的 entity 管线带 PER_FACE_LIGHTING，法线朝里就会被判成背面、用 -light 压暗 */
    public static boolean flipNormal = false;

    public static net.minecraft.core.Direction mapFace(net.minecraft.core.Direction d) {
        boolean ud = faceMap == FaceMap.UD || faceMap == FaceMap.UD_NS || faceMap == FaceMap.UD_NS_EW;
        boolean ns = faceMap == FaceMap.NS || faceMap == FaceMap.UD_NS || faceMap == FaceMap.UD_NS_EW;
        boolean ew = faceMap == FaceMap.EW || faceMap == FaceMap.UD_NS_EW;
        if (ud && d == net.minecraft.core.Direction.UP) return net.minecraft.core.Direction.DOWN;
        if (ud && d == net.minecraft.core.Direction.DOWN) return net.minecraft.core.Direction.UP;
        if (ns && d == net.minecraft.core.Direction.NORTH) return net.minecraft.core.Direction.SOUTH;
        if (ns && d == net.minecraft.core.Direction.SOUTH) return net.minecraft.core.Direction.NORTH;
        if (ew && d == net.minecraft.core.Direction.EAST) return net.minecraft.core.Direction.WEST;
        if (ew && d == net.minecraft.core.Direction.WEST) return net.minecraft.core.Direction.EAST;
        return d;
    }

    /** observer 的 left / right 是同一份几何的镜像两份，用来判断是不是"画了两份"导致的黑层 */
    public enum Side { BOTH, LEFT_ONLY, RIGHT_ONLY }

    public static Side side = Side.BOTH;

    public static boolean allowBone(String name) {
        return switch (side) {
            case BOTH -> true;
            case LEFT_ONLY -> !"right".equals(name);
            case RIGHT_ONLY -> !"left".equals(name);
        };
    }

    /** 实际要渲染的贴图（可被 textureOverride 覆盖） */
    public static Identifier textureFor(Identifier base) {
        if (textureOverride == null || textureOverride.isBlank()) {
            return base;
        }
        Identifier id = Identifier.tryParse(textureOverride.trim());
        return id == null ? base : id;
    }

    private static RenderType cached;
    private static String cachedKey = "";

    public static RenderType renderTypeFor(Identifier texture) {
        switch (mode) {
            case CUTOUT: return RenderTypes.entityCutout(texture);
            case CUTOUT_CULL: return RenderTypes.entityCutoutCull(texture);
            case TRANSLUCENT: return RenderTypes.entityTranslucent(texture);
            case TRANSLUCENT_CULL: return RenderTypes.entityTranslucentCull(texture);
            case SOLID: return RenderTypes.entitySolid(texture);
            case TRANSLUCENT_EMISSIVE: return RenderTypes.entityTranslucentEmissive(texture);
            case CUSTOM_CULL, CUSTOM_TRANSLUCENT: {
                String key = mode.name() + "|" + texture;
                if (cached != null && key.equals(cachedKey)) {
                    return cached;
                }
                RenderType made = buildCustom(mode, texture);
                if (made != null) {
                    cached = made;
                    cachedKey = key;
                    return made;
                }
                return RenderTypes.entityCutoutCull(texture);
            }
            default:
                return RenderTypes.entityCutout(texture);
        }
    }

    /**
     * 自建 RenderType：显式指定 pipeline（含 cull / 深度 / 混合）与贴图绑定，
     * 用来验证“我们传给 submitCustomGeometry 的 RenderType 到底有没有生效”。
     * RenderType.create 是包私有的，这里用反射调。
     */
    private static RenderType buildCustom(Mode which, Identifier texture) {
        try {
            var pipeline = which == Mode.CUSTOM_CULL
                    ? net.minecraft.client.renderer.RenderPipelines.ENTITY_CUTOUT_CULL
                    : net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT;
            var setup = net.minecraft.client.renderer.rendertype.RenderSetup
                    .builder(pipeline)
                    .withTexture("Sampler0", texture)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup();
            var m = RenderType.class.getDeclaredMethod("create", String.class,
                    net.minecraft.client.renderer.rendertype.RenderSetup.class);
            m.setAccessible(true);
            return (RenderType) m.invoke(null, "punchytacz_" + which.name().toLowerCase(), setup);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("punchytacz")
                    .warn("punchytacz: 自建 RenderType 失败: " + t);
            return null;
        }
    }

    /** 把 RenderType 的实际渲染状态压成一行，用于判断"参数到底吃不吃" */
    public static String describe(RenderType rt) {
        try {
            var pipeline = rt.pipeline();
            var depth = pipeline.getDepthStencilState();
            return "mode=" + mode
                    + " blend=" + rt.hasBlending()
                    + " cull=" + pipeline.isCull()
                    + " writeDepth=" + (depth != null && depth.writeDepth());
        } catch (Throwable t) {
            return "mode=" + mode + " describe失败:" + t;
        }
    }

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
            mode = Mode.valueOf(p.getProperty("mode", mode.name()));
            flipU = Boolean.parseBoolean(p.getProperty("flipU", String.valueOf(flipU)));
            flipV = Boolean.parseBoolean(p.getProperty("flipV", String.valueOf(flipV)));
            offsetU = Float.parseFloat(p.getProperty("offsetU", String.valueOf(offsetU)));
            offsetV = Float.parseFloat(p.getProperty("offsetV", String.valueOf(offsetV)));
            submitGeo = Boolean.parseBoolean(p.getProperty("submitGeo", String.valueOf(submitGeo)));
            renderArms = Boolean.parseBoolean(p.getProperty("renderArms", String.valueOf(renderArms)));
            punchyRender = Boolean.parseBoolean(p.getProperty("punchyRender", String.valueOf(punchyRender)));
            logState = Boolean.parseBoolean(p.getProperty("logState", String.valueOf(logState)));
            textureOverride = p.getProperty("textureOverride", textureOverride);
            side = Side.valueOf(p.getProperty("side", side.name()));
            faceMap = FaceMap.valueOf(p.getProperty("faceMap", faceMap.name()));
            flipNormal = Boolean.parseBoolean(p.getProperty("flipNormal", String.valueOf(flipNormal)));
        } catch (Throwable ignored) {
            // 配置损坏时保持默认值，不影响启动
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("mode", mode.name());
        p.setProperty("flipU", String.valueOf(flipU));
        p.setProperty("flipV", String.valueOf(flipV));
        p.setProperty("offsetU", String.valueOf(offsetU));
        p.setProperty("offsetV", String.valueOf(offsetV));
        p.setProperty("submitGeo", String.valueOf(submitGeo));
        p.setProperty("renderArms", String.valueOf(renderArms));
        p.setProperty("punchyRender", String.valueOf(punchyRender));
        p.setProperty("logState", String.valueOf(logState));
        p.setProperty("textureOverride", textureOverride == null ? "" : textureOverride);
        p.setProperty("side", side.name());
        p.setProperty("faceMap", faceMap.name());
        p.setProperty("flipNormal", String.valueOf(flipNormal));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "PunchyTACZ render debug switches");
        } catch (Throwable ignored) {
        }
    }
}
