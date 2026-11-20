package com.punchytacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.punchytacz.bedrock.BedrockModel;
import com.punchytacz.bedrock.BedrockPart;
import com.punchytacz.diag.SubmitProbe;
import com.punchytacz.statemachine.MeleeAnimationStateContext;
import com.punchytacz.statemachine.MeleeAnimationStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import punchy.api.PunchyApi;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 用 TACZ 的基岩模型与动画状态机接管 Punchy 的持刀第一人称渲染 */
public final class TaczRenderer {
    private TaczRenderer() {
    }

    /** 左右手当前占用的运行时，用于切刀/放下时干净地退出状态机 */
    private static TaczPacks.Runtime activeRight;
    private static TaczPacks.Runtime activeLeft;

    /** 本帧 Punchy 回调登记的动作（只用于读取 clip 名与隐藏物品，不参与渲染） */
    private static PunchyApi.Clip pendingRightClip;
    private static PunchyApi.Clip pendingLeftClip;
    private static TaczPacks.Weapon pendingRightWeapon;
    private static TaczPacks.Weapon pendingLeftWeapon;
    private static Object pendingRightStack;
    private static Object pendingLeftStack;
    private static String pendingRightOption;
    private static String pendingLeftOption;
    private static int pendingRightLight = -1;
    private static int pendingLeftLight = -1;

    /** 当前帧哪只手由 TACZ rig 接管（hideArms 用） */
    private static boolean taczOwnsRight;
    private static boolean taczOwnsLeft;
    /** draw() 是否接管了这只手（每帧由 Punchy 的回调刷新，比 renderFrame 的时序可靠） */
    private static boolean owningRight;
    private static boolean owningLeft;

    /** 物品 → 上次确认过的 entry（idle/run 时 Punchy 不给 clip，用它常驻渲染） */
    private static final Map<String, TaczPacks.Weapon> LAST_BY_ITEM = new HashMap<>();
    /** Punchy 的包解析比较贵（每次 200~400µs），按物品缓存 100ms，去掉每帧 4 次的重复调用 */
    private static final Map<String, String> OPTION_CACHE = new HashMap<>();
    private static final Map<String, Long> OPTION_CACHE_NS = new HashMap<>();
    private static final long OPTION_CACHE_TTL_NS = 100_000_000L;

    /** 帧序号与本帧已渲染标记，保证一套 rig 每帧只画一次 */
    private static long frameId;
    private static boolean renderedThisFrame;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("punchytacz");
    private static long lastLogNs;
    private static long lastDrawLogNs;
    private static long lastHideLogNs;
    private static long lastStateLogNs;
    private static long lastRigLogNs;
    private static String lastBounds = "-";
    /** 最近一次由我们接管的物品 key；用于吞掉切刀过渡帧里的旧物品 */
    private static String lastOwnedItemKey;
    /** 状态机累计重建次数：每帧重建会让 lua 的 attack_index 一直归零（只播第一段） */
    private static int reinitCount;
    private static long drawCalls;

    /** 改枪屏的"武器飞入/飞出"进度 0..1（对应 TACZ RefitTransform 的 0.25s 过渡） */
    private static float refitProgress;
    private static long refitLastNs;
    private static final float REFIT_TRANSITION_SECONDS = 0.25F;
    /** 摆放参数按武器缓存一次；每帧重算会因为 idle 姿态变化而抖动 */
    private static String refitKey;
    private static final org.joml.Vector3f REFIT_CENTER = new org.joml.Vector3f();
    private static float refitBaseScale = 0.62F;

    private static void logThrottled(long lastNs, java.util.function.LongConsumer setter, String msg) {
        long now = System.nanoTime();
        if (now - lastNs > 1_000_000_000L) {
            setter.accept(now);
            LOG.info(msg);
        }
    }

