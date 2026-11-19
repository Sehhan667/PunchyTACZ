package com.punchytacz.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** 移植 TACZ AnimationController：轨道、混合标志、动画队列与按轨道行顺序更新 */
public final class AnimationController {
    private final ArrayList<AnimRunner> currentRunners = new ArrayList<>();
    private final ArrayList<Boolean> blending = new ArrayList<>();
    private final AnimListenerSupplier listenerSupplier;
    private final ArrayList<Queue<AnimationPlan>> animationQueue = new ArrayList<>();
    private final Map<String, ObjectAnim> prototypes = new HashMap<>();
    private Iterable<Integer> updatingTrackArray;
    /** 本帧跨过的音效请求（渲染层每帧消费） */
    private final List<String> pendingSounds = new ArrayList<>();

    public AnimationController(List<ObjectAnim> animationPrototypes, AnimListenerSupplier listenerSupplier) {
        for (ObjectAnim prototype : animationPrototypes) {
            if (prototype != null) {
                prototypes.put(prototype.name, prototype);
            }
        }
        this.listenerSupplier = listenerSupplier;
    }

    public void providePrototypeIfAbsent(String name, java.util.function.Supplier<ObjectAnim> supplier) {
        if (!prototypes.containsKey(name)) {
            prototypes.put(name, supplier.get());
        }
    }

    public boolean containPrototype(String name) {
        return prototypes.containsKey(name);
    }

    public AnimRunner getAnimation(int track) {
        if (track < 0 || track >= currentRunners.size()) {
            return null;
        }
        return currentRunners.get(track);
    }

    public void removeAnimation(int track) {
        if (track >= 0 && track < currentRunners.size()) {
            currentRunners.set(track, null);
        }
        if (track >= 0 && track < animationQueue.size()) {
            animationQueue.set(track, null);
        }
    }

    /** 清空所有轨道（重新初始化状态机前使用，避免旧动画残留） */
    public synchronized void clear() {
        currentRunners.clear();
        animationQueue.clear();
        blending.clear();
    }

    public void queueAnimation(int track, Queue<AnimationPlan> queue) {
        if (track < 0) {
            return;
        }
        for (int i = animationQueue.size(); i <= track; i++) {
            animationQueue.add(null);
        }
        animationQueue.set(track, queue);
        if (queue != null) {
            AnimationPlan plan = null;
            while (plan == null && !queue.isEmpty()) {
                plan = queue.poll();
            }
            if (plan != null) {
                run(track, plan.animationName(), plan.playType(), plan.transitionTimeS());
            }
        }
    }

    public void runAnimation(int track, String animationName, ObjectAnim.PlayType playType, float transitionTimeS) {
        if (track < 0) {
            return;
        }
        if (track < animationQueue.size()) {
            animationQueue.set(track, null);
        }
        run(track, animationName, playType, transitionTimeS);
    }

    private synchronized void run(int track, String animationName, ObjectAnim.PlayType playType, float transitionTimeS) {
        ObjectAnim prototype = prototypes.get(animationName);
        if (prototype == null) {
            return;
        }
        for (int i = currentRunners.size(); i <= track; i++) {
            currentRunners.add(null);
        }

        ObjectAnim animation = new ObjectAnim(prototype);
        animation.applyAnimationListeners(listenerSupplier);
        animation.playType = playType;
        AnimRunner runner = new AnimRunner(animation);
        runner.setProgressNs(0);
        runner.run();

        AnimRunner oldRunner = currentRunners.get(track);
        if (transitionTimeS > 0 && oldRunner != null) {
            oldRunner.transition(runner, (long) (transitionTimeS * 1e9));
        } else {
            currentRunners.set(track, runner);
        }
    }

    public void setBlending(int track, boolean blend) {
        if (track < 0) {
            return;
        }
        for (int i = blending.size(); i <= track; i++) {
            blending.add(false);
        }
        blending.set(track, blend);
    }

    public Iterable<Integer> getUpdatingTrackArray() {
        return updatingTrackArray;
    }

    public void setUpdatingTrackArray(Iterable<Integer> updatingTrackArray) {
        this.updatingTrackArray = updatingTrackArray;
    }

    public synchronized void update() {
        if (updatingTrackArray != null) {
            updatingTrackArray.forEach(this::updateByTrack);
        } else {
            for (int i = 0; i < currentRunners.size(); i++) {
                updateByTrack(i);
            }
        }
    }

    private void updateByTrack(int track) {
        if (track < 0 || track >= currentRunners.size()) {
            return;
        }
        boolean blend = track < blending.size() && blending.get(track);
        AnimRunner runner = currentRunners.get(track);
        if (runner == null) {
            return;
        }
        if (runner.isRunning() || runner.isHolding() || runner.isPausing() || runner.isTransitioning()) {
            runner.update(blend);
        }
        if (runner.getTransitionTo() != null) {
            runner.getTransitionTo().update(blend);
            if (!runner.isTransitioning()) {
                currentRunners.set(track, runner.getTransitionTo());
                runner = runner.getTransitionTo();
            }
        }
        if ((runner.isHolding() || runner.isStopped()) && !runner.isTransitioning()) {
            if (track < animationQueue.size()) {
                Queue<AnimationPlan> queue = animationQueue.get(track);
                if (queue != null) {
                    AnimationPlan plan = null;
                    while (plan == null && !queue.isEmpty()) {
                        plan = queue.poll();
                    }
                    if (plan != null) {
                        run(track, plan.animationName(), plan.playType(), plan.transitionTimeS());
                    }
                }
            }
        }
        collectSounds(runner);
    }

    private void collectSounds(AnimRunner runner) {
        if (runner == null) {
            return;
        }
        // 源 runner 与过渡目标都可能本帧推进过（目标在 updateByTrack 里单独 update）
        pendingSounds.addAll(runner.consumeSoundEvents());
        if (runner.getTransitionTo() != null) {
            pendingSounds.addAll(runner.getTransitionTo().consumeSoundEvents());
        }
    }

    /** 取出本帧跨过的音效请求并清空 */
    public List<String> consumeSoundEvents() {
        if (pendingSounds.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(pendingSounds);
        pendingSounds.clear();
        return result;
    }
}
