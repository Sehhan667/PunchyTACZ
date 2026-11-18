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
        }
        runState.mode = -1;
        walkState.mode = -1;
        super.initialize();
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
            context.runAnimation("static_idle", context.getTrack(STATIC_TRACK_LINE, BASE_TRACK),
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
                context.runAnimation("draw", context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
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
                context.runAnimation("inspect", context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
                return this;
            }
            if (INPUT_ATTACK_LEFT.equals(input)) {
                int counter = context.getActionCount("attack_left");
                String animationName = "melee_" + (counter % 2 + 1);
                context.runAnimation(animationName, context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
                        false, ObjectAnim.PlayType.PLAY_ONCE_STOP, 0.2f);
                return this;
            }
            if (INPUT_ATTACK_RIGHT.equals(input)) {
                context.runAnimation("melee_3", context.getTrack(STATIC_TRACK_LINE, MAIN_TRACK),
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

        @Override
        public void entryAction(MeleeAnimationStateContext context) {
            mode = -1;
            time = context.getCurrentTimestamp();
            context.runAnimation("run_start", context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK),
                    true, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.2f);
        }

        @Override
        public void exitAction(MeleeAnimationStateContext context) {
            context.runAnimation("run_end", context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK),
                    true, ObjectAnim.PlayType.PLAY_ONCE_HOLD, 0.3f);
        }

        @Override
        public void update(MeleeAnimationStateContext context) {
            int track = context.getTrack(BLENDING_TRACK_LINE, MOVEMENT_TRACK);
            if (context.isHolding(track)) {
                context.runAnimation("run", track, true, ObjectAnim.PlayType.LOOP, 0.2f);
                mode = 0;
                context.anchorWalkDist();
            }
            if (mode == -1) {
                return;
            }
            if (!context.isOnGround()) {
                if (mode != 1) {
                    mode = 1;
                    context.runAnimation("run_hold", track, true, ObjectAnim.PlayType.LOOP, 0.6f);
                }
            } else {
                if (mode != 0) {
                    mode = 0;
                    context.runAnimation("run", track, true, ObjectAnim.PlayType.LOOP, 0.2f);
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