    /**
     * 由 FirstPersonHandsAndItemsRenderer.submitHandsWithItems 的 HEAD 调用，每帧一次。
     * <p>
     * 必须在这里渲染：Punchy 接管时会把整个 {@code submitHandsWithItems} 取消，
     * 原版后续的 {@code submitArmWithItem} 根本不会执行（日志已证实）。
     * 这里的 PoseStack 是相机空间，且尚未叠加 {@code *0.1} 的视角旋转。
     */
    public static void renderFrame(PoseStack poseStack, SubmitNodeCollector sink, int light, float partialTick) {
        if (Settings.punchyRender) {
            return;
        }
        // 【诊断】每帧统计 submit / 几何回调执行 / drawItem 回调次数（详见 SubmitProbe）
        SubmitProbe.enabled = Settings.logState;
        SubmitProbe.beginFrame();
        frameId++;
        advanceRefitProgress();
        // 本帧没渲染武器时把镜头动画清零，否则最后一帧的旋转会一直粘在相机上
        CAMERA_ANIM.identity();
        expireStale(activeRight);
        expireStale(activeLeft);
        renderedThisFrame = false;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // 与原版 TACZ 对齐：**只处理主手**。副手不建 rig、不接管、不隐藏，
        // 完全交回 Punchy 用它自己的模型和动画（TACZ 也只渲染手里的那一把枪）。
        net.minecraft.world.item.ItemStack mainStack = player.getMainHandItem();
        String mainOption = selectedPackOption(mainStack);
        // 宿主包 = 开，其它任何包 = 关（手和物品整个交回 Punchy）。
        // 改枪屏开着时进入"预览"：无论选的是哪个包都由我们渲染。
        boolean preview = TaczRefitScreen.isOpen();
        boolean mainTacz = preview || TaczPacks.isHostOption(mainOption);
        TaczPacks.Weapon mainWeapon = mainTacz ? TaczPacks.resolveForRender(mainStack) : null;
        taczOwnsRight = mainWeapon != null;
        taczOwnsLeft = false;
        if (mainWeapon == null) {
            clearHand(false);
            clearHand(true);
            return;
        }

        PunchyApi.Clip clip = PunchyApi.state().clipOn(false);
        TaczPacks.Weapon weapon = mainWeapon;
        boolean leftArm = false;
        LAST_BY_ITEM.put(itemKey(mainStack), weapon);
        renderedThisFrame = renderRig(weapon, clip, false, sink, light, partialTick, poseStack);
        if (Settings.logState) {
            logThrottled(lastRigLogNs, v -> lastRigLogNs = v,
                    "punchytacz: rig weapon=" + weapon.id() + " name=" + weapon.name()
                            + " hand=" + (leftArm ? "left" : "right")
                            + " refit=" + TaczRefitScreen.isOpen() + "/" + refitProgress
                            + " bounds=" + lastBounds);
        }
        if (Settings.logState) {
            logThrottled(lastStateLogNs, v -> lastStateLogNs = v,
                    "punchytacz: frame#" + frameId + " clip=" + clipName(clip)
                            + " hand=" + (leftArm ? "left" : "right") + " rendered=" + renderedThisFrame
                            + " rt=" + Settings.mode + "/" + Settings.filter
                            + " half=" + Settings.half + " face=" + Settings.faceDebug
                            + " flip=" + Settings.flip + " swapEW=" + Settings.swapEastWest
                            + " pipe=" + Settings.pipeline + "/cull=" + Settings.cull
                            + " submit=" + Settings.submitGeo);
        }
    }

    private static String clipName(PunchyApi.Clip clip) {
        return clip == null ? "idle(fallback)" : clip.name();
    }

    private static String itemKey(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * 由 FirstPersonHandsAndItemsRenderer.submitArmWithItem 的 HEAD 调用。
     * 正常情况下走不到（Punchy 已取消整个 pass）；若能走到，只负责取消原版手部渲染。
     */
    public static boolean shouldCancelVanillaHand() {
        return renderedThisFrame;
    }

    private static boolean pendingStillHeld(LocalPlayer player, boolean leftArm, Object stack) {
        if (!(stack instanceof net.minecraft.world.item.ItemStack pending)) {
            return false;
        }
        net.minecraft.world.item.ItemStack current = leftArm
                ? player.getOffhandItem()
                : player.getMainHandItem();
        return net.minecraft.world.item.ItemStack.isSameItemSameComponents(pending, current);
    }

    /** 读 Punchy 菜单里这只手当前物品选中的动画包（已归一化成 pack:... 形式） */
    private static String selectedPackOption(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String key = itemKey(stack);
        String cached = OPTION_CACHE.get(key);
        Long stamp = OPTION_CACHE_NS.get(key);
        long now = System.nanoTime();
        if (cached != null && stamp != null && now - stamp < OPTION_CACHE_TTL_NS) {
            return cached;
        }
        try {
            String resolved = TaczPacks.packOptionOf(
                    punchy.config.PunchyTuningConfig.getMixPreferredOptionForStack(stack));
            OPTION_CACHE.put(key, resolved);
            OPTION_CACHE_NS.put(key, now);
            return resolved;
        } catch (Throwable t) {
            logThrottled(lastLogNs, v -> lastLogNs = v,
                    "punchytacz: 读取 Punchy 包选择失败：" + t);
            return null;
        }
    }

    private static void clearPending(boolean leftArm) {
        if (leftArm) {
            pendingLeftClip = null;
            pendingLeftWeapon = null;
            pendingLeftStack = null;
            pendingLeftOption = null;
            pendingLeftLight = -1;
        } else {
            pendingRightClip = null;
            pendingRightWeapon = null;
            pendingRightStack = null;
            pendingRightOption = null;
            pendingRightLight = -1;
        }
    }

    /** 立即停掉某只手的 rig（不等待 0.5s 超时），用于包切换 */
    private static void clearHand(boolean leftArm) {
        clearPending(leftArm);
        if (leftArm) {
            if (activeLeft != null) {
                exitRuntime(activeLeft);
                activeLeft = null;
            }
        } else if (activeRight != null) {
            exitRuntime(activeRight);
            activeRight = null;
        }
    }

    public static boolean draw(PunchyApi.ItemDraw draw) {
        SubmitProbe.drawCall();
        if (Settings.punchyRender) {
            // 交回 Punchy：它用自己的 geo + display + clip 画
            return false;
        }
        if (!(draw.stack() instanceof net.minecraft.world.item.ItemStack stack)) {
            return false;
        }
        boolean leftArm = draw.leftArm();
        if (leftArm) {
            // 与原版 TACZ 对齐：只接管主手，副手全交给 Punchy
            return false;
        }
        // 切刀过渡期 Punchy 会拿**上一件**物品再来问一次。如果这时按"不是宿主包"处理，
        // 就会 clearHand → 下一帧新物品重建 → 重新 initialize（旧版每次随机 draw/draw_1）
        // → 画面上就是「切刀第一帧抽好几下」。所以先确认它问的就是当前手持。
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null) {
            ItemStack current = leftArm ? localPlayer.getOffhandItem() : localPlayer.getMainHandItem();
            if (!ItemStack.isSameItemSameComponents(stack, current)) {
                // Punchy 在切刀时拿**上一件**物品再问一次。如果交回 false，
                // 它会把那件物品的 Punchy 原生形态画一帧（切走瞬间闪一下）。
                // 是我们刚接管的旧物品 → 返回 true 但什么都不画，把它吞掉。
                if (itemKey(stack).equals(lastOwnedItemKey)) {
                    return true;
                }
                return false;
            }
        }
        String option = selectedPackOption(stack);
        if (!TaczPacks.isHostOption(option) && !TaczRefitScreen.isOpen()) {
            // 这只手选中的不是宿主包 → 关。物品交回 Punchy 用它自己的（原版）模型和动画画。
            clearHand(leftArm);
            if (leftArm) {
                taczOwnsLeft = false;
                owningLeft = false;
            } else {
                taczOwnsRight = false;
                owningRight = false;
            }
            return false;
        }
        TaczPacks.Weapon weapon = TaczPacks.resolveForRender(stack);
        if (weapon == null) {
            // 宿主包开着，但没有任何武器声明接管这件物品：同样交回去，避免出现隐形物品
            clearHand(leftArm);
            if (leftArm) {
                owningLeft = false;
            } else {
                owningRight = false;
            }
            return false;
        }
        PunchyApi.Clip clip = PunchyApi.state().clipOn(leftArm);
        // 游泳 / 交互 / 攀爬这些只有 Punchy 有动画的状态：**继续由我们渲染**，
        // 这样物品和手臂都不会回落到 Punchy（否则会出现原版模型 + 它的手臂）。
        // 具体播什么由 renderRig 里的 states 映射决定，没有声明就保持当前动画不动。
        LAST_BY_ITEM.put(itemKey(stack), weapon);
        if (leftArm) {
            pendingLeftClip = clip;
            pendingLeftWeapon = weapon;
            pendingLeftStack = stack;
            pendingLeftOption = option;
            pendingLeftLight = draw.light();
            taczOwnsLeft = true;
            owningLeft = true;
        } else {
            pendingRightClip = clip;
            pendingRightWeapon = weapon;
            pendingRightStack = stack;
            pendingRightOption = option;
            pendingRightLight = draw.light();
            taczOwnsRight = true;
            owningRight = true;
        }
        lastOwnedItemKey = itemKey(stack);
        drawCalls++;
        if (Settings.logState) {
            logThrottled(lastDrawLogNs, v -> lastDrawLogNs = v,
                    "punchytacz: draw hand=" + (leftArm ? "L" : "R") + " clip=" + clipName(clip)
                            + " option=" + option + " light=" + draw.light() + " calls=" + drawCalls);
        }
        // 和原版 TACZ 一致：renderFirstPerson 里 event.setCanceled(true)，武器完全由 geo 接管，
        // 不再叠原版物品模型。
        return true;
    }

