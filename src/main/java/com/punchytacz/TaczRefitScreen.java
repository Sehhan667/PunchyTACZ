package com.punchytacz;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import punchy.config.PunchyTuningConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 改枪屏 —— 骨架照 TACZ 的 {@code GunRefitScreen}：
 * <ul>
 *   <li>不画背景（TACZ 的 {@code renderBackground} / {@code renderBlurredBackground} 都是空的），世界直接可见；</li>
 *   <li>武器由第一人称渲染器从手里"飞"到镜头前（见 {@code TaczRenderer.applyRefitPlacement}，
 *       0.25s，对齐 TACZ 的 {@code RefitTransform}）；</li>
 *   <li>GUI 只叠文字和左右翻页按钮（TACZ 用 {@code RefitTurnPageButton}）。</li>
 * </ul>
 *
 * <p>绘制只用 {@code fill} + {@code text(font, Component, x, y, color)} —— 这是本工程里
 * 唯一被验证过能画出来的两个调用，不要再引入 {@code centeredText} / {@code nextStratum}。
 */
public final class TaczRefitScreen extends Screen {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("punchytacz");

    private static final int ARROW_W = 40;
    private static final int ARROW_H = 64;
    private static final int EDGE = 18;

    private static boolean open;
    private static TaczRefitScreen instance;
    private static boolean justOpened;
    private static float yaw;
    private static float pitch;
    private static float zoom = 1.0F;

    private List<TaczPacks.Weapon> candidates = List.of();
    private int index;
    /** true = 只对当前这一把物品生效，false = 对整类物品生效 */
    private boolean itemOnly;
    private boolean dragging;
    private double dragX;
    private double dragY;

    public TaczRefitScreen() {
        super(Component.literal("PunchyTACZ"));
    }

    public static boolean isOpen() {
        return open;
    }

