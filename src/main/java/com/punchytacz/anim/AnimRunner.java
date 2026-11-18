package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.Minecraft;

/** 移植 TACZ ObjectAnimationRunner（去掉声音/粒子通道），负责进度、过渡与挂起/停止 */
public final class AnimRunner {
    private final ObjectAnim animation;
    private long transitionTimeNs;
    private ArrayList<float[]> valueFrom;
    private ArrayList<float[]> valueRecover;
    private ArrayList<AnimChannel> transitionFromChannels;
    private ArrayList<AnimChannel> transitionToChannels;
    private ArrayList<AnimChannel> recoverChannels;
    private boolean running;
    private boolean pausing;
    private long lastUpdateNs;
    private long progressNs;
    private boolean isTransitioning;
    private AnimRunner transitionTo;
    private long transitionProgressNs;

    /** 单帧最多推进 0.1s，替代 TACZ 的 GunTime epoch 复位，避免暂停/卡顿后动画跳跃 */
    private static final long MAX_DELTA_NS = 100_000_000L;

    public AnimRunner(ObjectAnim animation) {
        this.animation = Objects.requireNonNull(animation);
    }

    public ObjectAnim getAnimation() {
        return animation;
    }

    public AnimRunner getTransitionTo() {
        return transitionTo;
    }

    public boolean isTransitioning() {
        return isTransitioning;
    }

    public void run() {
        if (!running) {
            running = true;
            lastUpdateNs = System.nanoTime();
        }
        pausing = false;
    }

    public void pause() {
        running = false;
        pausing = true;
    }

    public void hold() {
        progressNs = (long) (animation.getMaxEndTimeS() * 1e9) + 1;
        running = false;
    }

    public void stop() {
        progressNs = (long) (animation.getMaxEndTimeS() * 1e9) + 2;
        running = false;
    }

    public void reset() {
        progressNs = 0;
    }

    public long getProgressNs() {
        return progressNs;
    }

    public void setProgressNs(long progressNs) {
        this.progressNs = progressNs;
    }