    /** 资源重载/设置变化后丢弃旧运行时引用 */
    public static void resetActive() {
        OPTION_CACHE.clear();
        OPTION_CACHE_NS.clear();
        clearHand(false);
        clearHand(true);
        taczOwnsRight = false;
        taczOwnsLeft = false;
        ItemRenderType.clear();
    }

    /** 相机动画（camera 骨骼），供 Camera mixin 读取 */
    public static final org.joml.Quaternionf CAMERA_ANIM = new org.joml.Quaternionf();

    /** TACZ 的 applyLevelCameraAnimation：把四元数拆成 yaw/pitch/roll（度） */
    public static float[] cameraEuler() {
        org.joml.Quaternionf q = CAMERA_ANIM;
        double yaw = Math.asin(2 * (q.w() * q.y() - q.x() * q.z()));
        double pitch = Math.atan2(2 * (q.w() * q.x() + q.y() * q.z()), 1 - 2 * (q.x() * q.x() + q.y() * q.y()));
        double roll = Math.atan2(2 * (q.w() * q.z() + q.x() * q.y()), 1 - 2 * (q.y() * q.y() + q.z() * q.z()));
        return new float[]{(float) Math.toDegrees(yaw), (float) Math.toDegrees(pitch), (float) Math.toDegrees(roll)};
    }

