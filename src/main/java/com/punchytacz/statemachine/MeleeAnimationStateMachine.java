package com.punchytacz.statemachine;

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
            hasAngelVariants = context.hasAnimationPrototype("static_idle_angel");
            modelMode = hasAngelVariants
                    ? java.util.concurrent.ThreadLocalRandom.current().nextInt(2)
                    : 0;
        }
        runState.mode = -1;
        walkState.mode = -1;
        super.initialize();
    }

    /** 对应 annihil_state_machine.lua 的 {@code getAnim(baseName)}：Angel 形态追加 "_angel" */
    private String anim(String name) {
        if (modelMode == 1 && hasAngelVariants) {
            String angel = name + "_angel";
            MeleeAnimationStateContext context = getContext();
            if (context != null && context.hasAnimationPrototype(angel)) {
                return angel;
            }
        }
        return name;
    }

    /** annihil_state_machine.lua 的 draw/inspect 会随机播放 _1 变体（不存在时退回基础版） */
    private String randomVariant(String base, String alt) {
        MeleeAnimationStateContext context = getContext();
        if (context != null
                && context.hasAnimationPrototype(anim(alt))
                && java.util.concurrent.ThreadLocalRandom.current().nextBoolean()) {
            return anim(alt);
        }
        return anim(base);
    }

    private void runPutAwayAnimation(MeleeAnimationStateContext context) {
        float putAwayTime = context.getPutAwayTime();
        int track = context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK);
        context.runAnimation("put_away", track, false, ObjectAnim.PlayType.PLAY_ONCE_HOLD, putAwayTime * 0.75f);
        context.setAnimationProgress(track, 1f, true);
        context.adjustAnimationProgress(track, -putAwayTime, false);
    }

    private final class BaseTrackState implements AnimationState<MeleeAnimationStateContext> {
        @Override
        public void update(MeleeAnimationStateContext context) {
        }

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            context.runAnimation(anim("static_idle"), context.getTrack(STATIC_TRACK_LINE, BASE_TRACK),
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
                context.runAnimation(randomVariant("draw", "draw_1"),
                        context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0f);
                return mainIdleState;
            }
            return null;
        }
    }

    private final class MainIdleState implements AnimationState<MeleeAnimationStateContext> {
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
            if (INPUT_PUT_AWAY.equals(input)) {
                runPutAwayAnimation(context);
                return finalState;
            }
            if (INPUT_INSPECT.equals(input)) {
                context.runAnimation(randomVariant("inspect", "inspect_1"),
                        context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
                return this;
            }
            if (INPUT_ATTACK_LEFT.equals(input)) {
                int counter = context.getActionCount("attack_left");
                // annihil/observer 的 lua：attack_index = (counter + 1) % 3，三连击循环
                String animationName = "melee_" + (counter % 3 + 1);
                context.runAnimation(animationName, context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
                return this;
            }
            if (INPUT_ATTACK_RIGHT.equals(input)) {
                context.runAnimation(anim("melee_3"), context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
                return this;
            }
            return null;
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
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (context.isStopped(track) || context.isHolding(track)) {
                context.runAnimation("idle", track, true, ObjectAnim.PlayType.LOOP, 0f);
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
            if (INPUT_RUN.equals(input)) {
                if (context.isStopped(context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK))) {
                    return runState;
                }
                return walkState;
            }
            if (INPUT_WALK.equals(input)) {
                return walkState;
            }
            return null;
        }
    }

    private final class RunState implements AnimationState<MeleeAnimationStateContext> {
        private int mode = -1;
        private long time;
        /** 这套包的动画普遍没有 run_hold（23 把武器全部缺失），起跳时 runAnimation 会静默 no-op */
        private boolean hasRunHold = true;

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            mode = -1;
            time = context.getCurrentTimestamp();
            hasRunHold = context.hasAnimationPrototype("run_hold");
            context.runAnimation(anim("run_start"), context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK),
                    true, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.2f);
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
            context.runAnimation(anim("run_end"), context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK),
                    true, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.3f);
        }

        @Override
        public void update(MeleeAnimationStateContext context) {
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (context.isHolding(track)) {
                context.runAnimation(anim("run"), track, true, ObjectAnim.PlayType.LOOP, 0.2f);
                mode = 0;
                context.anchorWalkDist();
            }
            if (mode == -1) {
                return;
            }
            if (!context.isOnGround()) {
                if (mode != 1) {
                    mode = 1;
                    if (hasRunHold) {
                        context.runAnimation(anim("run_hold"), track, true, ObjectAnim.PlayType.LOOP, 0.6f);
                        // 腾空期间 walkDist 仍在累积（含垂直位移），落地时重新锚定相位
                        context.anchorWalkDist();
                    }
                }
                if (!hasRunHold) {
                    // 没有 run_hold 时**不要**在落地时重播 run：保持 run 继续播放并把相位
                    // 锁到地面移动距离，起跳/落地瞬间相位连续。
                    // 原实现在落地时重播 run 且相位取 walkDist%2/2（腾空期间已继续累积），
                    // 相位跳变 → 用户看到的“脚着地/离地卡一下”。
                    context.setAnimationProgress(track, (context.getWalkDist() % 2.0f) / 2.0f, true);
                }
            } else {
                if (mode != 0) {
                    mode = 0;
                    if (hasRunHold) {
                        context.runAnimation(anim("run"), track, true, ObjectAnim.PlayType.LOOP, 0.2f);
                        // 配合重播的 run：相位从头开始，脚周期与落地时刻同步
                        context.anchorWalkDist();
                    }
                    // 没有 run_hold 时 run 一直在距离锁相，无需重播
                }
                context.setAnimationProgress(track, (context.getWalkDist() % 2.0f) / 2.0f, true);
            }
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
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
        private int mode = -1;

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            mode = -1;
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
            context.runAnimation("idle", context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK),
                    true, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.4f);
        }

        @Override
        public void update(MeleeAnimationStateContext context) {
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (!context.isOnGround()) {
                if (mode != 0) {
                    mode = 0;
                    context.runAnimation("idle", track, true, ObjectAnim.PlayType.LOOP, 0.6f);
                }
            } else if (context.isInputUp()) {
                if (mode != 2) {
                    mode = 2;
                    context.runAnimation("walk_forward", track, true, ObjectAnim.PlayType.LOOP, 0.4f);
                    context.anchorWalkDist();
                }
            } else if (context.isInputDown()) {
                if (mode != 3) {
                    mode = 3;
                    context.runAnimation("walk_backward", track, true, ObjectAnim.PlayType.LOOP, 0.4f);
                    context.anchorWalkDist();
                }
            } else if (context.isInputLeft() || context.isInputRight()) {
                if (mode != 4) {
                    mode = 4;
                    context.runAnimation("walk_sideway", track, true, ObjectAnim.PlayType.LOOP, 0.4f);
                    context.anchorWalkDist();
                }
            }
            if (mode >= 1 && mode <= 4) {
                context.setAnimationProgress(track, (context.getWalkDist() % 2.0f) / 2.0f, true);
            }
        }

        @Override
        public AnimationState<MeleeAnimationStateContext> transition(MeleeAnimationStateContext context, String input) {
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