    public void transition(AnimRunner target, long transitionTimeNs) {
        if (this.transitionTo == null) {
            this.valueFrom = new ArrayList<>();
            this.valueRecover = new ArrayList<>();
            this.transitionFromChannels = new ArrayList<>();
            this.transitionToChannels = new ArrayList<>();
            this.recoverChannels = new ArrayList<>();
            this.transitionTo = target;
            this.running = false;
            for (Map.Entry<String, List<AnimChannel>> entry : animation.getChannels().entrySet()) {
                List<AnimChannel> toChannels = target.animation.getChannels().get(entry.getKey());
                if (toChannels != null) {
                    for (AnimChannel channel : entry.getValue()) {
                        Optional<AnimChannel> toChannel = toChannels.stream()
                                .filter(c -> c.type == channel.type).findAny();
                        float[] value = channel.getResult(progressNs / 1e9f);
                        if (channel.type == AnimChannelType.ROTATION && value.length == 3) {
                            value = AnimMath.toQuaternion(value[0], value[1], value[2]);
                        }
                        if (toChannel.isPresent()) {
                            valueFrom.add(value);
                            transitionFromChannels.add(channel);
                            transitionToChannels.add(toChannel.get());
                            toChannel.get().transitioning = true;
                        } else {
                            valueRecover.add(value);
                            recoverChannels.add(channel);
                        }
                    }
                } else {
                    for (AnimChannel channel : entry.getValue()) {
                        float[] value = channel.getResult(progressNs / 1e9f);
                        if (channel.type == AnimChannelType.ROTATION && value.length == 3) {
                            value = AnimMath.toQuaternion(value[0], value[1], value[2]);
                        }
                        valueRecover.add(value);
                        recoverChannels.add(channel);
                    }
                }
            }
        } else if (isTransitioning) {
            ArrayList<float[]> newValueFrom = new ArrayList<>();
            ArrayList<float[]> newValueRecover = new ArrayList<>();
            ArrayList<AnimChannel> newTransitionFromChannels = new ArrayList<>();
            ArrayList<AnimChannel> newTransitionToChannels = new ArrayList<>();
            ArrayList<AnimChannel> newRecoverChannels = new ArrayList<>();
            for (int i = 0; i < transitionFromChannels.size(); i++) {
                AnimChannel fromChannel = transitionFromChannels.get(i);
                AnimChannel toChannel = transitionToChannels.get(i);
                float[] from = valueFrom.get(i);
                float[] to = toChannel.getResult(this.transitionTo.progressNs / 1e9f);
                float[] result;
                float progress = easeOutCubic((float) transitionProgressNs / transitionTimeNs);
                if (fromChannel.type == AnimChannelType.TRANSLATION) {
                    result = new float[3];
                    lerp(from, to, progress, result);
                } else if (fromChannel.type == AnimChannelType.ROTATION) {
                    result = new float[4];
                    if (to.length == 3) {
                        to = AnimMath.toQuaternion(to[0], to[1], to[2]);
                    }
                    slerp(from, to, progress, result);
                } else {
                    result = new float[3];
                    lerp(from, to, progress, result);
                }

                List<AnimChannel> newToChannels = this.transitionTo.animation.getChannels().get(fromChannel.node);
                if (newToChannels != null) {
                    Optional<AnimChannel> newToChannel = newToChannels.stream()
                            .filter(c -> c.type == fromChannel.type).findAny();
                    if (newToChannel.isPresent()) {
                        newValueFrom.add(result);
                        newTransitionFromChannels.add(fromChannel);
                        newTransitionToChannels.add(newToChannel.get());
                        newToChannel.get().transitioning = true;
                    } else {
                        newValueRecover.add(result);
                        newRecoverChannels.add(fromChannel);
                    }
                } else {
                    newValueRecover.add(result);
                    newRecoverChannels.add(fromChannel);
                }
                toChannel.transitioning = false;
            }
            this.valueFrom = newValueFrom;
            this.valueRecover = newValueRecover;
            this.transitionToChannels = newTransitionToChannels;
            this.transitionFromChannels = newTransitionFromChannels;
            this.recoverChannels = newRecoverChannels;
            this.transitionTo = target;
        }
        this.transitionTimeNs = transitionTimeNs;
        this.transitionProgressNs = 0;
        this.isTransitioning = true;
    }

    public long getTransitionTimeNs() {
        return transitionTimeNs;
    }

    public long getTransitionProgressNs() {
        return transitionProgressNs;
    }

    public void setTransitionProgressNs(long progressNs) {
        this.transitionProgressNs = progressNs;
    }

    public void stopTransition() {
        isTransitioning = false;
        if (transitionToChannels != null) {
            for (AnimChannel channel : transitionToChannels) {
                channel.transitioning = false;
            }
        }
        this.transitionTimeNs = 0;
        this.transitionProgressNs = 0;
        this.transitionFromChannels = null;
        this.transitionToChannels = null;
        this.recoverChannels = null;
        this.valueFrom = null;
        this.valueRecover = null;
    }