    private static boolean renderRig(TaczPacks.Weapon weapon, PunchyApi.Clip clip, boolean leftArm,
                                     SubmitNodeCollector sink, int light, float partialTick, PoseStack baseStack) {
        TaczPacks.Runtime runtime = TaczPacks.runtime(weapon, leftArm);
        LocalPlayer player = Minecraft.getInstance().player;
        if (runtime == null || player == null) {
            return false;
        }
        // Punchy 物品回调带的光照比 avatarRenderState 更贴近它自己的绘制路径
        int pending = leftArm ? pendingLeftLight : pendingRightLight;
        final int effectiveLight = pending < 0 ? light : pending;

        // 复制基准，避免污染调用方（原版手部 pass）的 PoseStack
        PoseStack stack = new PoseStack();
        stack.mulPose(baseStack.last().pose());
        TaczPacks.Runtime previous = leftArm ? activeLeft : activeRight;
        if (previous != null && previous != runtime) {
            exitRuntime(previous);
        }
        if (leftArm) {
            activeLeft = runtime;
        } else {
            activeRight = runtime;
        }
        runtime.lastDrawnNs = System.nanoTime();

        com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext> stateMachine = runtime.stateMachine;
        if (!stateMachine.isInitialized()) {
            // 重新拔刀：清掉上一轮的轨道与残留姿态，再播放 draw
            runtime.controller.clear();
            runtime.model.cleanAnimationTransform();
            runtime.model.cameraRotation.identity();
            MeleeAnimationStateContext context = new MeleeAnimationStateContext();
            context.setPackAnimations(TaczPacks.packAnimationNames(weapon.rig()));
            stateMachine.setContext(context);
            if (stateMachine instanceof MeleeAnimationStateMachine javaSm) {
                javaSm.beginDraw();
            }
            stateMachine.initialize();
        }
        stateMachine.processContextIfExist(context -> context.setPartialTicks(partialTick));

        boolean showcase = TaczRefitScreen.isOpen();
        boolean punchyOnly = isPunchyOnlyState(clip == null ? null : clip.name());
        if (showcase && !runtime.showcaseHeld) {
            // 刚进改枪屏：把正在播的 run / walk / 攻击**真的停掉**。
            // 只 trigger(IDLE) 不够 —— LOOP 轨道不会因此停下，它的 root 位移会把模型带偏，
            // 表现为"界面里刀还在动、位置也不对"。
            runtime.showcaseHeld = true;
            MeleeAnimationStateContext ctx = stateMachine.getContext();
            if (ctx != null) {
                ctx.stopAnimation(ctx.getTrack(MeleeAnimationStateMachine.STATIC_TRACK_LINE,
                        MeleeAnimationStateMachine.MAIN_TRACK));
                ctx.stopAnimation(ctx.getTrack(MeleeAnimationStateMachine.BLENDING_TRACK_LINE,
                        MeleeAnimationStateMachine.MOVEMENT_TRACK));
            }
        } else if (!showcase) {
            runtime.showcaseHeld = false;
        }
        if (showcase) {
            // 改枪屏：屏内玩家本来就不能移动，这里只发 idle，让动作轨道停住；
            // 换武器靠重建 rig 触发 draw，所以**不**暂停动画（要能看见拔刀）。
            runtime.lastStateAnim = null;
            stateMachine.trigger(MeleeAnimationStateMachine.INPUT_IDLE);
        } else if (punchyOnly) {
            // Punchy 独有状态（游泳 / 交互 / 攀爬…）：有声明就播声明的动画，
            // 没声明就什么都不做 —— 不发任何输入，正在播的动画自然继续。
            applyStateAnimation(runtime, weapon, clip.name());
        } else {
            if (runtime.lastStateAnim != null) {
                // 退出 Punchy 独有状态：把动作轨道还回去，让状态机重新接管
                runtime.lastStateAnim = null;
                stateMachine.processContextIfExist(context ->
                        context.stopAnimation(context.getTrack(MeleeAnimationStateMachine.BLENDING_TRACK_LINE,
                                MeleeAnimationStateMachine.MOVEMENT_TRACK)));
                stateMachine.trigger(MeleeAnimationStateMachine.INPUT_IDLE);
            }
            triggerForClip(runtime, clip);
            detectSelfInputs(runtime, leftArm, player);
            triggerMovement(runtime, player);
        }
        if (runtime.replayDraw) {
            runtime.replayDraw = false;
            // setContext() 要求状态机未初始化：必须先 exit()，否则
            // IllegalStateException: State machine is already initialized（切刀崩溃就是这个）
            if (stateMachine.isInitialized()) {
                stateMachine.exit();
            }
            runtime.controller.clear();
            runtime.model.cleanAnimationTransform();
            runtime.model.cameraRotation.identity();
            MeleeAnimationStateContext fresh = new MeleeAnimationStateContext();
            fresh.setPackAnimations(TaczPacks.packAnimationNames(weapon.rig()));
            stateMachine.setContext(fresh);
            if (stateMachine instanceof MeleeAnimationStateMachine javaSm) {
                javaSm.beginDraw();
            }
            stateMachine.initialize();
        }
        // 清除上一帧的姿态再更新（TACZ 每帧渲染后清理，这里放在更新前，避免多次提交时互相覆盖）
        runtime.model.cleanAnimationTransform();
        runtime.model.cameraRotation.identity();
        stateMachine.update();
        CAMERA_ANIM.set(runtime.model.cameraRotation);
        playAnimSounds(runtime);

        BedrockModel model = runtime.model;
        if (!showcase) {
            // TACZ renderFirstPerson：先做视角摇摆
            float xRotOffset = Mth.lerp(partialTick, player.xBobO, player.xBob);
            float yRotOffset = Mth.lerp(partialTick, player.yBobO, player.yBob);
            final float xRot = player.getViewXRot(partialTick) - xRotOffset;
            final float yRot = player.getViewYRot(partialTick) - yRotOffset;

            // TACZ 在状态机更新后、渲染前给 root 骨骼叠加视角摆动
            BedrockPart rootPart = model.parts.get("root");
            if (rootPart != null) {
                float swayX = (float) Math.tanh(xRot / 25F) * 25F;
                float swayY = (float) Math.tanh(yRot / 25F) * 25F;
                rootPart.offsetX += swayY * 0.1F / 16F / 3F;
                rootPart.offsetY += -swayX * 0.1F / 16F / 3F;
                rootPart.additionalQuaternion.mul(Axis.XP.rotationDegrees(swayX * 0.05F));
                rootPart.additionalQuaternion.mul(Axis.YP.rotationDegrees(swayY * 0.05F));
            }

            // TACZ applyItemInHandCameraAnimation：camera 骨骼旋转叠加到手部空间（受“视角摇晃”设置控制）
            if (bobView()) {
                stack.mulPose(new Matrix4f().rotation(model.cameraRotation));
            }
        }
        model.cameraRotation.identity();
        // TACZ AnimateGeoItemRenderer.renderFirstPerson：
        // 基岩模型以 (0,24,0) 为渲染原点，先抬到模型原点，再翻转上下，
        // 然后用 idle_view 定位组的反相把模型锚到相机原点。
        stack.translate(0F, 1.5F, 0F);
        // 基岩模型上下翻转（TACZ 原本就是 Axis.ZP 180，见 AnimateGeoItemRenderer.renderFirstPerson）。
        // 这一步同时也是绕模型长轴滚 180°，会顺带对调刀的正反面，所以做成可切换用于诊断。
        switch (Settings.flip) {
            case ZP180 -> stack.rotateDegrees(Axis.ZP, 180F);
            case YP180 -> stack.rotateDegrees(Axis.YP, 180F);
            case NONE -> {
            }
        }
        float refitAlpha = 1.0F - (1.0F - refitProgress) * (1.0F - refitProgress);  // easeOut
        applyViewInverse(stack, model, refitAlpha);
        if (showcase || refitting()) {
            // 滚轮缩放（TACZ 没有，保留为额外操作，不影响定位）
            float z = 1.0F + (TaczRefitScreen.zoom() - 1.0F) * refitAlpha;
            if (Math.abs(z - 1.0F) > 0.0001F) {
                stack.scale(z, z, z);
            }
        }

        // 手臂必须在模型几何体提交之前、用同一套骨骼姿态同步提交；
        // 不能在 submitCustomGeometry 的回调里再提交节点（flush 过程中追加节点会导致位置/朝向错乱）。
        // 改枪屏里只留武器本身：我们的手臂也不画（Punchy 的手臂由 hideArms 挡掉）。
        // 飞入/飞出过程中同上，避免手臂在菜单里突兀地悬着。
        if (Settings.renderArms && !showcase) {
            renderArms(stack, model, sink, effectiveLight, player);
        }
        if (Settings.submitGeo) {
            Identifier texture = weapon.rig().texture();
            SubmitProbe.ours = true;
            try {
                sink.submitCustomGeometry(stack, ItemRenderType.of(texture), (pose, consumer) -> {
                    // 每次绘制阶段真正执行几何回调都会自增：一帧 >1 就说明同一条提交被画了两次
                    SubmitProbe.ourDraw();
                    PoseStack local = new PoseStack();
                    local.mulPose(pose.pose());
                    // 0xFFFFFFFF = 不额外染色；贴图 alpha 只有 0/255，CUTOUT 下就是硬剪裁
                    model.render(local, consumer, effectiveLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF,
                            name -> !isSkinBone(name) && allowHalf(name));
                });
            } finally {
                SubmitProbe.ours = false;
            }
        }
        return true;
    }

