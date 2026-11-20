package com.punchytacz.statemachine;

import java.util.concurrent.ThreadLocalRandom;

import com.punchytacz.anim.AnimationController;
import com.punchytacz.anim.ObjectAnim;

import java.util.List;

/**
 * lrtactical default_melee_state_machine.lua 的等价 Java 实现。
 * 轨道行、状态、输入、过渡时长与进度控制均与脚本一一对应。
 */
public final class MeleeAnimationStateMachine extends AnimationStateMachine<MeleeAnimationStateContext> {
    public static final String INPUT_DRAW = "draw";
    public static final String INPUT_PUT_AWAY = "put_away";
    public static final String INPUT_INSPECT = "inspect";
    public static final String INPUT_WALK = "walk";
    public static final String INPUT_RUN = "run";
    public static final String INPUT_IDLE = "idle";
    public static final String INPUT_ATTACK_LEFT = "attack_left";
    public static final String INPUT_ATTACK_RIGHT = "attack_right";

    public static final int STATIC_TRACK_LINE = 0;
    public static final int BASE_TRACK = 0;
    public static final int MAIN_TRACK = 1;
    public static final int BLENDING_TRACK_LINE = 1;
    public static final int MOVEMENT_TRACK = 0;

    private final BaseTrackState baseTrackState = new BaseTrackState();
    private final StartState startState = new StartState();
    private final MainIdleState mainIdleState = new MainIdleState();
    private final FinalState finalState = new FinalState();
    private final MovementIdleState movementIdleState = new MovementIdleState();
    private final RunState runState = new RunState();
    private final WalkState walkState = new WalkState();

    /**
     * annihil_state_machine.lua 的 {@code model_mode}（0=Devil，1=Angel）：
     * {@code initialize} 时随机选定，之后 {@code getAnim()} 给所有动画名追加 {@code _angel}；
     * 可见性由 {@code static_idle(_angel)} 的 scale 关键帧负责（把另一套几何缩到 0，
     * 我们的 Scale 监听器 + {@code BedrockPart} 对 scale=0 整枝跳过会生效）。
     */
    private int modelMode;
    private boolean hasAngelVariants;
    /**
     * modelMode 是否已经定过。
     * <p>
     * 原来每次 {@link #initialize()} 都重新随机（照 lua 的"拔刀随机形态"），
     * 但 rig 会因为 {@code exitRuntime} → 重新 initialize 而反复初始化，
     * 于是普通形态和天使形态交替出现 —— 表现出来就是"切刀第一帧抽好几下"。
     * 坠星者没有 angel 变体，所以不受影响。
     */
    private boolean modelModeChosen;
    /** 本次拔出用哪条起手动画（draw / draw_1），由 {@link #beginDraw()} 决定 */
    private String drawVariant = "draw";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("punchytacz");
    /**
     * 连击索引，对应 lua 的 {@code this.main_track_states.attack_index}。
     * <p>
     * lua 是**自己维护**这个值的（每次 attack_left 后 {@code (counter+1)%3}），
     * 不依赖任何外部 API。之前我翻译成了 {@code context:getActionCount("attack_left")}，
     * 那是 TACZ 给枪械用的、由外部写入的字段 —— 砍掉 tacz_melee_N 信号后没人写，
     * 于是永远 melee_1。
     */
    private int attackIndex;
    /** 对应 lua 的 {@code this.main_track_states.re}：连续多少帧没有攻击 */
    private int attackIdleFrames;

    /** 已经警告过的缺失动画名（每个只警告一次，避免刷屏） */
    private final java.util.Set<String> missingWarned = new java.util.HashSet<>();
    /**
     * 攻击 / 检视这类主轨道一次性动画播放期间，把动作轨道（走/跑）整个停掉。
     * 之前两条轨道同时给 root 和手臂写姿态，跑着挥刀会叠成一团乱麻。
     */
    private boolean movementSuppressed;

    public MeleeAnimationStateMachine(AnimationController animationController) {
        super(animationController);
        setStatesSupplier(this::initialStates);
    }

    private Iterable<? extends AnimationState<MeleeAnimationStateContext>> initialStates() {
        return List.of(baseTrackState, startState, movementIdleState);
    }

    @Override
    public void initialize() {
        MeleeAnimationStateContext context = getContext();
        if (context != null) {
            context.ensureTrackLineSize(2);
            context.ensureTracksAmount(STATIC_TRACK_LINE, 2);
            context.ensureTracksAmount(BLENDING_TRACK_LINE, 1);
            // 与 annihil_state_machine.lua 一致：双形态在 initialize 时随机
            hasAngelVariants = context.hasPackAnimation("static_idle_angel");
            if (!hasAngelVariants) {
                modelMode = 0;
            }
        }
        runState.started = false;
        walkState.clip = null;
        movementSuppressed = false;
        super.initialize();
    }

