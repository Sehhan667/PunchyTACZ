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
    private double lastX = Double.NaN;
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
            walkDistAnchor = walkDistance;
        }
    }

    public float getWalkDist() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0f;
        }
        updateWalkDistance(player);
        return walkDistance - walkDistAnchor;
    }

    /**
     * 旧版本可直接读 Entity.walkDist / walkDistO，新版已移除。
     * 这里按玩家水平位移累积，语义相同（只被动画进度取模使用）。
     */
    private void updateWalkDistance(LocalPlayer player) {
        double x = player.getX();
        double z = player.getZ();
        if (Double.isNaN(lastX)) {
            lastX = x;
            lastZ = z;
            return;
        }
        double dx = x - lastX;
        double dz = z - lastZ;
        lastX = x;
        lastZ = z;
        walkDistance += (float) Math.sqrt(dx * dx + dz * dz);
    }
}