    /**
     * 播放本帧跨过的动画音效（音效表来自动画文件的 {@code sound_effects}）。
     * <p>
     * 之前这套音效从不播放（移植时删掉了声音通道），实际听到的是 Punchy 自己 clip 的音效
     * ——它没有 angel 变体。现在按**最终播放的动画名**取音效表（annihil 的 angel 变体
     * 会用 {@code inspect_angel} 的表），配置里可关。
     */
    private static void playAnimSounds(TaczPacks.Runtime runtime) {
        var requests = runtime.controller.consumeSoundRequests();
        if (!Settings.playAnimSounds || Minecraft.getInstance().player == null) {
            return;
        }
        var soundManager = Minecraft.getInstance().getSoundManager();
        // 音效打断规则（照 TACZ 的事件模型 + 用户确认的语义）：
        //  1) 已自然播完（isActive == false）→ 只清引用；
        //  2) 来源动画仍是某条轨道上"当前生效"的动画 → 不停。同一条动画的多个音效
        //     互不打断；自然播完的 runner 仍停在轨道上，所以尾部音效能播完；
        //  3) 来源动画被替换（连击换段、检视被打断、切走）→ 立即停。
        // 每条请求都自带产生它的动画名（AnimationController.SoundRequest），
        // 不再用"主轨道当前动画"猜来源——那个值在过渡窗口里是旧动画，
        // 之前每条时间线的第一个音效就是这么被错标、被掐掉的。
        java.util.Set<String> live = runtime.controller.liveAnimationNames();
        runtime.activeSounds.entrySet().removeIf(e -> {
            if (!soundManager.isActive(e.getKey())) {
                return true;
            }
            if (live.contains(e.getValue())) {
                return false;
            }
            soundManager.stop(e.getKey());
            return true;
        });
        for (var request : requests) {
            Identifier id = Identifier.tryParse(request.effect());
            if (id == null) {
                continue;
            }
            // PLAYERS 类别 + 跟随玩家 + 并发上限，全部在 WeaponSoundSystem 里
            var instance = com.punchytacz.sound.WeaponSoundSystem.play(id, 1.0F, 1.0F);
            if (instance != null) {
                runtime.activeSounds.put(instance, request.animation());
            }
        }
    }