    /**
     * 开始一次新的"拔出"：随机形态 + 随机起手变体。
     * <p>
     * 必须绑在「换出这把武器」上，而不是每次状态机重建。rig 会因为
     * {@code exitRuntime} → {@code isInitialized()==false} 重新初始化，
     * 如果随机放在 {@code initialize()} 里，切刀过渡那几帧就会在
     * {@code draw} / {@code draw_1}（三个包的这两条差异都在 200°~590°）之间反复跳
     * —— 这就是「切刀第一帧抽好几下」。
     */
    public void beginDraw() {
        MeleeAnimationStateContext context = getContext();
        hasAngelVariants = context != null && context.hasPackAnimation("static_idle_angel");
        modelMode = hasAngelVariants
                && java.util.concurrent.ThreadLocalRandom.current().nextBoolean() ? 1 : 0;
        modelModeChosen = true;
        boolean hasAlt = context != null && context.hasPackAnimation(anim("draw_1"));
        drawVariant = hasAlt && ThreadLocalRandom.current().nextBoolean() ? "draw_1" : "draw";
    }

    /** 任何状态下都能播一次检视（不依赖当前是哪个 state） */
    public void playInspect() {
        MeleeAnimationStateContext context = getContext();
        if (context == null) {
            return;
        }
        String clip = pickExisting(context, "inspect", "inspect_1");
        org.slf4j.LoggerFactory.getLogger("punchytacz").info(
                "punchytacz: playInspect mode={} pick={} packAnims={}",
                modelMode, clip, context.packAnimationCount());
        if (clip == null) {
            return;
        }
        attackIndex = 0;
        attackIdleFrames = 0;
        context.runAnimation(clip, context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
        suppressMovement(context);
    }

    /** 按优先级挑一个包里存在的动画名 */
    private String pickExisting(MeleeAnimationStateContext context, String... names) {
        for (String name : names) {
            String candidate = anim(name);
            if (context.hasPackAnimation(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 只在**包内真的定义**了这条动画时才播，返回是否播了。
     * <p>
     * 绝不能让 {@code runAnimation} 落到 {@link TaczBuiltInAnimations} 的兜底上：
     * 那些是枪械动画（root 带位移/俯仰），套到近战 rig 上就是"手歪""手消失"。
     * 所有播动画的地方都必须走这里，而不是直接 {@code context.runAnimation(anim(...))}。
     */
    private boolean play(MeleeAnimationStateContext context, String name, int track,
                         boolean blend, ObjectAnim.PlayType type, float transition) {
        String clip = anim(name);
        if (!context.hasPackAnimation(clip)) {
            // 每个缺失的名字只警告一次：装新包时能直接看出它缺哪条动画，
            // 而不是只看到"姿势不动/没反应"然后回来猜。
            if (missingWarned.add(clip)) {
                LOG.warn("punchytacz: 包内没有动画 {}（已跳过，保持上一条姿势）", clip);
            }
            return false;
        }
        context.runAnimation(clip, track, blend, type, transition);
        return true;
    }

    /** 按顺序挑第一个包内存在的播；全都没有就什么都不做 */
    private boolean playFirst(MeleeAnimationStateContext context, int track, boolean blend,
                              ObjectAnim.PlayType type, float transition, String... names) {
        for (String name : names) {
            if (play(context, name, track, blend, type, transition)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把 {@link #anim(String)} 加过后缀的名字还原成基础名，
     * 方便再交给 {@link #play}（它内部会重新按当前形态加后缀）。
     */
    private String clipName(String clip) {
        if (clip == null) {
            return null;
        }
        if (modelMode == 1 && hasAngelVariants && clip.endsWith("_angel")) {
            return clip.substring(0, clip.length() - "_angel".length());
        }
        return clip;
    }

    /**
     * 攻击 / 检视这类"主轨道动作"，任何移动状态都必须能触发。
     * <p>
     * lua 里主轨道和移动轨道是**两个独立状态机**，所以走路/跑动时攻击照样生效；
     * 我们把它俩合成了一个状态机，如果只在 {@code MainIdleState.transition} 里处理，
     * 走/跑时按左键就完全没反应。返回 true 表示已处理，调用方应切回 mainIdleState。
     */
    private boolean handleAction(MeleeAnimationStateContext context, String input) {
        if (INPUT_INSPECT.equals(input)) {
            int track = context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK);
            playFirst(context, track, false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f,
                    randomVariant("inspect", "inspect_1"), "inspect", "inspect_1");
            attackIndex = 0;
            attackIdleFrames = 0;
            suppressMovement(context);
            return true;
        }
        if (INPUT_ATTACK_LEFT.equals(input)) {
            attackIdleFrames = 0;
            int counter = attackIndex;
            int segments = meleeSegments(context);
            if (segments > 0) {
                play(context, "melee_" + (counter % segments + 1),
                        context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
            }
            attackIndex = (counter + 1) % 3;
            suppressMovement(context);
            return true;
        }
        if (INPUT_ATTACK_RIGHT.equals(input)) {
            // lua 里 attack_right 不播动画，只归零
            attackIdleFrames = 0;
            attackIndex = 0;
            return true;
        }
        return false;
    }

    /** 包里实际有几段连击（1~3）。没有 melee_1 返回 0 */
    private int meleeSegments(MeleeAnimationStateContext context) {
        for (int i = 3; i >= 1; i--) {
            if (context.hasPackAnimation(anim("melee_" + i))) {
                return i;
            }
        }
        return 0;
    }

    /** 对应 annihil_state_machine.lua 的 {@code getAnim(baseName)}：Angel 形态追加 "_angel" */
    private String anim(String name) {
        if (modelMode == 1 && hasAngelVariants) {
            String angel = name + "_angel";
            MeleeAnimationStateContext context = getContext();
            if (context != null && context.hasPackAnimation(angel)) {
                return angel;
            }
        }
        return name;
    }

    /** annihil_state_machine.lua 的 draw/inspect 会随机播放 _1 变体（不存在时退回基础版） */
    private String randomVariant(String base, String alt) {
        MeleeAnimationStateContext context = getContext();
        if (context != null
                && context.hasPackAnimation(anim(alt))
                && java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
            return anim(alt);
        }
        return anim(base);
    }

    /** 主轨道开始一次性动作，把动作轨道停掉，避免两条轨道同时写 root / 手臂 */
    private void suppressMovement(MeleeAnimationStateContext context) {
        if (movementSuppressed) {
            return;
        }
        movementSuppressed = true;
        context.stopAnimation(context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK));
    }

    private void runPutAwayAnimation(MeleeAnimationStateContext context) {
        float putAwayTime = context.getPutAwayTime();
        int track = context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK);
        playFirst(context, track, false, ObjectAnim.PlayType.PLAY_ONCE_HOLD,
                putAwayTime * 0.75f, "put_away", "hand_out");
        context.setAnimationProgress(track, 1f, true);
        context.adjustAnimationProgress(track, -putAwayTime, false);
    }

    private final class BaseTrackState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            // base 轨道**只**播 static_idle：它负责「把另一套形态的骨骼 scale 缩到 0」。
            // 这条绝不能出现在动作轨道上 —— 动作轨道是累加混合，再乘一次 scale 就是 0，
            // 整个模型会消失（暗星 idle / 切刀看不见就是这么来的）。
            // 对应 lua 的 base_track_state.entry：attack_index = 0, re = 0
            attackIndex = 0;
            attackIdleFrames = 0;
            play(context, "static_idle", context.getTrack(STATIC_TRACK_LINE, BASE_TRACK),
                    false, ObjectAnim.PlayType.LOOP, 0f);
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            return null;
        }
    }

    private final class StartState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
            context.trigger(INPUT_DRAW);
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            if (INPUT_DRAW.equals(input)) {
                playFirst(context, context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0f, drawVariant, "draw");
                return mainIdleState;
            }
            return null;
        }
    }

    private final class MainIdleState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
            if (!movementSuppressed) {
                return;
            }
            int main = context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK);
            if (context.isStopped(main) || context.isHolding(main)) {
                // 一次性动作播完了，把动作轨道交还给走/跑
                movementSuppressed = false;
            }
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            if (INPUT_PUT_AWAY.equals(input)) {
                runPutAwayAnimation(context);
                return finalState;
            }
            if (handleAction(context, input)) {
                return this;
            }
            // 对应 lua 的 else 分支：每帧累计“多久没攻击”，超过阈值就把连击链断掉
            int frames = attackIdleFrames;
            attackIdleFrames = frames + 1;
            if (attackIndex == 0) {
                attackIdleFrames = 0;
            } else if (attackIndex == 1) {
                if (frames >= 37) {
                    attackIdleFrames = 0;
                    attackIndex = 0;
                }
            } else if (attackIndex == 2) {
                if (frames >= 41) {
                    attackIdleFrames = 0;
                    attackIndex = 0;
                }
            }
            return this;
        }
    }

    private final class FinalState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            return null;
        }
    }

    private final class MovementIdleState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
            if (movementSuppressed) {
                return;
            }
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (context.isStopped(track) || context.isHolding(track)) {
                // 只播真正的 idle；包内没有就什么都不播，让 base 的 static_idle 撑着。
                // blend=false（覆盖）：idle 通常是**完整**待机姿势，叠加到 base 上会和
                // static_idle 的 position 相加，几十格的位移直接把模型推出画面。
                play(context, "idle", track, false, ObjectAnim.PlayType.LOOP, 0f);
            }
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            if (movementSuppressed) {
                return null;
            }
            if (INPUT_RUN.equals(input)) {
                if (context.isStopped(context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK))) {
                    return runState;
                }
                return walkState;
            }
            if (INPUT_WALK.equals(input)
                    || !context.isStopped(context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK))) {
                return walkState;
            }
            return null;
        }
    }

    private final class RunState implements AnimationState<MeleeAnimationStateContext> {
        private boolean started;

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            started = false;
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (!context.hasPackAnimation(anim("run_start"))) {
                // 包里没有 run_start：什么都别播。之前会落到 TACZ 内置的枪械动画上，
                // 那条的 root 有大位移，直接把整只手甩出画面（暗星奔跑手消失）。
                context.stopAnimation(track);
                return;
            }
            play(context, "run_start", track, false, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.2f);
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (!play(context, "run_end", track, false, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.3f)) {
                // 没有收尾动画就必须显式停掉：run 是 LOOP，不停会一直转下去
                context.stopAnimation(track);
            }
        }

        @Override
        public void update(MeleeAnimationStateContext context) {
            if (movementSuppressed) {
                return;
            }
            // 只播包内自己的 run；没有就保持 static_idle（手不会消失）
            if (!context.hasPackAnimation(anim("run"))) {
                return;
            }
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (!started || context.isHolding(track)) {
                started = true;
                // blend=false（覆盖）：基岩动画的 rotation/position 都是**绝对**值（相对 rest pose），
                // 叠加到 base 上只会把两套姿势相加。现在三个包的 run 和 static_idle 骨骼无交集，
                // 所以叠加/覆盖结果相同；但某个包若两者有同名骨骼，叠加就是位移翻倍。
                play(context, "run", track, false, ObjectAnim.PlayType.LOOP, 0.2f);
                context.anchorWalkDist();
            }
            // 腾空时不再切 TACZ 内置的 run_hold：那条动画是给枪写的，root 固定俯仰 -36°，
            // 套到近战 rig 上会把整条手臂甩出画面。这里让 run 的相位停在起跳那一刻。
            if (context.isOnGround()) {
                context.setAnimationProgress(track, (context.getWalkDist() % 2.0f) / 2.0f, true);
            }
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            if (handleAction(context, input)) {
                return mainIdleState;
            }
            if (INPUT_IDLE.equals(input)) {
                return movementIdleState;
            }
            if (INPUT_WALK.equals(input)
                    || !context.isStopped(context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK))) {
                return walkState;
            }
            return null;
        }
    }

    private final class WalkState implements AnimationState<MeleeAnimationStateContext> {
        /** 当前在播的走路动画；null = 包里没有，保持静止（绝不拿 run 顶替） */
        private String clip;

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            clip = null;
            context.stopAnimation(context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK));
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
            if (clip == null) {
                return;
            }
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            // 走路动画本身是 LOOP，退出时必须用 idle 盖掉或显式停掉，否则会一直走
            if (!play(context, "idle", track, false, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.4f)) {
                context.stopAnimation(track);
            }
        }

        @Override
        public void update(MeleeAnimationStateContext context) {
            if (movementSuppressed) {
                return;
            }
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (clip == null) {
                // 包里没有 walk_forward / walk_backward / walk_sideway 就什么都不播。
                // 之前拿 run 的循环顶替，跑步的步频和幅度套在走路上看着像在水里跑，已撤掉。
                String want = pickWalkClip(context);
                if (want == null) {
                    return;
                }
                clip = want;
                play(context, clipName(clip), track, false, ObjectAnim.PlayType.LOOP, 0.2f);
                context.anchorWalkDist();
            } else if (context.isStopped(track) || context.isHolding(track)) {
                play(context, clipName(clip), track, false, ObjectAnim.PlayType.LOOP, 0.2f);
            }
            if (context.isOnGround()) {
                context.setAnimationProgress(track, (context.getWalkDist() % 2.0f) / 2.0f, true);
            }
        }

        /** 按输入方向挑包内的走路动画；没有就返回 null */
        private String pickWalkClip(MeleeAnimationStateContext context) {
            String candidate;
            if (context.isInputDown()) {
                candidate = anim("walk_backward");
            } else if (context.isInputLeft() || context.isInputRight()) {
                candidate = anim("walk_sideway");
            } else {
                candidate = anim("walk_forward");
            }
            if (context.hasPackAnimation(candidate)) {
                return candidate;
            }
            return null;
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
            // 走/跑时攻击照常生效（lua 里主轨道是独立状态机，不受移动影响）
            if (handleAction(context, input)) {
                return mainIdleState;
            }
            if (movementSuppressed) {
                return null;
            }
            if (INPUT_IDLE.equals(input)) {
                return movementIdleState;
            }
            if (INPUT_RUN.equals(input)
                    && context.isStopped(context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK))) {
                return runState;
            }
            return null;
        }
    }
}