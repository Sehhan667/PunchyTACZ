package com.punchytacz;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.punchytacz.anim.AnimationController;
import com.punchytacz.anim.BedrockAnimationLoader;
import com.punchytacz.anim.BedrockListeners;
import com.punchytacz.anim.ObjectAnim;
import com.punchytacz.anim.TaczBuiltInAnimations;
import com.punchytacz.bedrock.BedrockModel;
import com.punchytacz.statemachine.MeleeAnimationStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 扫描资源包中 Punchy 格式的动画文件，读取其中的 "tacz" 声明：
 *
 * <pre>
 * "tacz": {
 *   "geo": "wt:geo_models/melee/observer_geo.json",
 *   "animation": "wt:animations/melee/observer.animation.json",
 *   "texture": "wt:textures/melee/observer_uv.png",
 *   "clips": { "observer_melee_1": "melee_1" }
 * }
 * </pre>
 */
public final class TaczPacks {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");

    public record Rig(Identifier geo, Identifier animation, Identifier texture) {
    }

    public record Entry(Rig rig, String taczClip) {
    }

    /** 每只手独立的模型 + 动画控制器 + 状态机 */
    public static final class Runtime {
        public final BedrockModel model;
        public final AnimationController controller;
        public final MeleeAnimationStateMachine stateMachine;
        public String lastClip;
        public long lastPlayback = -1L;
        public long lastDrawnNs;
        public boolean hooksInstalled;

        private Runtime(BedrockModel model, AnimationController controller, MeleeAnimationStateMachine stateMachine) {
            this.model = model;
            this.controller = controller;
            this.stateMachine = stateMachine;
        }
    }

    private static final Map<String, Map<String, Entry>> CLIPS_BY_PACK = new LinkedHashMap<>();
    private static final Map<String, Entry> ALL_CLIPS = new LinkedHashMap<>();
    private static final Set<String> TACZ_PACK_OPTIONS = new LinkedHashSet<>();
    private static final Map<Identifier, List<ObjectAnim>> ANIMS = new HashMap<>();
    private static final Map<String, Runtime> RUNTIMES = new HashMap<>();

    private static ResourceManager lastManager;
    private static boolean dirty = true;

    private TaczPacks() {
    }

    public static void invalidate() {
        dirty = true;
    }

    /** 资源重载完成后立即重建缓存，避免第一帧在渲染线程扫描资源包 */
    public static void reload() {
        dirty = true;
        ensure();
    }

    /**
     * 按 Punchy 选中的包 + clip 名取映射。
     * Punchy 的包选项形如 {@code pack:file/punchy刀包移植}，见 {@link #packOptionOf(String)}。
     */
    public static Entry find(String packOption, String punchyClip) {
        ensure();
        if (packOption == null || punchyClip == null) {
            return null;
        }
        Map<String, Entry> clips = CLIPS_BY_PACK.get(packOption);
        return clips == null ? null : clips.get(punchyClip);
    }

    /** 不区分包名的回退查询，仅用于诊断/兼容旧 clip 判断 */
    public static Entry findAny(String punchyClip) {
        ensure();
        return punchyClip == null ? null : ALL_CLIPS.get(punchyClip);
    }

    /** 该 Punchy 包选项是否声明了 tacz 块（即应由本模组接管渲染） */
    public static boolean isTaczPackOption(String option) {
        ensure();
        return option != null && TACZ_PACK_OPTIONS.contains(option);
    }