    /**
     * Punchy 独有状态（游泳 / 交互 / 攀爬…）的动画接管。
     * <p>
     * 武器声明里 {@code "states": {"swiming": "swim"}} 这样写；键是 Punchy 的 clip 名
     * （支持前缀匹配），值是包内动画名。放在**动作轨道**上，退出该状态时会自然被
     * 走/跑/idle 覆盖，不需要额外的收尾逻辑。
     */
    private static void applyStateAnimation(TaczPacks.Runtime runtime, TaczPacks.Weapon weapon, String clipName) {
        String anim = TaczSelection.stateAnimFor(weapon.id(), clipName);
        if (anim == null || anim.equals(runtime.lastStateAnim)) {
            return;
        }
        runtime.lastStateAnim = anim;
        runtime.stateMachine.processContextIfExist(context ->
                context.runAnimation(anim,
                        context.getTrack(MeleeAnimationStateMachine.BLENDING_TRACK_LINE,
                                MeleeAnimationStateMachine.MOVEMENT_TRACK),
                        // 覆盖式：配置里写的是完整姿势的动画名，叠加会和 base 相加
                        false, com.punchytacz.anim.ObjectAnim.PlayType.LOOP, 0.2F));
    }

    /** 宿主包 compat 定义的通用 clip 名 */
    private static boolean isHostClip(String name) {
        return name != null && name.startsWith("tacz_");
    }

    /**
     * Punchy 会整手接管姿态的全局状态（游泳 / 攀爬 / 鞘翅 / 爬行 / 骑乘）。
     * <p>
     * 只有这些状态下才"不发输入、不打断当前动画"；其余 clip（hand_in_sword、idle、
     * attack 等）都是 Punchy 正常的动作，应该照常由我们的状态机接管。
     * 之前判的是「名字不是 tacz_ 开头」，compat 改成等价默认声明后，
     * 这个条件对所有 clip 都成立，等于把状态机完全冻死。
     */
    private static boolean isPunchyOnlyState(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("swim") || n.startsWith("climb") || n.startsWith("elytra")
                || n.startsWith("crawl") || n.startsWith("ride") || n.startsWith("boat")
                || n.startsWith("ladder") || n.startsWith("fall_flying");
    }

    /** 检视键被按下，等下一次渲染时转给当前手的运行时 */
    private static volatile boolean inspectRequested;

    /** 外部按键调用：请求播一次检视 */
    public static void requestInspect() {
        inspectRequested = true;
        if (Settings.logState) {
            LOG.info("punchytacz: 检视键按下");
        }
    }

    /**
     * 自己检测「换出武器」和「攻击」。
     * <p>
     * 宿主 compat 现在只声明 hand_in / hand_out（值等价于 Punchy 默认），
     * 不再提供 {@code tacz_draw} / {@code tacz_melee_N} 这类信号 —— 否则会把
     * Punchy 自己的起手动画顶成空 clip（Punchy 模式下切刀动画丢失就是这么来的）。
     * 所以这两件事改成我们自己在渲染时判断。
     */
    private static void detectSelfInputs(TaczPacks.Runtime runtime, boolean leftArm, LocalPlayer player) {
        if (player == null) {
            return;
        }
        ItemStack held = leftArm ? player.getOffhandItem() : player.getMainHandItem();
        String key = itemKey(held);
        if (runtime.lastSeenItem == null) {
            runtime.lastSeenItem = key;
        } else if (!key.equals(runtime.lastSeenItem)) {
            // 换出了另一件物品 → 重播一次拔刀
            runtime.lastSeenItem = key;
            runtime.replayDraw = true;
            runtime.wasSwinging = player.isSwinging();
        }

        if (inspectRequested) {
            inspectRequested = false;
            // lua 状态机里 inspect 由 main 轨道的 idle 态处理（三条轨道并行，
            // 走路时 main 依然在 idle，所以走/跑也能检视）；Java 版直接播。
            if (runtime.stateMachine instanceof MeleeAnimationStateMachine javaSm) {
                javaSm.playInspect();
            } else {
                runtime.stateMachine.trigger(MeleeAnimationStateMachine.INPUT_INSPECT);
            }
        }

        // 攻击：swinging 的上升沿；挖方块时 swinging 也在跳，用 gameMode.isDestroying() 排除
        boolean destroying = false;
        try {
            var mode = Minecraft.getInstance().gameMode;
            destroying = mode != null && mode.isDestroying();
        } catch (Throwable ignored) {
            // 拿不到就退化成“只要挥动就算攻击”
        }
        boolean swinging = player.isSwinging() && !destroying;
        if (swinging && !runtime.wasSwinging) {
            // 连击超时由状态机自己按 lua 的 re/37/41 帧逻辑处理，这里只管发输入
            runtime.stateMachine.trigger(MeleeAnimationStateMachine.INPUT_ATTACK_LEFT);
        }
        runtime.wasSwinging = swinging;
    }

