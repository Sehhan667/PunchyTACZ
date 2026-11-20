package com.punchytacz.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 照 TACZ 的 {@code GunSoundInstance}：
 * <ul>
 *   <li>{@link SoundSource#PLAYERS}：单人暂停时引擎的 {@code pauseAllExcept(MUSIC, UI)}
 *       会自动暂停它、恢复时自动继续。之前用 {@code forUI}（UI 类别）正好被豁免，
 *       所以暂停页面音效还在响。</li>
 *   <li>{@code relative + Attenuation.NONE}：跟随玩家（第一人称手持武器音效），
 *       无距离衰减，音量走"玩家"滑条。</li>
 *   <li>可随时 {@link #setStop()} 中断。</li>
 * </ul>
 */
public class WeaponSoundInstance extends AbstractSoundInstance {

    public WeaponSoundInstance(SoundEvent soundEvent, float volume, float pitch) {
        super(soundEvent, SoundSource.PLAYERS, RandomSource.create(943));
        this.attenuation = Attenuation.NONE;
        this.relative = true;
        this.volume = volume;
        this.pitch = pitch;
        this.x = 0;
        this.y = 0;
        this.z = 0;
    }

    /** 立即停止（TACZ GunSoundInstance.setStop） */
    public void setStop() {
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    @Override
    public String toString() {
        return "WeaponSound[" + identifier + "]";
    }

    /** 安抚编译器：没有实例字段也实现接口要求 */
    @Override
    public boolean canPlaySound() {
        return Minecraft.getInstance().player != null;
    }
}