    public static boolean justOpened() {
        boolean value = justOpened;
        justOpened = false;
        return value;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static float zoom() {
        return zoom;
    }

    /** 屏内正在预览的武器：还没分配时渲染用它兜底，保证屏里看得到模型 */
    public static TaczPacks.Weapon previewWeapon() {
        TaczRefitScreen screen = instance;
        if (screen == null || screen.candidates.isEmpty()) {
            return null;
        }
        return screen.candidates.get(Math.floorMod(screen.index, screen.candidates.size()));
    }

    private static ItemStack held() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    /**
     * 生命周期放在 {@code init()} 里而不是 {@code added()}：26.3 的 {@code added()}
     * 没有可靠地被调用（实测屏开着但 {@code isOpen()} 还是 false，
     * 于是武器留在右下角继续播动画）。
     */
    @Override
    protected void init() {
        open = true;
        justOpened = true;
        instance = this;
        yaw = 0;
        pitch = 0;
        zoom = 1.0F;
        try {
            refresh();
            addWidgets();
            ItemStack stack = held();
            LOG.info("punchytacz: 改枪屏打开 kind={} 候选={} 把 → {}",
                    TaczPacks.kindOf(stack), candidates.size(),
                    candidates.stream().map(TaczPacks.Weapon::id).limit(8).toList());
        } catch (Throwable t) {
            LOG.error("punchytacz: 改枪屏初始化失败", t);
        }
    }

    /** GUI 元素一律用 widget —— 26.3 的 Screen 由 super.extractRenderState 统一画 widget，
     *  自己用 graphics.fill/text 画的东西很容易被覆盖或画不出来（TACZ 也是全用 widget）。 */
    private void addWidgets() {
        int y = height / 2 - 30;
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                        Component.literal("<"), b -> step(-1))
                .bounds(EDGE, y, 36, 60).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                        Component.literal(">"), b -> step(1))
                .bounds(width - EDGE - 36, y, 36, 60).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                        Component.literal(scopeLabel()), b -> {
                            itemOnly = !itemOnly;
                            refresh();
                            rebuildWidgets();
                        })
                .bounds(width / 2 - 80, height - 52, 160, 20).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
                        Component.literal("应用"), b -> apply())
                .bounds(width / 2 - 80, height - 28, 160, 20).build());
    }

    /** 只有点「应用」才写入选择并切到宿主包；箭头只是预览 */
    private void apply() {
        if (candidates.isEmpty()) {
            return;
        }
        ItemStack stack = held();
        String weaponId = candidates.get(index).id();
        TaczSelection.put(scopeKey(stack), weaponId);
        boolean host = ensureHostSelected(itemOnly);
        TaczRenderer.resetActive();
        LOG.info("punchytacz: 应用武器 {}（{}）→ 宿主包={}", weaponId, scopeKey(stack), host);
    }

    /** 当前预览的是不是已经生效的那把 */
    private boolean applied() {
        if (candidates.isEmpty()) {
            return true;
        }
        return candidates.get(index).id().equals(TaczSelection.get(scopeKey(held())));
    }

    private String scopeLabel() {
        return itemOnly ? "仅此物品" : "整类物品";
    }

    private void refresh() {
        ItemStack stack = held();
        candidates = new ArrayList<>(TaczPacks.weapons());
        String chosen = TaczSelection.get(scopeKey(stack));
        index = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).id().equals(chosen)) {
                index = i;
                break;
            }
        }
    }

    private String scopeKey(ItemStack stack) {
        return itemOnly ? TaczSelection.itemKey(stack)
                : TaczSelection.kindKey(TaczPacks.kindOf(stack));
    }

    /** 箭头只切预览，不写配置、不动 Punchy；要生效请点「应用」 */
    private void step(int dir) {
        if (candidates.isEmpty()) {
            return;
        }
        index = Math.floorMod(index + dir, candidates.size());
        // 预览模型靠 previewWeapon() 传递给渲染器，所以这里清一下当前 rig，
        // 让下一帧按新的武器重建（重建即重播 draw）
        TaczRenderer.resetActive();
    }

    private int arrowX(boolean left) {
        return left ? EDGE : width - EDGE - ARROW_W;
    }

    private int arrowY() {
        return height / 2 - ARROW_H / 2;
    }

    @Override
    public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
        // TACZ 的 GunRefitScreen 也不画背景
    }

    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
        // 不画模糊背景
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 不画暗化背景（TACZ 同样留空，世界原样可见）
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // 顺序必须和 TACZ 一致：**先** super（把 widget 全部提交），再画自己的文字
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        try {
            drawText(graphics);
        } catch (Throwable t) {
            LOG.error("punchytacz: 改枪屏绘制失败", t);
        }
    }

    private void drawText(GuiGraphicsExtractor graphics) {
        String title = candidates.isEmpty() ? "没有可用的 TACZ 武器" : candidates.get(index).name();
        String sub = candidates.isEmpty() ? ""
                : candidates.get(index).id()
                + (candidates.size() > 1 ? "   " + (index + 1) + "/" + candidates.size() : "");
        drawCentered(graphics, title, 28, 0xFFFFFFFF);
        if (!sub.isEmpty()) {
            drawCentered(graphics, sub, 44, 0xFFC0C0C0);
        }
        if (!applied()) {
            drawCentered(graphics, "未应用 · 点下方「应用」生效", 62, 0xFFFFD060);
        }
    }

    private void drawCentered(GuiGraphicsExtractor graphics, String text, int y, int color) {
        Font f = this.font != null ? this.font : Minecraft.getInstance().font;
        if (f == null) {
            return;
        }
        int x = (width - f.width(text)) / 2;
        graphics.text(f, Component.literal(text), x, y, color);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 先让 widget 处理：之前无条件 return true，把按钮的点击全吞了（整类物品点不动）
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true;
            dragX = event.x();
            dragY = event.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            yaw += (float) (event.x() - this.dragX) * 0.6F;
            pitch = Math.max(-80.0F, Math.min(80.0F, pitch + (float) (event.y() - this.dragY) * 0.6F));
            this.dragX = event.x();
            this.dragY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            zoom = Math.max(0.4F, Math.min(3.0F, zoom * (scrollY > 0 ? 1.1F : 1 / 1.1F)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.keycode() == InputConstants.KEY_LEFT) {
            step(-1);
            return true;
        }
        if (event.keycode() == InputConstants.KEY_RIGHT) {
            step(1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        open = false;
        instance = null;
    }

    @Override
    public void onClose() {
        open = false;
        instance = null;
        minecraft.setScreenAndShow(null);
    }

    /**
     * 把 Punchy 的包选择切到宿主包（= 打开 TACZ 渲染）。
     * <p>
     * Punchy 的槽位有两种（{@code MixSlotType}）：{@code item:<id>} 和 {@code kind:<lowerKind>}，
     * 而且 {@code resolveMixSlotForStack} **优先用 item 槽位**。所以：
     * <ul>
     *   <li>「仅此物品」→ 只改 item 槽位；</li>
     *   <li>「整类物品」→ 改 kind 槽位，同时把已存在的 item 槽位也对齐，
     *       否则那件物品仍会走自己的 item 槽位，看起来像"改了没生效"。</li>
     * </ul>
     * 以前这里用 {@code slot.equalsIgnoreCase(kind)} 匹配，而实际 id 是 {@code kind:sword}，
     * 永远匹配不上，只能靠末尾的兜底分支碰运气。
     */
    public static boolean ensureHostSelected(boolean itemOnly) {
        ItemStack stack = held();
        if (stack.isEmpty()) {
            return false;
        }
        try {
            List<String> slots = PunchyTuningConfig.getMixPackSlotIds();
            if (slots.isEmpty()) {
                LOG.warn("punchytacz: Punchy 没有可用的 mix 槽位（MIX_CATALOG 为空）");
                return false;
            }
            String specific = TaczPacks.specificSlotFor(stack);
            String kindSlot = "kind:" + TaczPacks.kindOf(stack).toLowerCase(java.util.Locale.ROOT);
            boolean specificSelected = specific != null && slots.contains(specific)
                    && hostIn(PunchyTuningConfig.getMixPackSlotOptionIds(specific)) != null;
            boolean kindSelected = slots.contains(kindSlot)
                    && hostIn(PunchyTuningConfig.getMixPackSlotOptionIds(kindSlot)) != null;

            if (itemOnly) {
                if (specificSelected) {
                    return selectHost(specific);
                }
                // 这件物品没有独立槽位，只能退到 kind 槽位（Punchy 自己也是这个优先级）
                return kindSelected && selectHost(kindSlot);
            }
            boolean any = false;
            if (kindSelected) {
                any |= selectHost(kindSlot);
            }
            if (specificSelected) {
                any |= selectHost(specific);
            }
            return any;
        } catch (Throwable t) {
            LOG.warn("punchytacz: 切换宿主包失败", t);
            return false;
        }
    }

    /** 把某个槽位设成宿主包 */
    private static boolean selectHost(String slot) {
        String host = hostIn(PunchyTuningConfig.getMixPackSlotOptionIds(slot));
        if (host == null) {
            return false;
        }
        return PunchyTuningConfig.setMixPackSelection(slot, host);
    }

    /** 打开前把 Punchy 的包选择切到宿主包（整类）。保留给旧调用点。 */
    public static boolean ensureHostSelected() {
        return ensureHostSelected(false);
    }

    private static String hostIn(List<String> options) {
        for (String option : options) {
            if (TaczPacks.isHostOption(option)) {
                return option;
            }
        }
        return null;
    }
}
