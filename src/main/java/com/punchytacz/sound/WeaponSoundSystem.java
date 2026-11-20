package com.punchytacz.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 照 TACZ 的 {@code SoundPlayManager}（只保留近战动画音效需要的部分）：
 * <ul>
 *   <li>统一播放入口：{@link SoundSource#PLAYERS} + 跟随玩家
 *       （暂停自动暂停、音量走"玩家"滑条）</li>
 *   <li>并发上限：同一音效 id 同时最多 {@link #CONCURRENCY_LIMIT} 个实例，
 *       超出停掉最旧的（对应 TACZ 的 HIGH_FREQUENCY_SOUND_CONCURRENCY_LIMIT，
 *       连击时 swing 音效不会叠成一团）</li>
 *   <li>周期清理：用 {@code SoundManager.isActive} 移除已播完的引用，
 *       不再依赖"播了多久"之类的猜测</li>
 *   <li>退出世界（level == null）时全部停止——之前 forUI 音效在退世界后还会继续响</li>
 * </ul>
 */
public final class WeaponSoundSystem {
    /** TACZ 高频音效并发上限的量级 */
    private static final int CONCURRENCY_LIMIT = 4;

    /** 音效 id → 仍在播的实例（播放顺序） */
    private static final Map<Identifier, ArrayDeque<WeaponSoundInstance>> TRACKED = new HashMap<>();
    private static int cleanupCounter;

    private WeaponSoundSystem() {
    }

    /** 播放一个动画音效；返回实例供调用方按"来源动画"追踪，无法播时返回 null */
    public static WeaponSoundInstance play(Identifier id, float volume, float pitch) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return null;
        }
        limitConcurrent(client, id);
        // sounds.json 只负责"id → 文件"，不会在 SOUND_EVENT 注册表里建条目，
        // 按现造可变距离 SoundEvent，SoundManager 用 sounds.json 解析文件（TACZ 同款做法）。
        var event = SoundEvent.createVariableRangeEvent(id);
        var instance = new WeaponSoundInstance(event, volume, pitch);
        client.getSoundManager().play(instance);
        TRACKED.computeIfAbsent(id, k -> new ArrayDeque<>()).addLast(instance);
        return instance;
    }

    /** 同一音效 id 的并发上限：超出停最旧的 */
    private static void limitConcurrent(Minecraft client, Identifier id) {
        ArrayDeque<WeaponSoundInstance> sounds = TRACKED.get(id);
        if (sounds == null) {
            return;
        }
        sounds.removeIf(i -> !client.getSoundManager().isActive(i));
        int toStop = sounds.size() - CONCURRENCY_LIMIT + 1;
        if (toStop <= 0) {
            return;
        }
        // ArrayDeque 迭代顺序即播放顺序：从头停 = 停最旧的
        Iterator<WeaponSoundInstance> it = sounds.iterator();
        int stopped = 0;
        while (it.hasNext() && stopped < toStop) {
            WeaponSoundInstance oldest = it.next();
            if (client.getSoundManager().isActive(oldest)) {
                oldest.setStop();
                stopped++;
            }
            it.remove();
        }
        if (sounds.isEmpty()) {
            TRACKED.remove(id);
        }
    }

    /** 在 END_CLIENT_TICK 调用：周期清理已播完的引用；退出世界时全部停止 */
    public static void clientTick(Minecraft client) {
        if (client.level == null) {
            for (ArrayDeque<WeaponSoundInstance> sounds : TRACKED.values()) {
                for (WeaponSoundInstance instance : sounds) {
                    instance.setStop();
                }
            }
            TRACKED.clear();
            return;
        }
        if (++cleanupCounter % 20 == 0) {
            Iterator<Map.Entry<Identifier, ArrayDeque<WeaponSoundInstance>>> it = TRACKED.entrySet().iterator();
            while (it.hasNext()) {
                ArrayDeque<WeaponSoundInstance> sounds = it.next().getValue();
                sounds.removeIf(i -> !client.getSoundManager().isActive(i));
                if (sounds.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    /** 停掉一组实例（切走物品 / 收起 rig 时），并从追踪里移除 */
    public static void stopAll(Collection<? extends SoundInstance> instances) {
        var soundManager = Minecraft.getInstance().getSoundManager();
        for (SoundInstance instance : instances) {
            soundManager.stop(instance);
        }
    }
}
