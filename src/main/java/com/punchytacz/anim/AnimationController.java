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

    /**
     * 一条音效请求：{@code effect} 是音效 id；{@code animation} 是**产生它**的动画名。
     * <p>
     * 不能在渲染层"看主轨道当前是谁"来猜来源：带过渡的 runAnimation 里，新 runner
     * 挂在旧 runner 的 {@code transitionTo} 上，{@code getAnimation(track)} 返回的是
     * **旧动画** —— 过渡窗口（动画开头 0.2s，恰好是每条时间线第一个音效的位置）里
     * 触发的音效会被错标成旧动画，过渡一完成就被"来源已换"停掉。所以来源必须由
     * 产生事件的那个 runner 自己报告。
     */
    public record SoundRequest(String animation, String effect) {
    }

    /** 本帧跨过的音效请求（渲染层每帧消费） */
    private final List<SoundRequest> pendingSoundRequests = new ArrayList<>();

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
        pendingSoundRequests.clear();
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
        // 同一轨道正在**循环播放同一条**动画时，不要重建 runner。
        // 刀包的 lua 在落地时会重新 runAnimation("run", LOOP)，而包里没有 run_hold
        // （空中那次 runAnimation 是 no-op），于是落地这一下会把正在跑的 run 相位清零
        // ——表现就是「每次落地卡一下」。TACZ 的枪包有 run_hold，落地前后是两条不同动画，
        // 所以它不会触发这条路径。
        if (playType == ObjectAnim.PlayType.LOOP && track < currentRunners.size()) {
            AnimRunner current = currentRunners.get(track);
            if (current != null && current.animation() != null
                    && animationName.equals(current.animation().name)
                    && current.animation().playType == ObjectAnim.PlayType.LOOP) {
                return;
            }
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
        // 源 runner 与过渡目标都可能本帧推进过（目标在 updateByTrack 里单独 update），
        // 每条请求都带上**产生它的那个 runner** 的动画名
        collectInto(runner);
        if (runner.getTransitionTo() != null) {
            collectInto(runner.getTransitionTo());
        }
    }

    private void collectInto(AnimRunner runner) {
        for (String effect : runner.consumeSoundEvents()) {
            pendingSoundRequests.add(new SoundRequest(runner.getAnimation().name, effect));
        }
    }

    /** 取出本帧跨过的音效请求并清空 */
    public List<SoundRequest> consumeSoundRequests() {
        if (pendingSoundRequests.isEmpty()) {
            return List.of();
        }
        List<SoundRequest> result = new ArrayList<>(pendingSoundRequests);
        pendingSoundRequests.clear();
        return result;
    }

    /**
     * 所有轨道上"当前生效"的动画名：带过渡的轨道沿过渡链取**最终目标**——
     * 切换发生的第一刻，旧动画就算被替换（这正是"切换动画 = 打断音效"的判定依据）。
     * 自然播完（PLAY_ONCE_STOP/STOP）的 runner 仍停在轨道上，所以它的名字仍在集合里，
     * 尾部音效可以播完。
     */
    public java.util.Set<String> liveAnimationNames() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (AnimRunner runner : currentRunners) {
            AnimRunner r = runner;
            while (r != null && r.isTransitioning() && r.getTransitionTo() != null) {
                r = r.getTransitionTo();
            }
            if (r != null && r.getAnimation() != null) {
                names.add(r.getAnimation().name);
            }
        }
        return names;
    }
}
