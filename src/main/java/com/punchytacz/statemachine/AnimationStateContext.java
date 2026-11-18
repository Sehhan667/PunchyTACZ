package com.punchytacz.statemachine;

import com.punchytacz.anim.AnimRunner;
import com.punchytacz.anim.AnimationController;
import com.punchytacz.anim.DiscreteTrackArray;
import com.punchytacz.anim.ObjectAnim;

import java.util.List;

/** 对应 TACZ AnimationStateContext：给状态机提供轨道与播放控制 */
public class AnimationStateContext {
    private AnimationStateMachine<?> stateMachine;
    private final DiscreteTrackArray trackArray = new DiscreteTrackArray();

    public AnimationStateMachine<?> getStateMachine() {
        return stateMachine;
    }

    public DiscreteTrackArray getTrackArray() {
        return trackArray;
    }

    public int addTrackLine() {
        checkTrackArray();
        return trackArray.addTrackLine();
    }

    public void ensureTrackLineSize(int size) {
        checkTrackArray();
        trackArray.ensureCapacity(size);
    }

    public int getTrackLineSize() {
        checkTrackArray();
        return trackArray.getTrackLineSize();
    }

    public int assignNewTrack(int index) {
        checkTrackArray();
        return trackArray.assignNewTrack(index);
    }

    public int findIdleTrack(int index, boolean interruptHolding) {
        AnimationStateMachine<?> machine = checkStateMachine();
        checkTrackArray();
        List<Integer> trackList = trackArray.getByIndex(index);
        AnimationController controller = machine.getAnimationController();
        for (int track : trackList) {
            AnimRunner animation = controller.getAnimation(track);
            if (animation == null || animation.isStopped() || (interruptHolding && animation.isHolding())) {
                return track;
            }
        }
        return trackArray.assignNewTrack(index);
    }

    public void ensureTracksAmount(int index, int amount) {
        checkTrackArray();
        trackArray.ensureTrackAmount(index, amount);
    }

    public int getTrack(int trackLineIndex, int trackIndex) {
        checkTrackArray();
        if (trackLineIndex >= trackArray.getTrackLineSize()) {
            return -1;
        }
        List<Integer> tracks = trackArray.getByIndex(trackLineIndex);
        if (trackIndex >= tracks.size()) {
            return -1;
        }
        return tracks.get(trackIndex);
    }

    public int getAsSingletonTrack(int index) {
        checkTrackArray();
        List<Integer> trackList = trackArray.getByIndex(index);
        if (trackList.isEmpty()) {
            return trackArray.assignNewTrack(index);
        }
        return trackList.get(0);
    }

    public void runAnimation(String name, int track, boolean blending, ObjectAnim.PlayType playType,
                             float transitionTime) {
        AnimationStateMachine<?> machine = checkStateMachine();
        machine.getAnimationController().runAnimation(track, name, playType, transitionTime);
        machine.getAnimationController().setBlending(track, blending);
    }

    /** 与 lua 脚本一致的整数入口，取值为 {@link ObjectAnim.PlayType} 的 ordinal */
    public void runAnimation(String name, int track, boolean blending, int playType, float transitionTime) {
        runAnimation(name, track, blending, ObjectAnim.PlayType.values()[playType], transitionTime);
    }

    public void stopAnimation(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.stop();
        }
    }

    public void holdAnimation(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.hold();
        }
    }

    public void pauseAnimation(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.pause();
        }
    }

    public void resumeAnimation(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            runner.run();
        }
    }

    public void setAnimationProgress(int track, float progress, boolean normalization) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner == null) {
            return;
        }
        if (runner.isRunning() || runner.isPausing()) {
            if (normalization) {
                progress = runner.getAnimation().getMaxEndTimeS() * progress;
            }
            runner.setProgressNs((long) (progress * 1e9));
            return;
        }
        AnimRunner next = runner.getTransitionTo();
        if (next != null) {
            if (normalization) {
                progress = next.getAnimation().getMaxEndTimeS() * progress;
            }
            next.setProgressNs((long) (progress * 1e9));
        }
    }

    public void adjustAnimationProgress(int track, float progress, boolean normalization) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner == null) {
            return;
        }
        if (runner.isRunning()) {
            if (normalization) {
                progress = runner.getAnimation().getMaxEndTimeS() * progress;
            }
            runner.setProgressNs(runner.getProgressNs() + (long) (progress * 1e9));
            return;
        }
        AnimRunner next = runner.getTransitionTo();
        if (next != null) {
            if (normalization) {
                progress = next.getAnimation().getMaxEndTimeS() * progress;
            }
            next.setProgressNs(next.getProgressNs() + (long) (progress * 1e9));
        }
    }

    public boolean isHolding(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            return runner.getTransitionTo() != null ? runner.getTransitionTo().isHolding() : runner.isHolding();
        }
        return false;
    }

    public boolean isStopped(int track) {
        AnimRunner runner = checkStateMachine().getAnimationController().getAnimation(track);
        if (runner != null) {
            return runner.getTransitionTo() != null ? runner.getTransitionTo().isStopped() : runner.isStopped();
        }
        return true;
    }

    public boolean hasAnimationPrototype(String name) {
        return checkStateMachine().getAnimationController().containPrototype(name);
    }

    public void trigger(String input) {
        checkStateMachine().trigger(input);
    }

    void setStateMachine(AnimationStateMachine<?> stateMachine) {
        if (this.stateMachine != null) {
            this.stateMachine.getAnimationController().setUpdatingTrackArray(null);
        }
        if (stateMachine != null) {
            stateMachine.getAnimationController().setUpdatingTrackArray(trackArray);
        }
        this.stateMachine = stateMachine;
    }

    private void checkTrackArray() {
        if (stateMachine != null && stateMachine.getAnimationController().getUpdatingTrackArray() != trackArray) {
            throw new TrackArrayMismatchException();
        }
    }

    private AnimationStateMachine<?> checkStateMachine() {
        if (stateMachine == null) {
            throw new IllegalStateException("This context has not been bound to a state machine.");
        }
        return stateMachine;
    }

    /** 对应 TACZ TrackArrayMismatchException */
    public static final class TrackArrayMismatchException extends RuntimeException {
        public TrackArrayMismatchException() {
            super("The track array of the context does not match the state machine's animation controller");
        }
    }
}
