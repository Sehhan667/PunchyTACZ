package com.punchytacz;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.punchytacz.bedrock.BedrockModel;
import com.punchytacz.bedrock.BedrockPart;
import com.punchytacz.statemachine.MeleeAnimationStateContext;
import com.punchytacz.statemachine.MeleeAnimationStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import punchy.api.PunchyApi;

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

    /** 帧序号与本帧已渲染标记，保证一套 rig 每帧只画一次 */
    private static long frameId;
    private static boolean renderedThisFrame;

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("punchytacz");
    private static long lastLogNs;
    private static long lastDrawLogNs;
    private static long lastLambdaLogNs;
    private static long lastBaseLogNs;
    private static long lastArmLogNs;
    private static long lastHideLogNs;
    private static long lastPlaceLogNs;
    private static long armCalls;
    private static long renderedFrames;
    private static long drawCalls;
    private static long lambdaCalls;

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
        frameId++;
        expireStale(activeRight);
        expireStale(activeLeft);
        renderedThisFrame = false;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        PunchyApi.State state = PunchyApi.state();
        PunchyApi.Clip main = state.clipOn(false);
        PunchyApi.Clip off = state.clipOn(true);

        PunchyApi.Clip clip = null;
        TaczPacks.Entry entry = null;
        boolean leftArm = false;
        if (main != null && (entry = TaczPacks.find(main.name())) != null) {
            clip = main;
        } else if (off != null && (entry = TaczPacks.find(off.name())) != null) {
            clip = off;
            leftArm = true;
        } else if (pendingRightEntry != null && pendingRightClip != null
                && pendingStillHeld(player, false, pendingRightStack)) {
            clip = pendingRightClip;
            entry = pendingRightEntry;
        } else if (pendingLeftEntry != null && pendingLeftClip != null
                && pendingStillHeld(player, true, pendingLeftStack)) {
            clip = pendingLeftClip;
            entry = pendingLeftEntry;
            leftArm = true;
        } else {
            pendingRightClip = null;
            pendingLeftClip = null;
            pendingRightEntry = null;
            pendingLeftEntry = null;
            pendingRightStack = null;
            pendingLeftStack = null;
            logThrottled(lastLogNs, v -> lastLogNs = v,
                    "punchytacz: 无 TACZ clip（main=" + (main == null ? "null" : main.name())
                            + " off=" + (off == null ? "null" : off.name()) + "）");
            return;
        }
        if (renderRig(entry, clip, leftArm, sink, light, partialTick, poseStack)) {
            renderedThisFrame = true;
        }
        logThrottled(lastLogNs, v -> lastLogNs = v,
                "punchytacz: frame#" + frameId + " clip=" + clip.name()
                        + " hand=" + (leftArm ? "left" : "right") + " rendered=" + renderedThisFrame);
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

    public static boolean draw(PunchyApi.ItemDraw draw) {
        if (!(draw.stack() instanceof net.minecraft.world.item.ItemStack stack)) {
            return false;
        }
        boolean leftArm = draw.leftArm();
        PunchyApi.Clip clip = PunchyApi.state().clipOn(leftArm);
        if (clip == null) {
            return false;
        }
        TaczPacks.Entry entry = TaczPacks.find(clip.name());
        if (entry == null) {
            return false;
        }
        if (leftArm) {
            pendingLeftClip = clip;
            pendingLeftEntry = entry;
            pendingLeftStack = stack;
        } else {
            pendingRightClip = clip;
            pendingRightEntry = entry;
            pendingRightStack = stack;
        }
        drawCalls++;
        logThrottled(lastDrawLogNs, v -> lastDrawLogNs = v,
                "punchytacz: draw hand=" + (leftArm ? "L" : "R") + " clip=" + clip.name()
                        + " calls=" + drawCalls);
        // Punchy 只负责隐藏自己：返回 true 表示物品由我们接管，它不要画
        return true;
    }

    /** 资源重载后丢弃旧运行时引用 */
    public static void resetActive() {
        activeRight = null;
        activeLeft = null;
        pendingRightClip = null;
        pendingLeftClip = null;
        pendingRightEntry = null;
        pendingLeftEntry = null;
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
        if (!runtime.hooksInstalled) {
            runtime.hooksInstalled = true;
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
        final org.joml.Quaternionf camRot = new org.joml.Quaternionf(model.cameraRotation);
        if (bobView()) {
            stack.mulPose(new Matrix4f().rotation(model.cameraRotation));
        }
        model.cameraRotation.identity();
        // 一次性打全：基准矩阵、相机状态、以及 idle_view 反相前的模型锚点
        Matrix4f b = baseStack.last().pose();
        logThrottled(lastBaseLogNs, v -> lastBaseLogNs = v, String.format(
                "punchytacz: BASE t=(%.3f,%.3f,%.3f) R=[%.3f %.3f %.3f | %.3f %.3f %.3f | %.3f %.3f %.3f] "
                        + "cam=(%.2f,%.2f,%.2f) view=(%.1f,%.1f) bob=(%.2f,%.2f) idlePivot=%s",
                b.m30(), b.m31(), b.m32(),
                b.m00(), b.m01(), b.m02(), b.m10(), b.m11(), b.m12(), b.m20(), b.m21(), b.m22(),
                player.getX(), player.getEyeY(), player.getZ(),
                player.getViewYRot(partialTick), player.getViewXRot(partialTick),
                xRot, yRot,
                idlePivotInfo(model)));
        // TACZ AnimateGeoItemRenderer.renderFirstPerson：
        // 基岩模型以 (0,24,0) 为渲染原点，先抬到模型原点，再翻转上下，
        // 然后用 idle_view 定位组的反相把模型锚到相机原点。
        // static_idle 的位移（weapon y≈56.8 模型单位）是相对 idle_view 写的，缺了反相会被顶到屏幕外。
        stack.translate(0F, 1.5F, 0F);
        // 26.3 的 PoseStack.rotate(Axis, float) 收的是弧度，180F 会被当成 180rad（≈233°）
        stack.rotateDegrees(Axis.ZP, 180F);
        applyIdleViewInverse(stack, model);

        // 手臂必须在模型几何体提交之前、用同一套骨骼姿态同步提交；
        // 不能在 submitCustomGeometry 的回调里再提交节点（flush 过程中追加节点会导致位置/朝向错乱）。
        renderArms(stack, model, sink, light, player);
        // base 的逆：把提交时的 pose 换算回相机空间，即屏幕上真正看到的位置
        final Matrix4f invBase = new Matrix4f(baseStack.last().pose()).invert();
        sink.submitCustomGeometry(stack, RenderTypes.entityCutout(entry.rig().texture()), (pose, consumer) -> {
            PoseStack local = new PoseStack();
            local.mulPose(pose.pose());
            PoseStack camSpace = new PoseStack();
            // invBase · pose = 之后的骨骼变换链，也就是屏幕坐标
            camSpace.mulPose(new Matrix4f(invBase).mul(pose.pose()));
            logThrottled(lastPlaceLogNs, v -> lastPlaceLogNs = v, String.format(
                    "punchytacz: POSE origin=(%.2f,%.2f,%.2f) | CAM origin=(%.2f,%.2f,%.2f) "
                            + "weapon=(%.2f,%.2f,%.2f) rhand=(%.2f,%.2f,%.2f) camRot=(%.2f,%.2f,%.2f,%.2f)",
                    pose.pose().m30(), pose.pose().m31(), pose.pose().m32(),
                    camSpace.last().pose().m30(), camSpace.last().pose().m31(), camSpace.last().pose().m32(),
                    boneX(camSpace, model.parts.get("weapon")),
                    boneY(camSpace, model.parts.get("weapon")),
                    boneZ(camSpace, model.parts.get("weapon")),
                    boneX(camSpace, model.parts.get("righthand_pos")),
                    boneY(camSpace, model.parts.get("righthand_pos")),
                    boneZ(camSpace, model.parts.get("righthand_pos")),
                    camRot.x(), camRot.y(), camRot.z(), camRot.w()));
            model.render(local, consumer, light, OverlayTexture.NO_OVERLAY, 1F, 1F, 1F, 1F,
                    name -> !isSkinBone(name));
        });
        return true;
    }

    /** 把 Punchy 的 clip 变化映射成状态机的一次性输入 */
    private static void triggerForClip(TaczPacks.Runtime runtime, PunchyApi.Clip clip) {
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

    /** idle_view 的局部锚点（相对父骨骼，模型单位 1/16 格），用于判断定位基准是否合理 */
    private static String idlePivotInfo(BedrockModel model) {
        BedrockPart idle = model.parts.get("idle_view");
        if (idle == null) {
            return "none";
        }
        BedrockPart parent = idle.getParent();
        return String.format("[%.2f, %.2f, %.2f]@%s", idle.x, idle.y, idle.z,
                parent == null ? "root" : parent.name);
    }

    private static void logRigPlacement(PoseStack modelStack, BedrockModel model) {
        logThrottled(lastPlaceLogNs, v -> lastPlaceLogNs = v, String.format(
                "punchytacz: rig origin=(%.2f, %.2f, %.2f) weapon=(%.2f, %.2f, %.2f) rhand=(%.2f, %.2f, %.2f)",
                modelStack.last().pose().m30(), modelStack.last().pose().m31(), modelStack.last().pose().m32(),
                boneX(modelStack, model.parts.get("weapon")),
                boneY(modelStack, model.parts.get("weapon")),
                boneZ(modelStack, model.parts.get("weapon")),
                boneX(modelStack, model.parts.get("righthand_pos")),
                boneY(modelStack, model.parts.get("righthand_pos")),
                boneZ(modelStack, model.parts.get("righthand_pos"))));
    }

    private static float boneX(PoseStack modelStack, BedrockPart part) {
        return bonePos(modelStack, part)[0];
    }

    private static float boneY(PoseStack modelStack, BedrockPart part) {
        return bonePos(modelStack, part)[1];
    }

    private static float boneZ(PoseStack modelStack, BedrockPart part) {
        return bonePos(modelStack, part)[2];
    }

    private static float[] bonePos(PoseStack modelStack, BedrockPart part) {
        if (part == null) {
            return new float[]{Float.NaN, Float.NaN, Float.NaN};
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
        Matrix4f m = ps.last().pose();
        return new float[]{m.m30(), m.m31(), m.m32()};
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
        return clip != null && TaczPacks.find(clip.name()) != null;
    }

    /**
     * 注册给 PunchyApi.hideArms：只有映射到 TACZ rig 的 clip 才让 Punchy 藏起自己的手臂。
     * <p>
     * 不能用 {@code PunchyApi.state()}：日志显示它在每帧的部分时刻为 null，
     * 导致 hideArms 间歇性变回 false、Punchy 又画出一对手臂（表现为“第三只手”）。
     * 这里改为读取我们自己的持久状态（本帧登记的 clip 或仍在运行的 runtime）。
     */
    public static boolean hideArmsNow() {
        boolean hidden = pendingRightClip != null || pendingLeftClip != null
                || activeRight != null || activeLeft != null;
        logThrottled(lastHideLogNs, v -> lastHideLogNs = v,
                "punchytacz: hideArms=" + hidden
                        + " pendingR=" + (pendingRightClip == null ? "null" : pendingRightClip.name())
                        + " pendingL=" + (pendingLeftClip == null ? "null" : pendingLeftClip.name()));
        return hidden;
    }
}
