package com.punchytacz.statemachine;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** lrtactical BaseAnimationStateContext + MeleeAnimationStateContext 的等价实现 */
public final class MeleeAnimationStateContext extends AnimationStateContext {
    private float partialTicks;
    private float putAwayTime;
    private int attackLeftCount;
    private float walkDistAnchor;
    private float walkDistance;
    private float tickDelta;
    private int lastTick = Integer.MIN_VALUE;
    private double lastX;
    private double lastY;
    private double lastZ;

    public float getPartialTicks() {
        return partialTicks;
    }

    public void setPartialTicks(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPutAwayTime() {
        return putAwayTime;
    }

    public void setPutAwayTime(float putAwayTime) {
        this.putAwayTime = putAwayTime;
    }

    public int getAttackLeftCount() {
        return attackLeftCount;
    }

    public void setAttackLeftCount(int attackLeftCount) {
        this.attackLeftCount = attackLeftCount;
    }

    /** 对应 lua 的 context:getActionCount("attack_left") */
    public int getActionCount(String action) {
        return "attack_left".equals(action) ? attackLeftCount : 0;
    }

    public long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    public boolean isInputUp() {
        return Minecraft.getInstance().options.keyUp.isDown();
    }

    public boolean isInputDown() {
        return Minecraft.getInstance().options.keyDown.isDown();
    }

    public boolean isInputLeft() {
        return Minecraft.getInstance().options.keyLeft.isDown();
    }

    public boolean isInputRight() {
        return Minecraft.getInstance().options.keyRight.isDown();
    }

    public boolean isInputJumping() {
        return Minecraft.getInstance().options.keyJump.isDown();
    }

    public boolean isOnGround() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.onGround();
    }

    public boolean isCrouching() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.isCrouching();
    }

    public boolean isSprinting() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.isSprinting();
    }

    public void anchorWalkDist() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            updateWalkDistance(player);
            walkDistAnchor = currentWalkDist();
        }
    }

    public float getWalkDist() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0f;
        }
        updateWalkDistance(player);
        return currentWalkDist() - walkDistAnchor;
    }

    /** 两个 tick 之间用 partialTicks 插值，平均速度仍等于原版 walkDist（每 tick 一次） */
    private float currentWalkDist() {
        return walkDistance - tickDelta + tickDelta * partialTicks;
    }

    /**
     * 对齐原版 {@code Entity.walkDist}：只在游戏 tick 变化时累积一次水平位移。
     * <p>
     * 之前是按渲染帧累积，帧率越高累积越快（60fps ≈ 3 倍），run 的
     * {@code setAnimationProgress(walkDist % 2 / 2)} 就会表现为“地面奔跑速度异常加快”。
     * 腾空时状态机走 run_hold、不用这个进度，所以只有脚着地时才明显。
     */
    private void updateWalkDistance(LocalPlayer player) {
        if (lastTick == Integer.MIN_VALUE) {
            lastTick = player.tickCount;
            lastX = player.getX();
            lastY = player.getY();
            lastZ = player.getZ();
            return;
        }
        if (player.tickCount == lastTick) {
            return;
        }
        lastTick = player.tickCount;
        double dx = player.getX() - lastX;
        double dy = player.getY() - lastY;
        double dz = player.getZ() - lastZ;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        // 原版 Entity.move 里是 walkDist += length(dx,dy,dz) * 0.6F，
        // 漏掉这个 0.6 会让 run 的 (walkDist % 2 / 2) 快 1/0.6 ≈ 1.67 倍。
        tickDelta = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.6F;
        walkDistance += tickDelta;
    }
}
