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

        render.addEntry(eb.startEnumSelector(Component.literal("只画半边（诊断）"), Settings.Half.class, Settings.half)
                .setDefaultValue(Settings.Half.BOTH)
                .setTooltip(Component.literal("模型由 left/right 两套镜像几何叠成。\n"
                        + "只画一边可以确认黑色是不是另一边画出来的。"))
                .setSaveConsumer(v -> Settings.half = v)
                .build());

        render.addEntry(eb.startEnumSelector(Component.literal("渲染管线"), Settings.Pipeline.class, Settings.pipeline)
                .setDefaultValue(Settings.Pipeline.VANILLA)
                .setTooltip(Component.literal("VANILLA=原版 entity 管线（对照）\n"
                        + "FLAT=自建管线，不做逐面方向光\n"
                        + "FULLBRIGHT=自建管线，不乘光照贴图\n"
                        + "SOLID=自建管线+纯白，排除贴图只看几何"))
                .setSaveConsumer(v -> Settings.pipeline = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("剔除背面（自建管线）"), Settings.cull)
                .setDefaultValue(false)
                .setTooltip(Component.literal("相邻 cube 的暗面与亮面共面，不剔除会深度冲突出黑块"))
                .setSaveConsumer(v -> Settings.cull = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("对调 east/west 的 UV（修复项）"), Settings.swapEastWest)
                .setDefaultValue(true)
                .setTooltip(Component.literal("这套包的逐面 UV 里 east/west 两个键与我们的面朝向约定互换，\n"
                        + "不对调刀身就是黑的。遇到不遵守这套约定的包时才需要关掉。\n"
                        + "切换后会重新解析模型。"))
                .setSaveConsumer(v -> Settings.swapEastWest = v)
                .build());

        render.addEntry(eb.startEnumSelector(Component.literal("翻转方式（诊断）"), Settings.Flip.class, Settings.flip)
                .setDefaultValue(Settings.Flip.ZP180)
                .setTooltip(Component.literal("TACZ 原本用 ZP180 把基岩模型翻正；\n"
                        + "这一滚也会对调刀的正反面。NONE 会上下颠倒，只看颜色。"))
                .setSaveConsumer(v -> Settings.flip = v)
                .build());

        render.addEntry(eb.startEnumSelector(Component.literal("面朝向调试（只画一个方向）"), Settings.FaceDebug.class, Settings.faceDebug)
                .setDefaultValue(Settings.FaceDebug.NONE)
                .setTooltip(Component.literal("只绘制指定方向的面并输出纯色：\n"
                        + "EAST=红 WEST=绿 UP=蓝 DOWN=黄 NORTH=青 SOUTH=品红。\n"
                        + "用来确认该方向的面在刀身的哪一侧。"))
                .setSaveConsumer(v -> Settings.faceDebug = v)
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

        render.addEntry(eb.startBooleanToggle(Component.literal("播放动画音效"), Settings.playAnimSounds)
                .setDefaultValue(true)
                .setTooltip(Component.literal("播放动画文件 sound_effects 里的音效（按最终播放的\n"
                        + "动画名解析，angel 形态用 inspect_angel 的音效表）。\n"
                        + "若出现双重音效（Punchy 也在播它自己的），关掉这个开关。"))
                .setSaveConsumer(v -> Settings.playAnimSounds = v)
                .build());

        render.addEntry(eb.startBooleanToggle(Component.literal("日志打印渲染状态"), Settings.logState)
                .setDefaultValue(true)
                .setSaveConsumer(v -> Settings.logState = v)
                .build());

        builder.setSavingRunnable(() -> {
            Settings.save();
            // SWAP_EW / SWAP_UD 在烘焙期生效，cull 烘焙进 pipeline，保存后都要重建
            ItemPipeline.invalidate();
            ItemRenderType.clear();
            TaczPacks.reload();
            TaczRenderer.resetActive();
        });
        return builder.build();
    }
}
