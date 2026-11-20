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
import com.punchytacz.statemachine.MeleeAnimationStateContext;
import com.punchytacz.statemachine.MeleeAnimationStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 武器注册表：**直接吃原始 TACZ 刀包**，不需要任何自家声明文件。
 *
 * <p>扫描所有资源包里的 {@code assets/<ns>/display/melee/*.json}，按 TACZ 的路径约定
 * 在同一套资源里解析出几何 / 动画 / 贴图：
 *
 * <pre>
 * display.model     "wt:melee/observer_geo"  → assets/wt/geo_models/melee/observer_geo.json
 * display.animation "wt:melee/observer"      → assets/wt/animations/melee/observer.animation.json
 * display.texture   "wt:melee/observer_uv"   → assets/wt/textures/melee/observer_uv.png
 * </pre>
 *
 * <p>显示名按 TACZ 惯例取 lang key {@code item.<ns>.<文件名>}（原始包的语言文件里都有）。
 *
 * <p>原始包不声明「替换哪些物品」，所以武器和物品的对应关系完全存在
 * {@link TaczSelection}（config）里，由改枪屏指定。
 *
 * <p>武器包不含 Punchy compat，所以装多少个都不会往 Punchy 的包列表里多出选项。
 * Punchy 那边只有本模组内置资源包提供的唯一一个宿主选项。
 */
public final class TaczPacks {
    private static final Logger LOG = LoggerFactory.getLogger("punchytacz");

    public record Rig(Identifier geo, Identifier animation, Identifier texture) {
    }

    /** 一件从原始 TACZ 包自动发现出来的武器 */
    public record Weapon(String id, String name, String packId, Rig rig) {
    }

    /** 每只手独立的模型 + 动画控制器 + 状态机 */
    public static final class Runtime {
        public final BedrockModel model;
        public final AnimationController controller;
        public final com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext> stateMachine;
        public String lastClip;
        public long lastPlayback = -1L;
        public long lastDrawnNs;
        /** 当前正在播放的「Punchy 独有状态」动画名，null 表示没有 */
        public String lastStateAnim;
        /** 已经在改枪屏里做过一次"停掉所有轨道"的处理 */
        public boolean showcaseHeld;
        /** 下一帧重建状态机（重播拔刀） */
        public boolean replayDraw;
        /** 上一帧的 Punchy clip 是不是拔刀（用于去抖） */
        public boolean lastClipWasDraw;
        public long lastDrawReplayNs;
        /** 上一次见到的物品 key（用于自己检测“换出武器” → 重播拔刀，不依赖 Punchy 信号） */
        public String lastSeenItem;
        /** 自己数连击段数：0/1 = 前两段，2 = 第三段 */
        public int comboIndex;
        /** 上一帧 player.swinging（取上升沿） */
        public boolean wasSwinging;
        /** 上一次攻击的时间戳，用于连击超时重置 */
        public long lastAttackNs;
        /**
         * 上一次发送移动输入的 tick。
         * <p>
         * lua 状态机里的 {@code re}（连击超时计数）是**按游戏 tick** 递增的
         * （37 tick ≈ 1.85 秒），而 {@code renderRig} 是**渲染帧**（60+ FPS）驱动。
         * 如果在渲染帧里每帧 trigger 一次，{@code re} 会以 3 倍速增长 ——
         * 37 次只要 0.6 秒，正好卡在剑的攻击冷却上，于是每刀之前都被判超时、
         * attack_index 归零，永远只播 melee_1。
         */
        public long lastMoveTick = Long.MIN_VALUE;
        /**
         * 本 rig 播出去的音效实例 → 播放时刻。
         * 切走时逐个 {@code SoundManager.stop(instance)}，否则检视音效会继续播完。
         * 超过 5 秒的条目自然清理（没有 isPlaying 接口）。
         */
        /** 音效实例 → 来源动画名。主轨道换成别的动画就停，同动画内不打断 */
        public final java.util.LinkedHashMap<net.minecraft.client.resources.sounds.SoundInstance, String>
                activeSounds = new java.util.LinkedHashMap<>();

        private Runtime(BedrockModel model, AnimationController controller,
                        com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext> stateMachine) {
            this.model = model;
            this.controller = controller;
            this.stateMachine = stateMachine;
        }
    }

    private static final List<Weapon> WEAPONS = new ArrayList<>();
    private static final Map<String, Runtime> RUNTIMES = new HashMap<>();
    private static final Map<Identifier, java.util.Set<String>> PACK_ANIMS = new HashMap<>();
    /**
     * Punchy 的槽位 id 有两种（{@code MixSlotType}）：
     * <ul>
     *   <li>{@code kind:sword} —— 按工具类别（{@code ToolKind.name().toLowerCase()}）</li>
     *   <li>{@code item:minecraft:iron_sword} —— 按具体物品</li>
     * </ul>
     * 之前拿 {@code slot.equalsIgnoreCase("SWORD")} 去比，永远是 false，
     * 于是「槽位同步」一次都没改过任何东西，也就解释了两个老问题：
     * 没分配武器的物品仍被切到宿主包、以及手感上「刀皮逻辑诡异」。
     */
    static String kindOfSlot(String slot) {
        if (slot == null || !slot.startsWith("kind:")) {
            return null;
        }
        return slot.substring("kind:".length()).toUpperCase(Locale.ROOT);
    }

    /** 这件物品对应的 specific 槽位 id（{@code item:<ns>:<path>}），拿不到返回 null */
    static String specificSlotFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : "item:" + id;
    }

    /** 宿主 compat 覆盖的 kind（和内置 compat 保持一致） */
    private static final java.util.Set<String> HOST_KINDS = java.util.Set.of(
            "SWORD", "AXE", "PICKAXE", "HOE", "SHOVEL", "MACE", "SPEAR", "TRIDENT");

    /** 宿主包的 Punchy 选项 id（内置资源包提供），null 表示没找到 */
    private static String hostOption;

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

    public static List<Weapon> weapons() {
        ensure();
        return List.copyOf(WEAPONS);
    }

    public static Weapon byId(String id) {
        ensure();
        if (id == null) {
            return null;
        }
        for (Weapon weapon : WEAPONS) {
            if (weapon.id().equals(id)) {
                return weapon;
            }
        }
        return null;
    }

    /**
     * 把 Punchy 的槽位选择跟本模组的分配对齐：
     * <p>
     * 宿主 compat 覆盖了 8 个 kind，Punchy 会把这些槽位都当成"有 TACZ 选项"，
     * 某些情况下会默认选中宿主包 —— 结果没有任何刀包的物品（镐子、锄头…）也被切到 TACZ。
     * 这里对**没有分配武器的槽位**把选择退回该槽位的第一个非宿主选项。
     * <p>
     * 只在资源重载后跑一次，且只在「当前选中的是宿主包」时才动手，不会覆盖用户的有效选择。
     */
    public static void syncPunchySelection() {
        ensure();
        if (hostOption == null) {
            return;
        }
        try {
            java.util.List<String> slots = punchy.config.PunchyTuningConfig.getMixPackSlotIds();
            boolean anyItemOverride = TaczSelection.keys().stream().anyMatch(k -> k.startsWith("item:"));
            StringBuilder dump = new StringBuilder();
            for (String slot : slots) {
                String selected = punchy.config.PunchyTuningConfig.getMixPackSelectedOption(slot);
                boolean host = isHostOption(packOptionOf(selected));
                String kindName = kindOfSlot(slot);
                dump.append(slot).append('=').append(selected)
                        .append(host ? "(host)" : "").append(' ');
                // 只处理 kind 槽位；item 槽位单独看（下面按 assigned 判断）
                if (kindName == null || !HOST_KINDS.contains(kindName)) {
                    continue;
                }
                // 「仅此物品」也是有效分配：只要有 item: 级覆盖，就说明用户手头有东西在用 TACZ，
                // 此时不能把槽位退回原生包，否则那件物品的定制会失效。
                boolean assigned = TaczSelection.get("kind:" + kindName) != null || anyItemOverride;
                if (!host || assigned) {
                    continue;
                }
                java.util.List<String> options =
                        punchy.config.PunchyTuningConfig.getMixPackSlotOptionIds(slot);
                for (String option : options) {
                    if (!isHostOption(option)) {
                        punchy.config.PunchyTuningConfig.setMixPackSelection(slot, option);
                        LOG.info("punchytacz: 槽位 {} 没有分配武器，从宿主包退回 {}", slot, option);
                        break;
                    }
                }
            }
            LOG.info("punchytacz: Punchy 槽位 = {}", dump.toString().trim());
        } catch (Throwable t) {
            LOG.warn("punchytacz: 同步 Punchy 槽位选择失败", t);
        }
    }

    /**
     * 优先用刀包**自带的 lua 状态机**（TACZ 同款）。
     * <p>
     * 之前是把 lua 手工翻译成 Java，翻译失真造成了一连串问题（连击只播第一段、
     * attack_right 播错动画、走路时按攻击无效……）。现在直接执行原脚本：
     * 轨道分配、连击计数、形态随机、过渡时长全部由作者写的那份 lua 决定。
     * 包里没有 lua 时才回退到 Java 版。
     */
    private static com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext>
            createStateMachine(Rig rig, AnimationController controller) {
        Identifier scriptId = scriptFor(rig);
        org.luaj.vm2.LuaTable script = com.punchytacz.lua.LuaScript.get(scriptId);
        if (script != null) {
            try {
                var stateMachine = new com.punchytacz.lua.LuaStateMachineFactory<MeleeAnimationStateContext>()
                        .setController(controller)
                        .setLuaScripts(script)
                        .build();
                LOG.info("punchytacz: 使用包内 lua 状态机 {}", scriptId);
                return stateMachine;
            } catch (Throwable t) {
                LOG.warn("punchytacz: lua 状态机构建失败 {}，回退 Java 版", scriptId, t);
            }
        }
        LOG.info("punchytacz: {} 没有 state_machine.lua，回退 Java 状态机", rig.animation());
        return new MeleeAnimationStateMachine(controller);
    }

    /** 脚本约定：和动画文件同名的 {@code assets/<ns>/scripts/<name>_state_machine.lua} */
    private static Identifier scriptFor(Rig rig) {
        String path = rig.animation().getPath();
        String base = path.substring(path.lastIndexOf('/') + 1);
        for (String suffix : new String[] {".animation.json", ".json"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return Identifier.parse(rig.animation().getNamespace()
                + ":scripts/" + base + "_state_machine.lua");
    }

    /**
     * 预热：把 config 里已经指定过的武器提前解析成 Runtime（模型/动画/状态机）。
     * 否则第一次掏出这把刀时会在渲染线程上解析 100KB 级的 geo + 动画，表现为"切刀卡一下"。
     */
    public static void warmSelected() {
        ensure();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (String key : TaczSelection.keys()) {
            String value = TaczSelection.get(key);
            if (value != null) {
                ids.add(value);
            }
        }
        for (String id : ids) {
            Weapon weapon = byId(id);
            if (weapon == null) {
                continue;
            }
            runtime(weapon, false);
            runtime(weapon, true);
            LOG.info("punchytacz: 预热武器 {}", id);
        }
    }

    /** 按 config 里记的「物品覆盖 → kind 覆盖」解析；没指定返回 null（= 交回原版） */
    public static Weapon resolve(ItemStack stack) {
        ensure();
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Weapon chosen = byId(TaczSelection.get(TaczSelection.itemKey(stack)));
        if (chosen == null) {
            chosen = byId(TaczSelection.get(TaczSelection.kindKey(kindOf(stack))));
        }
        return chosen;
    }

    /** 渲染用：除了 config，还允许改枪屏在「尚未指定」时用预览项，保证屏内看得到模型 */
    public static Weapon resolveForRender(ItemStack stack) {
        // 屏内一律以「正在预览的那把」为准：箭头切的是预览，不是已生效的选择。
        // 否则已分配过武器的物品按箭头换不了模型。
        if (TaczRefitScreen.isOpen()) {
            Weapon preview = TaczRefitScreen.previewWeapon();
            if (preview != null) {
                return preview;
            }
        }
        return resolve(stack);
    }

    /** Punchy 的物品 kind；读不到时返回 "UNKNOWN" */
    public static String kindOf(ItemStack stack) {
        try {
            String kind = punchy.api.PunchyApi.kindOf(stack);
            return kind == null ? "UNKNOWN" : kind;
        } catch (Throwable t) {
            return "UNKNOWN";
        }
    }

    /** 宿主包对应的 Punchy 选项（选中它 = 由本模组渲染） */
    public static String hostOption() {
        ensure();
        return hostOption;
    }

    public static boolean isHostOption(String option) {
        ensure();
        if (option == null || hostOption == null) {
            return false;
        }
        if (option.equals(hostOption)) {
            return true;
        }
        // 容错：Punchy 对内置资源包的写法可能是 fabric/<id> 或别的包装，只比较包名部分
        return normalizePackId(option).equals(normalizePackId(hostOption));
    }

    private static String normalizePackId(String option) {
        String value = option.toLowerCase(Locale.ROOT);
        if (value.startsWith("pack:")) {
            value = value.substring("pack:".length());
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        return value;
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

    /** 某个 rig 的动画文件里**自己定义**的名字（用于区分内置兜底） */
    public static java.util.Set<String> packAnimationNames(Rig rig) {
        ensure();
        if (rig == null) {
            return java.util.Set.of();
        }
        return PACK_ANIMS.computeIfAbsent(rig.animation(), id -> {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (ObjectAnim anim : anims(id)) {
                if (anim != null && anim.name != null) {
                    names.add(anim.name);
                }
            }
            return java.util.Set.copyOf(names);
        });
    }

    /** 按武器 + 手取运行时；每只手一个独立模型，动画原型共享 */
    public static Runtime runtime(Weapon weapon, boolean leftArm) {
        ensure();
        if (weapon == null) {
            return null;
        }
        Rig rig = weapon.rig();
        // 只处理主手（对齐 TACZ：一个 display 一个状态机），key 不再区分左右手
        String key = rig.geo() + "#" + rig.animation();
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
        // 包内定义优先；状态机引用的 idle 等名字由 TACZ 内置动画兜底
        // **不要**注册枪械内置兜底（walk_forward / walk_backward / walk_sideway / run_hold）：
        // 刀包的 lua 会请求这些名字，但包内没有对应资源，于是会落到 TACZ 给**枪**写的那几条上。
        // 那几条只动 root（滚动 6°~12°、run_hold 还有 -36° 俯仰和大位移），套到近战 rig 上
        // 就是"一走路手就飞走"。宁可缺动画保持 static_idle，也不能借枪的动作。
        com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext> stateMachine =
                createStateMachine(rig, controller);
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
                return BedrockAnimationLoader.parse(JsonParser.parseReader(reader).getAsJsonObject());
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
        WEAPONS.clear();
        RUNTIMES.clear();
        hostOption = null;
        scanWeapons(rm);
        scanHost(rm);
        LOG.info("punchytacz: 已登记 {} 把武器，宿主选项 = {}", WEAPONS.size(), hostOption);
    }

    private static void scanWeapons(ResourceManager rm) {
        Map<Identifier, List<Resource>> files;
        try {
            files = rm.listResourceStacks("display",
                    id -> id.getPath().contains("display/melee/") && id.getPath().endsWith(".json"));
        } catch (Exception e) {
            LOG.error("扫描 display/melee 失败", e);
            return;
        }
        List<Weapon> found = new ArrayList<>();
        for (Map.Entry<Identifier, List<Resource>> entry : files.entrySet()) {
            // 同一个文件可能被多个资源包各自提供，逐个读取
            for (Resource resource : entry.getValue()) {
                Weapon weapon = readWeapon(rm, entry.getKey(), resource);
                if (weapon != null) {
                    found.add(weapon);
                }
            }
        }
        found.sort(Comparator.comparing(Weapon::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Weapon::id));
        for (Weapon weapon : found) {
            String id = weapon.id();
            int n = 2;
            while (byIdInternal(id) != null) {
                id = weapon.id() + "#" + n++;
            }
            WEAPONS.add(new Weapon(id, weapon.name(), weapon.packId(), weapon.rig()));
        }
    }

    private static Weapon byIdInternal(String id) {
        for (Weapon weapon : WEAPONS) {
            if (weapon.id().equals(id)) {
                return weapon;
            }
        }
        return null;
    }

    private static Weapon readWeapon(ResourceManager rm, Identifier file, Resource resource) {
        JsonObject root;
        try (Reader reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return null;
            }
            root = parsed.getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("读取 display 失败 {}", file, e);
            return null;
        }
        String model = str(root, "model");
        String animation = str(root, "animation");
        String texture = str(root, "texture");
        if (model == null || animation == null || texture == null) {
            return null;
        }
        Identifier geoId = resolvePath(model, "geo_models", ".json");
        Identifier animId = resolvePath(animation, "animations", ".animation.json");
        Identifier texId = resolvePath(texture, "textures", ".png");
        if (geoId == null || animId == null || texId == null) {
            return null;
        }
        if (rm.getResource(geoId).isEmpty() || rm.getResource(animId).isEmpty() || rm.getResource(texId).isEmpty()) {
            LOG.warn("武器资源不全，跳过 {}（geo={} anim={} tex={}）", file, geoId, animId, texId);
            return null;
        }
        // wt:display/melee/observer.json → wt:melee/observer（和包内 model/animation 的写法一致）
        String raw = file.getPath();
        int cut = raw.indexOf("display/melee/");
        if (cut < 0) {
            return null;
        }
        String idPath = "melee/" + raw.substring(cut + "display/melee/".length());
        String id = file.getNamespace() + ":" + idPath.substring(0, idPath.length() - 5);
        String base = idPath.substring(idPath.lastIndexOf('/') + 1);
        if (base.endsWith(".json")) {
            base = base.substring(0, base.length() - 5);
        }
        String name = displayName(file.getNamespace(), base);
        return new Weapon(id, name, resource.sourcePackId(), new Rig(geoId, animId, texId));
    }

    private static String str(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()) {
            return null;
        }
        String value = root.get(key).getAsString();
        return value.isBlank() ? null : value;
    }

    /** 把 TACZ 的 {@code ns:path} 解析成真实资源位置（同包内），失败返回 null */
    private static Identifier resolvePath(String raw, String folder, String suffix) {
        String ns = "minecraft";
        String path = raw.trim();
        int colon = path.indexOf(':');
        if (colon >= 0) {
            ns = path.substring(0, colon);
            path = path.substring(colon + 1);
        }
        for (String known : new String[]{".animation.json", ".json", ".png"}) {
            if (path.endsWith(known)) {
                path = path.substring(0, path.length() - known.length());
                break;
            }
        }
        try {
            return Identifier.fromNamespaceAndPath(ns, folder + "/" + path + suffix);
        } catch (Exception e) {
            return null;
        }
    }

    /** TACZ 惯例：显示名是 lang key {@code item.<ns>.<文件名>} */
    private static String displayName(String namespace, String base) {
        String key = "item." + namespace + "." + base;
        try {
            net.minecraft.locale.Language language = net.minecraft.locale.Language.getInstance();
            if (language.has(key)) {
                return language.getOrDefault(key);
            }
        } catch (Throwable ignored) {
            // lang 还没加载好就退回文件名
        }
        return base;
    }

    /** 宿主包靠「clip 名里带 tacz_ 前缀」识别，不写死资源包名 */
    private static void scanHost(ResourceManager rm) {
        Map<Identifier, Resource> files;
        try {
            files = rm.listResources("punchy/animations", id -> id.getPath().endsWith(".json"));
        } catch (Exception e) {
            LOG.error("扫描 punchy/animations 失败", e);
            return;
        }
        for (Map.Entry<Identifier, Resource> entry : files.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) {
                    continue;
                }
                JsonObject root = parsed.getAsJsonObject();
                if (!root.has("animations") || !root.get("animations").isJsonObject()) {
                    continue;
                }
                if (root.getAsJsonObject("animations").has("tacz_melee_1")) {
                    hostOption = packOptionOf(entry.getValue().sourcePackId());
                    return;
                }
            } catch (Exception e) {
                LOG.warn("读取动画文件失败 {}", entry.getKey(), e);
            }
        }
    }
}
