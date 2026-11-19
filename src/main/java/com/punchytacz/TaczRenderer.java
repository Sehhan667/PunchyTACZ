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
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import punchy.api.PunchyApi;

import java.util.HashMap;
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
    private static TaczPacks.Entry pendingRightEntry;
    private static TaczPacks.Entry pendingLeftEntry;
    private static Object pendingRightStack;
    private static Object pendingLeftStack;
    private static String pendingRightOption;
    private static String pendingLeftOption;
    private static int pendingRightLight = -1;
    private static int pendingLeftLight = -1;

    /** 当前帧哪只手由 TACZ rig 接管（hideArms 用） */
    private static boolean taczOwnsRight;
    private static boolean taczOwnsLeft;

    /** 物品 → 上次确认过的 entry（idle/run 时 Punchy 不给 clip，用它常驻渲染） */
    private static final Map<String, TaczPacks.Entry> LAST_BY_ITEM = new HashMap<>();

    /** 帧序号与本帧已渲染标记，保证一套 rig 每帧只画一次 */
    private static long frameId;
    private static boolean renderedThisFrame;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("punchytacz");
    private static long lastLogNs;
    private static long lastDrawLogNs;
    private static long lastHideLogNs;
    private static long lastStateLogNs;
    private static long drawCalls;

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
        expireStale(activeRight);
        expireStale(activeLeft);
        renderedThisFrame = false;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        net.minecraft.world.item.ItemStack mainStack = player.getMainHandItem();
        net.minecraft.world.item.ItemStack offStack = player.getOffhandItem();
        String mainOption = selectedPackOption(mainStack);
        String offOption = selectedPackOption(offStack);
        boolean mainTacz = TaczPacks.isTaczPackOption(mainOption);
        boolean offTacz = TaczPacks.isTaczPackOption(offOption);
        taczOwnsRight = mainTacz;
        taczOwnsLeft = offTacz;
        // 选中的包不是 TACZ 包时立即停掉对应手的 rig，避免 Punchy 和本模组同时画手
        if (!mainTacz) {
            clearHand(false);
        }
        if (!offTacz) {
            clearHand(true);
        }

        PunchyApi.State state = PunchyApi.state();
        PunchyApi.Clip main = mainTacz ? state.clipOn(false) : null;
        PunchyApi.Clip off = offTacz ? state.clipOn(true) : null;
        // Punchy 自己的 clip（游泳 / 交互 / 攀爬等 TACZ 没有的动画）激活时，
        // 那只手必须**完全**交回 Punchy：物品和手臂都由它画。
        // 之前只清了 pending、没停 rig，renderFrame 会走 LAST_BY_ITEM 常驻渲染静态 idle，
        // 同时 hideArmsNow 还在让 Punchy 藏手臂 → 出现“我们的手臂 + 它的物品”。
        if (main != null && TaczPacks.find(mainOption, main.name()) == null) {
            clearHand(false);
        }
        if (off != null && TaczPacks.find(offOption, off.name()) == null) {
            clearHand(true);
        }
        boolean mainHasTaczClip = main == null || TaczPacks.find(mainOption, main.name()) != null;
        boolean offHasTaczClip = off == null || TaczPacks.find(offOption, off.name()) != null;

        PunchyApi.Clip clip = null;
        TaczPacks.Entry entry = null;
        boolean leftArm = false;
        if (main != null && mainHasTaczClip && (entry = TaczPacks.find(mainOption, main.name())) != null) {
            clip = main;
            LAST_BY_ITEM.put(itemKey(mainStack), entry);
        } else if (off != null && offHasTaczClip && (entry = TaczPacks.find(offOption, off.name())) != null) {
            clip = off;
            leftArm = true;
            LAST_BY_ITEM.put(itemKey(offStack), entry);
        } else if (mainTacz && mainHasTaczClip && pendingRightEntry != null
                && Objects.equals(pendingRightOption, mainOption)
                && pendingStillHeld(player, false, pendingRightStack)) {
            clip = pendingRightClip;
            entry = pendingRightEntry;
        } else if (offTacz && offHasTaczClip && pendingLeftEntry != null
                && Objects.equals(pendingLeftOption, offOption)
                && pendingStillHeld(player, true, pendingLeftStack)) {
            clip = pendingLeftClip;
            entry = pendingLeftEntry;
            leftArm = true;
        } else if (mainTacz && main == null && (entry = LAST_BY_ITEM.get(itemKey(mainStack))) != null) {
            // Punchy 在 idle / run 下不给 clip，用该物品上次确认过的 entry 常驻渲染
            clip = null;
        } else if (offTacz && off == null && (entry = LAST_BY_ITEM.get(itemKey(offStack))) != null) {
            clip = null;
            leftArm = true;
        } else {
            clearPending(false);
            clearPending(true);
            return;
        }
        renderedThisFrame = renderRig(entry, clip, leftArm, sink, light, partialTick, poseStack);
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
        try {
            return TaczPacks.packOptionOf(
                    punchy.config.PunchyTuningConfig.getMixPreferredOptionForStack(stack));
        } catch (Throwable t) {
            logThrottled(lastLogNs, v -> lastLogNs = v,
                    "punchytacz: 读取 Punchy 包选择失败：" + t);
            return null;
        }
    }

    private static void clearPending(boolean leftArm) {
        if (leftArm) {
            pendingLeftClip = null;
            pendingLeftEntry = null;
            pendingLeftStack = null;
            pendingLeftOption = null;
            pendingLeftLight = -1;
        } else {
            pendingRightClip = null;
            pendingRightEntry = null;
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
        String option = selectedPackOption(stack);
        if (!TaczPacks.isTaczPackOption(option)) {
            // 这只手当前选的是别的动画包：让 Punchy 自己画，并立刻停掉旧 rig。
            // 【回退路径】PunchyApi.drawItem 返回 true 表示"这件物品由本模组接管、Punchy 不画"；
            // 返回 false 则 Punchy 用它自己的 model_parts_items 定义（geo/texture/display）绘制。
            // 所以：其它近战武器、包里没写 tacz 块、或 clip 找不到时，都会走这里交还给 Punchy，
            // Punchy 必须能拿到模型（这也是资源包里每个 model_parts_items 定义都要有 display 的原因）。
            clearHand(leftArm);
            if (leftArm) {
                taczOwnsLeft = false;
            } else {
                taczOwnsRight = false;
            }
            return false;
        }
        PunchyApi.Clip clip = PunchyApi.state().clipOn(leftArm);
        TaczPacks.Entry entry = clip == null ? null : TaczPacks.find(option, clip.name());
        if (entry == null) {
            if (clip != null) {
                // 当前是 Punchy 自己的 clip（游泳 / 交互 / 攀爬等 TACZ 没有的动画）：
                // 整只手交回 Punchy（物品+手臂都由它画），并停掉我们的 rig；
                // 切回 TACZ clip（拔刀/挥砍）时 renderFrame 会重新初始化状态机并重播 draw。
                // 之前这里只清 pending，rig 还在常驻渲染静态 idle，同时 hideArmsNow 又让
                // Punchy 藏手臂 → 出现“我们的手臂 + 它的物品”。
                clearHand(leftArm);
                return false;
            }
            // Punchy 在 idle / run 这类程序化姿态下不给 clip：用这个物品上一次确认过的 entry
            // （一个包里可以有多把刀，不能按包名随便挑一个 *_idle）
            entry = LAST_BY_ITEM.get(itemKey(stack));
            if (entry == null) {
                clearPending(leftArm);
                return false;
            }
        } else {
            LAST_BY_ITEM.put(itemKey(stack), entry);
        }
        if (leftArm) {
            pendingLeftClip = clip;
            pendingLeftEntry = entry;
            pendingLeftStack = stack;
            pendingLeftOption = option;
            pendingLeftLight = draw.light();
            taczOwnsLeft = true;
        } else {
            pendingRightClip = clip;
            pendingRightEntry = entry;
            pendingRightStack = stack;
            pendingRightOption = option;
            pendingRightLight = draw.light();
            taczOwnsRight = true;
        }
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

    private static boolean renderRig(TaczPacks.Entry entry, PunchyApi.Clip clip, boolean leftArm,
                                     SubmitNodeCollector sink, int light, float partialTick, PoseStack baseStack) {
        TaczPacks.Runtime runtime = TaczPacks.runtime(entry.rig(), leftArm);
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

        MeleeAnimationStateMachine stateMachine = runtime.stateMachine;
        if (!stateMachine.isInitialized()) {
            // 重新拔刀：清掉上一轮的轨道与残留姿态，再播放 draw
            runtime.controller.clear();
            runtime.model.cleanAnimationTransform();
            runtime.model.cameraRotation.identity();
            MeleeAnimationStateContext context = new MeleeAnimationStateContext();
            stateMachine.setContext(context);
            stateMachine.initialize();
        }
        stateMachine.processContextIfExist(context -> context.setPartialTicks(partialTick));

        triggerForClip(runtime, clip);
        triggerMovement(runtime, player);
        // 清除上一帧的姿态再更新（TACZ 每帧渲染后清理，这里放在更新前，避免多次提交时互相覆盖）
        runtime.model.cleanAnimationTransform();
        runtime.model.cameraRotation.identity();
        stateMachine.update();
        CAMERA_ANIM.set(runtime.model.cameraRotation);

        BedrockModel model = runtime.model;
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
        applyIdleViewInverse(stack, model);

        // 手臂必须在模型几何体提交之前、用同一套骨骼姿态同步提交；
        // 不能在 submitCustomGeometry 的回调里再提交节点（flush 过程中追加节点会导致位置/朝向错乱）。
        if (Settings.renderArms) {
            renderArms(stack, model, sink, effectiveLight, player);
        }
        if (Settings.submitGeo) {
            Identifier texture = entry.rig().texture();
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

    /** 把 Punchy 的 clip 变化映射成状态机的一次性输入 */
    private static void triggerForClip(TaczPacks.Runtime runtime, PunchyApi.Clip clip) {
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

        MeleeAnimationStateMachine stateMachine = runtime.stateMachine;
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
        if (name.contains("_draw")) {
            stateMachine.trigger(MeleeAnimationStateMachine.INPUT_DRAW);
            return;
        }
        if (name.contains("_inspect")) {
            stateMachine.trigger(MeleeAnimationStateMachine.INPUT_INSPECT);
        }
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

    /** 与 TACZ TickAnimationEvent 一致：每帧按玩家状态发送移动输入 */
    private static void triggerMovement(TaczPacks.Runtime runtime, LocalPlayer player) {
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
        if (runtime.stateMachine.isInitialized()) {
            runtime.stateMachine.exit();
        }
        runtime.controller.clear();
        runtime.model.cleanAnimationTransform();
        runtime.model.cameraRotation.identity();
        runtime.lastClip = null;
        runtime.lastPlayback = -1L;
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

    private static void applyIdleViewInverse(PoseStack poseStack, BedrockModel model) {
        if (model.idleViewPath.isEmpty()) {
            return;
        }
        Matrix4f inverse = new Matrix4f();
        for (int i = model.idleViewPath.size() - 1; i >= 0; i--) {
            BedrockPart part = model.idleViewPath.get(i);
            inverse.rotate(Axis.XN.rotation(part.xRot));
            inverse.rotate(Axis.YN.rotation(part.yRot));
            inverse.rotate(Axis.ZN.rotation(part.zRot));
            if (part.getParent() != null) {
                inverse.translate(-part.x / 16F, -part.y / 16F, -part.z / 16F);
            } else {
                inverse.translate(-part.x / 16F, 1.5F - part.y / 16F, -part.z / 16F);
            }
        }
        poseStack.translate(0F, 1.5F, 0F);
        poseStack.mulPose(inverse);
        poseStack.translate(0F, -1.5F, 0F);
    }

    public static boolean isTaczClip(PunchyApi.Clip clip) {
        return clip != null && TaczPacks.findAny(clip.name()) != null;
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
        boolean hidden = renderedThisFrame;
        if (Settings.logState) {
            logThrottled(lastHideLogNs, v -> lastHideLogNs = v,
                    "punchytacz: hideArms=" + hidden
                            + " ownR=" + taczOwnsRight + " ownL=" + taczOwnsLeft);
        }
        return hidden;
    }
}
