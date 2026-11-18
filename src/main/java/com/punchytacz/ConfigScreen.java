package com.punchytacz;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** ClothConfig 界面：只放真正会影响画面的开关，保存即生效 */
public final class ConfigScreen {
    private ConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("PunchyTACZ 渲染"));

        ConfigCategory render = builder.getOrCreateCategory(Component.literal("渲染"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        render.addEntry(eb.startEnumSelector(Component.literal("绘制方式"), Settings.Mode.class, Settings.mode)
                .setDefaultValue(Settings.Mode.CUTOUT)
                .setTooltip(Component.literal("CUTOUT = alpha 剪裁 + 写深度（推荐）。\n"
                        + "半透明模式不写深度，几百个层会叠在一起变暗。"))
                .setSaveConsumer(v -> Settings.mode = v)
                .build());

        render.addEntry(eb.startEnumSelector(Component.literal("贴图采样"), Settings.Filter.class, Settings.Filter.NEAREST)
                .setDefaultValue(Settings.Filter.NEAREST)
                .setTooltip(Component.literal("这套 UV 贴图每个面只覆盖 1~2 像素，\n"
                        + "LINEAR 会把相邻的透明黑像素混进来，把刀压成暗色。"))
                .setSaveConsumer(v -> Settings.filter = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("提交武器几何"), Settings.submitGeo)
                .setDefaultValue(true)
                .setTooltip(Component.literal("关掉后只剩手臂，用于确认画面上的刀是我们画的"))
                .setSaveConsumer(v -> Settings.submitGeo = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("画手臂"), Settings.renderArms)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Settings.renderArms = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("交回 Punchy 渲染"), Settings.punchyRender)
                .setDefaultValue(false)
                .setTooltip(Component.literal("true = 第一人称完全交给 Punchy（TACZ 状态机不生效）"))
                .setSaveConsumer(v -> Settings.punchyRender = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("日志打印渲染状态"), Settings.logState)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Settings.logState = v)
                .build());

        builder.setSavingRunnable(Settings::save);
        return builder.build();
    }
}