    /**
     * 把资源包 id / Punchy 选择值统一成 Punchy 的选项 id。
     * 规则对齐 PunchyTuningConfig.normalizeMixOption：
     * 内置包 → pack:punchy:punchy，原版/mod_resources → punchy_default，其余 → pack:<小写 id>。
     */
    public static String packOptionOf(String packId) {
        if (packId == null) {
            return null;
        }
        String id = packId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) {
            return null;
        }
        if (id.startsWith("pack:")) {
            id = id.substring("pack:".length());
        }
        if (id.equals("punchy") || id.startsWith("punchy:") || id.endsWith(":punchy")
                || id.endsWith("/punchy") || id.endsWith("\\punchy")
                || id.equals("builtin/punchy") || id.equals("builtin\\punchy")) {
            return "pack:punchy:punchy";
        }
        if (id.equals("vanilla") || id.equals("mod_resources") || id.equals("punchy_default")) {
            return "punchy_default";
        }
        return "pack:" + id;
    }

    /** 按 rig + 手取运行时；每只手一个独立模型，动画原型共享 */
    public static Runtime runtime(Rig rig, boolean leftArm) {
        ensure();
        if (rig == null) {
            return null;
        }
        String key = rig.geo() + "#" + rig.animation() + "#" + leftArm;
        Runtime cached = RUNTIMES.get(key);
        if (cached != null) {
            return cached;
        }
        BedrockModel model = parseGeo(rig.geo());
        List<ObjectAnim> prototypes = anims(rig.animation());
        if (model == null || prototypes.isEmpty()) {
            return null;
        }
        AnimationController controller = new AnimationController(prototypes, BedrockListeners.supplier(model));
        // 包内定义优先；状态机引用的 idle/run_hold/walk_* 等名字由 TACZ 内置动画兜底
        TaczBuiltInAnimations.applyFallbacks(controller, Minecraft.getInstance().getResourceManager());
        MeleeAnimationStateMachine stateMachine = new MeleeAnimationStateMachine(controller);
        Runtime runtime = new Runtime(model, controller, stateMachine);
        RUNTIMES.put(key, runtime);
        return runtime;
    }

    private static BedrockModel parseGeo(Identifier id) {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm == null) {
            return null;
        }
        try {
            Resource resource = rm.getResource(id).orElse(null);
            if (resource == null) {
                LOG.warn("缺少几何体 {}", id);
                return null;
            }
            try (Reader reader = resource.openAsReader()) {
                return BedrockModel.parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (Exception e) {
            LOG.error("解析几何体失败 {}", id, e);
            return null;
        }
    }

    private static List<ObjectAnim> anims(Identifier id) {
        ensure();
        List<ObjectAnim> cached = ANIMS.get(id);
        if (cached != null) {
            return cached;
        }
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm == null) {
            return List.of();
        }
        try {
            Resource resource = rm.getResource(id).orElse(null);
            if (resource == null) {
                LOG.warn("缺少动画 {}", id);
                return List.of();
            }
            try (Reader reader = resource.openAsReader()) {
                List<ObjectAnim> prototypes = BedrockAnimationLoader.parse(JsonParser.parseReader(reader).getAsJsonObject());
                ANIMS.put(id, prototypes);
                return prototypes;
            }
        } catch (Exception e) {
            LOG.error("解析动画失败 {}", id, e);
            return List.of();
        }
    }

    private static void ensure() {
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm == null) {
            return;
        }
        if (!dirty && rm == lastManager) {
            return;
        }
        lastManager = rm;
        dirty = false;
        CLIPS_BY_PACK.clear();
        ALL_CLIPS.clear();
        TACZ_PACK_OPTIONS.clear();
        ANIMS.clear();
        RUNTIMES.clear();
        scan(rm);
    }

    private static void scan(ResourceManager rm) {
        Map<Identifier, Resource> files;
        try {
            files = rm.listResources("punchy/animations", id -> id.getPath().endsWith(".json"));
        } catch (Exception e) {
            LOG.error("扫描 punchy/animations 失败", e);
            return;
        }
        for (Map.Entry<Identifier, Resource> entry : files.entrySet()) {
            JsonObject root;
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                root = parsed.getAsJsonObject();
            } catch (Exception e) {
                LOG.warn("读取动画文件失败 {}", entry.getKey(), e);
                continue;
            }
            String packOption = packOptionOf(entry.getValue().sourcePackId());
            if (packOption != null) {
                readFile(root, packOption);
            }
        }
        LOG.info("punchytacz: 已登记 {} 个 TACZ 包，{} 个 clip", TACZ_PACK_OPTIONS.size(), ALL_CLIPS.size());
    }

    private static void readFile(JsonObject root, String packOption) {
        JsonObject tacz = root.has("tacz") && root.get("tacz").isJsonObject()
                ? root.getAsJsonObject("tacz") : null;

        Rig rig = null;
        if (tacz != null && tacz.has("geo") && tacz.has("animation") && tacz.has("texture")) {
            try {
                rig = new Rig(
                        Identifier.parse(tacz.get("geo").getAsString()),
                        Identifier.parse(tacz.get("animation").getAsString()),
                        Identifier.parse(tacz.get("texture").getAsString()));
            } catch (Exception e) {
                LOG.warn("tacz 资源路径无效", e);
            }
        }

        JsonObject clipsJson = root.has("animations") && root.get("animations").isJsonObject()
                ? root.getAsJsonObject("animations") : null;
        if (clipsJson == null) {
            return;
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        if (tacz != null && tacz.has("clips") && tacz.get("clips").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : tacz.getAsJsonObject("clips").entrySet()) {
                mapping.put(e.getKey(), e.getValue().getAsString());
            }
        }
        for (Map.Entry<String, JsonElement> e : clipsJson.entrySet()) {
            if (!mapping.containsKey(e.getKey()) && e.getValue().isJsonObject()) {
                JsonObject clip = e.getValue().getAsJsonObject();
                if (clip.has("tacz") && clip.get("tacz").isJsonPrimitive()) {
                    mapping.put(e.getKey(), clip.get("tacz").getAsString());
                }
            }
        }
        if (rig == null) {
            return;
        }
        Map<String, Entry> clips = CLIPS_BY_PACK.computeIfAbsent(packOption, key -> new LinkedHashMap<>());
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            Entry entry = new Entry(rig, e.getValue());
            clips.put(e.getKey(), entry);
            ALL_CLIPS.put(e.getKey(), entry);
        }
        TACZ_PACK_OPTIONS.add(packOption);
    }
}
