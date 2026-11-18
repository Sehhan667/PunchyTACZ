package com.punchytacz;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** ClothConfig 界面：把所有可疑变量摆出来，改完立刻生效 */
public final class ConfigScreen {
    private ConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("PunchyTACZ 渲染诊断"));

        ConfigCategory render = builder.getOrCreateCategory(Component.literal("渲染"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        render.addEntry(eb.startEnumSelector(Component.literal("RenderType"), Settings.Mode.class, Settings.mode)
                .setDefaultValue(Settings.Mode.TRANSLUCENT)
                .setTooltip(Component.literal("切完关掉本界面即生效。日志会打印 blend / cull / writeDepth 的真实值。"))
                .setSaveConsumer(v -> Settings.mode = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("UV 翻转 U"), Settings.flipU)
                .setDefaultValue(false)
                .setTooltip(Component.literal("顶点写出时翻 U（1-u），立即生效"))
                .setSaveConsumer(v -> Settings.flipU = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("UV 翻转 V"), Settings.flipV)
                .setDefaultValue(false)
                .setTooltip(Component.literal("顶点写出时翻 V（1-v），立即生效"))
                .setSaveConsumer(v -> Settings.flipV = v)
                .build());

        render.addEntry(eb.startFloatField(Component.literal("UV 偏移 U（贴图单位，0.5=半个条带）"), Settings.offsetU)
                .setDefaultValue(0.0F)
                .setMin(-8.0F)
                .setMax(8.0F)
                .setTooltip(Component.literal("正数向右平移。贴图条带周期 0.5，试 0.5 / 1.0 / 1.5 / 2.0"))
                .setSaveConsumer(v -> Settings.offsetU = v)
                .build());

        render.addEntry(eb.startFloatField(Component.literal("UV 偏移 V（贴图单位，0.5=半个条带）"), Settings.offsetV)
                .setDefaultValue(0.0F)
                .setMin(-8.0F)
                .setMax(8.0F)
                .setTooltip(Component.literal("正数向下平移。贴图条带周期 0.5，试 0.5 / 1.0 / 1.5 / 2.0"))
                .setSaveConsumer(v -> Settings.offsetV = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("提交武器几何"), Settings.submitGeo)
                .setDefaultValue(true)
                .setTooltip(Component.literal("关掉后如果刀还在，说明还有别的层在画"))
                .setSaveConsumer(v -> Settings.submitGeo = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("画手臂"), Settings.renderArms)
                .setDefaultValue(true)
                .setTooltip(Component.literal("关掉可以排除手臂遮住武器的可能"))
                .setSaveConsumer(v -> Settings.renderArms = v)
                .build());

        render.addEntry(eb.startEnumSelector(Component.literal("面UV重映射"), Settings.FaceMap.class, Settings.faceMap)
                .setDefaultValue(Settings.FaceMap.NONE)
                .setTooltip(Component.literal("对调某些方向的 UV 条目：外面黑内面正常 → 试 UD / NS / EW"))
                .setSaveConsumer(v -> Settings.faceMap = v)
                .build());
        render.addEntry(eb.startBooleanToggle(Component.literal("法线取反（外表面黑/内表面正常时试）"), Settings.flipNormal)
                .setDefaultValue(false)
                .setTooltip(Component.literal("26.3 的 entity 管线带 PER_FACE_LIGHTING，法线方向错了会被压成接近全黑"))
                .setSaveConsumer(v -> Settings.flipNormal = v)
                .build());
        render.addEntry(eb.startEnumSelector(Component.literal("镜像几何（left/right）"), Settings.Side.class, Settings.side)
                .setDefaultValue(Settings.Side.BOTH)
                .setTooltip(Component.literal("BOTH=都画（默认）；LEFT_ONLY/RIGHT_ONLY 用来验证是不是画了两份导致黑层"))
                .setSaveConsumer(v -> Settings.side = v)
                .build());
        render.addEntry(eb.startStrField(Component.literal("贴图覆盖（留空=用包内，如 wt:melee/observer_uv_s）"), Settings.textureOverride)
                .setDefaultValue("")
                .setSaveConsumer(v -> Settings.textureOverride = v == null ? "" : v)
                .build());
        render.addEntry(eb.startBooleanToggle(Component.literal("交回 Punchy 渲染"), Settings.punchyRender)
                .setDefaultValue(false)
                .setTooltip(Component.literal("true = 第一人称完全交给 Punchy（外观正常，TACZ 状态机不生效）"))
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