    private void updateProgress(long alphaProgress) {
        if (running) {
            progressNs += alphaProgress;
        }
        switch (animation.playType) {
            case PLAY_ONCE_HOLD -> {
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    hold();
                }
            }
            case PLAY_ONCE_STOP -> {
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    stop();
                }
            }
            case LOOP -> {
                if (progressNs / 1e9 > animation.getMaxEndTimeS()) {
                    if (animation.getMaxEndTimeS() == 0) {
                        progressNs = 0;
                    } else {
                        progressNs = progressNs % (long) (animation.getMaxEndTimeS() * 1e9);
                    }
                }
            }
        }
    }

    public void update(boolean blend) {
        long alphaProgress = elapsedSinceLastUpdate();
        updateProgress(alphaProgress);
        if (isTransitioning) {
            transitionProgressNs += alphaProgress;
            if (transitionProgressNs >= transitionTimeNs) {
                stopTransition();
            } else {
                float progress = (float) transitionProgressNs / transitionTimeNs;
                updateTransition(easeOutCubic(progress), blend);
            }
        } else {
            animation.update(blend, progressNs);
        }
    }

    private long elapsedSinceLastUpdate() {
        long now = System.nanoTime();
        if (lastUpdateNs == 0L) {
            lastUpdateNs = now;
            return 0L;
        }
        long elapsed = Math.max(0L, now - lastUpdateNs);
        lastUpdateNs = now;
        // 与 TACZ GunTime / AnimationClock 一致：单人暂停时动画冻结
        if (Minecraft.getInstance().isPaused()) {
            return 0L;
        }
        return Math.min(elapsed, MAX_DELTA_NS);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPausing() {
        return pausing;
    }

    public boolean isHolding() {
        return progressNs == (long) (getAnimation().getMaxEndTimeS() * 1e9) + 1;
    }

    public boolean isStopped() {
        return progressNs == (long) (getAnimation().getMaxEndTimeS() * 1e9) + 2;
    }

    private void updateTransition(float progress, boolean blend) {
        if (transitionTo == null) {
            return;
        }
        for (int i = 0; i < transitionToChannels.size(); i++) {
            AnimChannel fromChannel = transitionFromChannels.get(i);
            AnimChannel toChannel = transitionToChannels.get(i);

            float[] from = valueFrom.get(i);
            float[] to = toChannel.getResult(transitionTo.progressNs / 1e9f);
            float[] result;
            if (fromChannel.type == AnimChannelType.TRANSLATION) {
                result = new float[3];
                lerp(from, to, progress, result);
            } else if (fromChannel.type == AnimChannelType.ROTATION) {
                result = new float[4];
                if (to.length == 3) {
                    to = AnimMath.toQuaternion(to[0], to[1], to[2]);
                }
                slerp(from, to, progress, result);
            } else {
                result = new float[3];
                lerp(from, to, progress, result);
            }
            for (AnimListener listener : fromChannel.getListeners()) {
                listener.update(result, blend);
            }
        }
        if (animation.playType != ObjectAnim.PlayType.PLAY_ONCE_STOP) {
            for (int i = 0; i < recoverChannels.size(); i++) {
                AnimChannel channel = recoverChannels.get(i);
                float[] from = valueRecover.get(i);
                float[] result;
                if (channel.type == AnimChannelType.TRANSLATION) {
                    result = new float[3];
                    lerp(from, new float[]{0f, 0f, 0f}, progress, result);
                    for (AnimListener listener : channel.getListeners()) {
                        listener.update(result, blend);
                    }
                } else if (channel.type == AnimChannelType.ROTATION) {
                    result = new float[4];
                    slerp(from, new float[]{0f, 0f, 0f, 1f}, progress, result);
                    for (AnimListener listener : channel.getListeners()) {
                        listener.update(result, blend);
                    }
                } else if (channel.type == AnimChannelType.SCALE) {
                    result = new float[3];
                    lerp(from, new float[]{1f, 1f, 1f}, progress, result);
                    for (AnimListener listener : channel.getListeners()) {
                        listener.update(result, blend);
                    }
                }
            }
        }
    }

    private static float easeOutCubic(double x) {
        return (float) (1 - Math.pow(1 - x, 4));
    }

    private static void lerp(float[] from, float[] to, float alpha, float[] result) {
        for (int i = 0; i < result.length; i++) {
            result[i] = from[i] * (1 - alpha) + to[i] * alpha;
        }
    }

    private static void slerp(float[] from, float[] to, float alpha, float[] result) {
        float[] out = AnimMath.slerp(from, to, alpha);
        System.arraycopy(out, 0, result, 0, Math.min(out.length, result.length));
    }
}