    /** 把 Punchy 的 clip 变化映射成状态机的一次性输入 */    private static void triggerForClip(TaczPacks.Runtime runtime, PunchyApi.Clip clip) {
        if (clip == null) {
            return;
        }
        String name = clip.name();
        long playback = clip.playback();
        if (name.equals(runtime.lastClip) && playback == runtime.lastPlayback) {
            return;
        }
        runtime.lastClip = name;
        runtime.lastPlayback = playback;

        com.punchytacz.statemachine.AnimationStateMachine<MeleeAnimationStateContext> stateMachine = runtime.stateMachine;
        if (name.contains("_melee_")) {
            int index = meleeIndex(name);
            if (index == 3) {
                stateMachine.trigger(MeleeAnimationStateMachine.INPUT_ATTACK_RIGHT);
            } else if (index > 0) {
                // 让 lua 的 (count % 2 + 1) 恰好等于当前连击序号
                stateMachine.processContextIfExist(context -> context.setAttackLeftCount(index - 1));
                stateMachine.trigger(MeleeAnimationStateMachine.INPUT_ATTACK_LEFT);
                stateMachine.processContextIfExist(
                        context -> context.setAttackLeftCount(context.getAttackLeftCount() + 1));
            }
            return;
        }
        // 拔刀改由 detectSelfInputs 按「物品变化」触发，不再依赖 _draw clip 名：
        // 宿主 compat 若声明 hand_in，会把 Punchy 自己的起手动画顶掉。
        // 检视**不再**靠 clip 名判断：宿主 compat 把 inspect 指到空 clip 后，Punchy 的
        // clip 会反复在 tacz_inspect / idle 之间跳，每跳一次就发一次 INSPECT，
        // 而 lua 的 INSPECT 分支会把 attack_index 和 re 一起归零（连击永远第一段）。
        // 现在改成监听 Punchy 的检视键（key.punchy.inspect）上升沿。
    }

