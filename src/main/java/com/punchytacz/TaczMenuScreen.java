package com.punchytacz;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import punchy.config.PunchyTuningConfig;

import java.util.List;

/**
 * 动画包选择菜单（仿 TACZ 改枪页：左右两栏、背景保持世界可见）。
 * <p>
 * 切换直接调用 Punchy 公开的槽位选择 API（{@code setMixPackSelection}），由 Punchy 自己持久化；
 * 我们的渲染每帧读 {@code getMixPreferredOptionForStack}，所以切换**立即生效、无需重载资源包**。
 * <p>
 * 菜单打开期间第一人称武器会进入展示姿态（见 {@code TaczRenderer} 的 showcase 分支），
 * 世界保持可见，可以边看模型边换包。
 */
public final class TaczMenuScreen extends Screen {
    private static final int PANEL_W = 235;
    private static final int ROW_H = 24;
    private static final int TOP = 56;

    private final Screen parent;
    private List<String> slots = List.of();
    private int selected;

    public TaczMenuScreen(Screen parent) {
        super(Component.literal("PunchyTACZ 动画包选择"));
        this.parent = parent;
    }

    /** Screen 的当前实例由本类自持（26.3 的 Minecraft 没有公开的 screen getter） */
    private static boolean open;
    /** 展示姿态的旋转与缩放（TaczRenderer 的 showcase 分支读取） */
    private static float showcaseYaw;
    private static float showcasePitch;
    private static float showcaseZoom = 1.0F;

    public static boolean isOpen() {
        return open;
    }

    public static float showcaseYaw() {
        return showcaseYaw;
    }

    public static float showcasePitch() {
        return showcasePitch;
    }

    public static float showcaseZoom() {
        return showcaseZoom;
    }

    private static void adjustYaw(float delta) {
        showcaseYaw = (showcaseYaw + delta) % 360.0F;
    }

    private static void adjustPitch(float delta) {
        showcasePitch = Math.max(-60.0F, Math.min(60.0F, showcasePitch + delta));
    }

    private static void adjustZoom(float factor) {
        showcaseZoom = Math.max(0.4F, Math.min(2.5F, showcaseZoom * factor));
    }

    @Override
    protected void init() {
        clearWidgets();
        slots = PunchyTuningConfig.getMixPackSlotIds();
        if (selected >= slots.size()) {
            selected = 0;
        }
        int left = 14;
        int right = left + PANEL_W + 18;
        int maxRows = Math.max(1, (height - TOP - 40) / ROW_H);

        // 左栏：物品分组（槽位）
        for (int i = 0; i < slots.size() && i < maxRows; i++) {
            final int index = i;
            String slot = slots.get(i);
            String label = PunchyTuningConfig.getMixPackSlotLabel(slot);
            if (label == null || label.isBlank()) {
                label = slot;
            }
            String current = PunchyTuningConfig.getMixPackSelectedOption(slot);
            String name = i == selected ? "▶ " + label : label;
            addRenderableWidget(Button.builder(
                            Component.literal(name + "  " + shortOption(current)),
                            b -> {
                                selected = index;
                                rebuildWidgets();
                            })
                    .bounds(left, TOP + i * ROW_H, PANEL_W, 20)
                    .build());
        }

        // 右栏：该分组可用的动画包选项（点选即切换并持久化）
        if (!slots.isEmpty()) {
            String slot = slots.get(selected);
            List<String> options = PunchyTuningConfig.getMixPackSlotOptionIds(slot);
            String current = PunchyTuningConfig.getMixPackSelectedOption(slot);
            for (int i = 0; i < options.size() && i < maxRows; i++) {
                String option = options.get(i);
                boolean isTacz = TaczPacks.isTaczPackOption(TaczPacks.packOptionOf(option));
                String prefix = option.equals(current) ? "● " : (isTacz ? "◆ " : "○ ");
                addRenderableWidget(Button.builder(
                                Component.literal(prefix + shortOption(option)),
                                b -> {
                                    PunchyTuningConfig.setMixPackSelection(slot, option);
                                    rebuildWidgets();
                                })
                        .bounds(right, TOP + i * ROW_H, PANEL_W, 20)
                        .build());
            }
        }

        int bottomY = height - 30;
        // 展示姿态控制（观赏模型）：旋转 / 缩放
        int midY = bottomY - 24;
        addRenderableWidget(Button.builder(Component.literal("旋转 ◀"),
                        b -> adjustYaw(-15.0F)).bounds(width / 2 - 235, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("旋转 ▶"),
                        b -> adjustYaw(15.0F)).bounds(width / 2 - 175, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("俯仰 ▲"),
                        b -> adjustPitch(10.0F)).bounds(width / 2 - 115, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("俯仰 ▼"),
                        b -> adjustPitch(-10.0F)).bounds(width / 2 - 55, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("缩小"),
                        b -> adjustZoom(1 / 1.15F)).bounds(width / 2 + 5, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("放大"),
                        b -> adjustZoom(1.15F)).bounds(width / 2 + 65, midY, 55, 20).build());
        addRenderableWidget(Button.builder(Component.literal("复位视角"),
                        b -> {
                            showcaseYaw = 0;
                            showcasePitch = 0;
                            showcaseZoom = 1.0F;
                        }).bounds(width / 2 + 125, midY, 110, 20).build());
        addRenderableWidget(Button.builder(
                        Component.literal("恢复默认（按资源包顺序）"),
                        b -> {
                            PunchyTuningConfig.resetMixPackSelectionsToResourcepackOrder();
                            rebuildWidgets();
                        })
                .bounds(width / 2 - 235, bottomY, 230, 20)
                .build());
        addRenderableWidget(Button.builder(
                        Component.literal("完成"),
                        b -> onClose())
                .bounds(width / 2 + 5, bottomY, 110, 20)
                .build());
    }

    /** pack:file/punchy刀包移植 → punchy刀包移植；punchy:punchy → 内置包 */
    private static String shortOption(String option) {
        if (option == null) {
            return "-";
        }
        if (option.startsWith("pack:")) {
            option = option.substring("pack:".length());
        }
        if (option.equals("punchy:punchy")) {
            return "内置包";
        }
        return option;
    }

    @Override
    public void added() {
        open = true;
    }

    @Override
    public void removed() {
        open = false;
    }

    @Override
    public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
        // 不画菜单背景：保持世界可见，方便边看武器边换包
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int right = 14 + PANEL_W + 18;
        graphics.fill(10, TOP - 22, 10 + PANEL_W + 4, height - 34, 0x90000000);
        graphics.fill(right - 4, TOP - 22, right - 4 + PANEL_W + 4, height - 34, 0x90000000);
        graphics.text(font, Component.literal("物品分组（槽位）"), 14, TOP - 18, 0xFFFFFF);
        graphics.text(font, Component.literal("动画包（◆=TACZ 包）"), right, TOP - 18, 0xFFFFFF);

        // 手持物品当前实际生效的动画包
        var player = minecraft.player;
        if (player != null) {
            var stack = player.getMainHandItem();
            String resolved;
            try {
                resolved = PunchyTuningConfig.getMixPreferredOptionForStack(stack);
            } catch (Throwable t) {
                resolved = "-";
            }
            graphics.text(font, Component.literal("手持物品当前动画包: " + shortOption(resolved)),
                    14, TOP - 40, 0xFFFFA0);
        }
        graphics.text(font, Component.literal("提示: ◆ 为带 TACZ 动画的包；切换立即生效，无需重载"),
                14, height - 42, 0xA0C0FF);
        // 按钮最后画，压在面板上面
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        open = false;
        minecraft.setScreenAndShow(parent);
    }
}