    private static int meleeIndex(String name) {
        int start = name.indexOf("_melee_");
        if (start < 0) {
            return 0;
        }
        int i = start + "_melee_".length();
        int value = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            value = value * 10 + (name.charAt(i) - '0');
            i++;
        }
        return value;
    }

    /**
     * 与 TACZ 的 {@code TickAnimationEvent} 一致：**每个游戏 tick** 按玩家状态发一次移动输入。
     * <p>
     * 不能每个渲染帧都发：lua 里的 {@code re} 是按 tick 数到 37 的，
     * 按 60FPS 发送会让它在 0.6 秒就到阈值，把连击链一直打断（只播 melee_1）。
     */
    private static void triggerMovement(TaczPacks.Runtime runtime, LocalPlayer player) {
        long tick = player.tickCount;
        if (tick == runtime.lastMoveTick) {
            return;
        }
        runtime.lastMoveTick = tick;
        String input;
        if (!player.isMovingSlowly() && player.isSprinting()) {
            input = MeleeAnimationStateMachine.INPUT_RUN;
        } else if (!player.isMovingSlowly() && hasMovementInput()) {
            input = MeleeAnimationStateMachine.INPUT_WALK;
        } else {
            input = MeleeAnimationStateMachine.INPUT_IDLE;
        }
        runtime.stateMachine.trigger(input);
    }

    private static boolean hasMovementInput() {
        var options = Minecraft.getInstance().options;
        return options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown();
    }

    private static boolean bobView() {
        return Minecraft.getInstance().options.bobView().get();
    }

    /** 当前手不再渲染 TACZ 武器时，退出状态机，下次拔出会重新播 draw */
    private static void expireStale(TaczPacks.Runtime runtime) {
        if (runtime == null) {
            return;
        }
        // 持仓切换时 Punchy 的 clip 可能短暂为空，延迟半秒再退出，避免频繁重播 draw
        if (System.nanoTime() - runtime.lastDrawnNs < 500_000_000L) {
            return;
        }
        if (activeRight == runtime) {
            activeRight = null;
        }
        if (activeLeft == runtime) {
            activeLeft = null;
        }
        exitRuntime(runtime);
    }

    private static void exitRuntime(TaczPacks.Runtime runtime) {
        // 停掉本 rig 播出去但还没结束的音效（统一走 WeaponSoundSystem）
        com.punchytacz.sound.WeaponSoundSystem.stopAll(runtime.activeSounds.keySet());
        runtime.activeSounds.clear();
        if (runtime.stateMachine.isInitialized()) {
            runtime.stateMachine.exit();
        }
        runtime.controller.clear();
        runtime.model.cleanAnimationTransform();
        runtime.model.cameraRotation.identity();
        runtime.lastClip = null;
        runtime.lastPlayback = -1L;
        runtime.lastStateAnim = null;
    }

    private static void renderArms(PoseStack modelStack, BedrockModel model, SubmitNodeCollector sink,
                                   int light, LocalPlayer player) {
        Identifier skin = skinTexture(player);
        if (skin == null) {
            return;
        }
        renderArmAt(modelStack, model.parts.get("righthand_pos"), sink, light, skin, player, false);
        renderArmAt(modelStack, model.parts.get("lefthand_pos"), sink, light, skin, player, true);
    }

    private static void renderArmAt(PoseStack modelStack, BedrockPart part, SubmitNodeCollector sink,
                                    int light, Identifier skin, LocalPlayer player, boolean leftArm) {
        if (part == null) {
            return;
        }
        java.util.List<BedrockPart> path = new java.util.ArrayList<>();
        for (BedrockPart p = part; p != null; p = p.getParent()) {
            path.add(0, p);
        }
        PoseStack ps = new PoseStack();
        ps.mulPose(modelStack.last().pose());
        for (BedrockPart p : path) {
            p.translateAndRotateAndScale(ps);
        }
        ps.pushPose();
        // TACZ RightHandRender/LeftHandRender 在手臂前额外翻转一次 Z
        ps.rotate(Axis.ZP.rotationDegrees(180F));
        renderArm(ps, sink, light, skin, player, leftArm);
        ps.popPose();
    }

    /** TACZ 手里那两块手模用玩家真实手臂，不是武器贴图 */
    private static boolean isSkinBone(String name) {
        return name.contains("hand") && name.endsWith("_pos");
    }

    /** 诊断用：模型由 left / right 两套镜像几何叠成，可以只画其中一套 */
    private static boolean allowHalf(String name) {
        return switch (Settings.half) {
            case BOTH -> true;
            case LEFT_ONLY -> !"right".equals(name);
            case RIGHT_ONLY -> !"left".equals(name);
        };
    }

    private static void renderArm(PoseStack poseStack, SubmitNodeCollector sink, int light, Identifier skin,
                                  Object playerObj, boolean leftArm) {
        if (skin == null || !(playerObj instanceof LocalPlayer player)) {
            return;
        }
        net.minecraft.client.renderer.entity.EntityRenderer<?, ?> renderer =
                Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        if (renderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatar) {
            if (leftArm) {
                avatar.renderLeftHand(poseStack, sink, light, skin, true);
            } else {
                avatar.renderRightHand(poseStack, sink, light, skin, true);
            }
        }
    }

    private static Identifier skinTexture(Object player) {
        if (player instanceof AbstractClientPlayer clientPlayer) {
            return clientPlayer.getSkin().body().texturePath();
        }
        return null;
    }

    /**
     * 相机定位组的反相矩阵（照 TACZ 的 {@code getPositioningNodeInverse}）。
     * <p>
     * {@code idle_view} = 第一人称手持时眼球的位置；{@code refit_view} = 改枪界面里
     * 武器该在的位置（TACZ 的 {@code GunModelConstant.REFIT_VIEW_NODE}）。
     */
    private static Matrix4f viewInverse(List<BedrockPart> path) {
        Matrix4f inverse = new Matrix4f();
        if (path == null) {
            return inverse;
        }
        for (int i = path.size() - 1; i >= 0; i--) {
            BedrockPart part = path.get(i);
            inverse.rotate(Axis.XN.rotation(part.xRot));
            inverse.rotate(Axis.YN.rotation(part.yRot));
            inverse.rotate(Axis.ZN.rotation(part.zRot));
            if (part.getParent() != null) {
                inverse.translate(-part.x / 16F, -part.y / 16F, -part.z / 16F);
            } else {
                inverse.translate(-part.x / 16F, 1.5F - part.y / 16F, -part.z / 16F);
            }
        }
        return inverse;
    }

    /**
     * 应用相机定位：{@code alpha=0} 时是 idle_view（手持），{@code alpha=1} 时是
     * refit_view（改枪界面）。
     * <p>
     * **照 TACZ**：改枪界面只认模型里的 {@code refit_view} 定位组。包里没有这个节点时，
     * 反相矩阵退化为 idle_view —— 也就是武器停在手持位置，不会"自动居中"。
     * 位置是美术在 geo 里标的，不是代码算出来的。
     */
    private static void applyViewInverse(PoseStack poseStack, BedrockModel model, float alpha) {
        if (model.idleViewPath.isEmpty()) {
            return;
        }
        Matrix4f idle = viewInverse(model.idleViewPath);
        Matrix4f target = model.refitViewPath.isEmpty() ? idle : viewInverse(model.refitViewPath);
        Matrix4f used;
        if (alpha <= 0.0001F) {
            used = idle;
        } else if (alpha >= 0.9999F) {
            used = target;
        } else {
            used = new Matrix4f(idle).lerp(target, alpha);
        }
        poseStack.translate(0F, 1.5F, 0F);
        poseStack.mulPose(used);
        poseStack.translate(0F, -1.5F, 0F);
    }

    /** 推进改枪屏的飞入 / 飞出进度（0 = 在手里，1 = 完全浮到菜单位置） */
    private static void advanceRefitProgress() {
        long now = System.nanoTime();
        if (refitLastNs == 0L) {
            refitLastNs = now;
            return;
        }
        float dt = Math.min((now - refitLastNs) / 1.0E9F, 0.1F);
        refitLastNs = now;
        float target = TaczRefitScreen.isOpen() ? 1.0F : 0.0F;
        float step = dt / REFIT_TRANSITION_SECONDS;
        if (refitProgress < target) {
            refitProgress = Math.min(target, refitProgress + step);
        } else if (refitProgress > target) {
            refitProgress = Math.max(target, refitProgress - step);
            if (refitProgress <= 0.0F) {
                refitKey = null;
            }
        }
    }

    /** 是否还要按改枪屏的位置摆放（含关屏后的飞出过渡） */
    private static boolean refitting() {
        return refitProgress > 0.0001F;
    }




    /**
     * 注册给 PunchyApi.hideArms：当前手由 TACZ rig 接管时才让 Punchy 藏起自己的手臂。
     * <p>
     * 不能用 {@code PunchyApi.state()}：日志显示它在每帧的部分时刻为 null，
     * 导致 hideArms 间歇性变回 false、Punchy 又画出一对手臂（表现为“第三只手”）。
     */
    public static boolean hideArmsNow() {
        if (Settings.punchyRender) {
            return false;
        }
        // 不能只看 renderedThisFrame：它在本帧 renderFrame 开头就被清成 false，
        // 而 Punchy 可能在 renderFrame 之前就问 hideArms，导致本帧它的手臂被画出来
        // —— 界面上就会出现「它的手臂 + 我们居中的武器」。
        // 只接管主手：副手永远交回 Punchy，所以隐藏与否也只由主手决定
        boolean hidden = owningRight;
        if (Settings.logState) {
            logThrottled(lastHideLogNs, v -> lastHideLogNs = v,
                    "punchytacz: hideArms=" + hidden
                            + " ownR=" + taczOwnsRight + " ownL=" + taczOwnsLeft);
        }
        return hidden;
    }
}
