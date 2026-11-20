package com.punchytacz;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import punchy.api.PunchyApi;

public final class PunchyTACZClient implements ClientModInitializer {
    public static final String MOD_ID = "punchytacz";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 打开改枪屏的按键（默认 G，可在控制设置里改） */
    private static final KeyMapping.Category MENU_CATEGORY =
            KeyMapping.Category.register(Identifier.parse("punchytacz:menu"));
    private static final KeyMapping REFIT_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.punchytacz.refit", InputConstants.Type.KEYBOARD, 71, MENU_CATEGORY));
    /** 检视动画（Punchy 的检视键没有公开 API，这里自己开一个，默认 V） */
    private static final KeyMapping INSPECT_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.punchytacz.inspect", InputConstants.Type.KEYBOARD, 86, MENU_CATEGORY));

    /**
     * Punchy 自己的检视键（{@code key.punchy.inspect}）。
     * <p>
     * 我们不再自己开一个键：玩家已经习惯按 Punchy 的检视键，而 Punchy 没有公开 API
     * 暴露这个 KeyMapping，只能从 {@code options.keyMappings} 里按翻译键找。
     * 用 {@code isDown()} 的上升沿判断而不是 {@code consumeClick()}，避免和 Punchy 抢点击。
     */
    private static KeyMapping punchyInspectKey;
    private static boolean lastPunchyInspectDown;

    /** 重载后延迟若干 tick 再对齐 Punchy 的槽位选择，避开 Punchy 自己的加载时序 */
    private static int syncTicker = 40;
    /** 再晚一点做武器预热（解析模型/动画），避免第一次掏刀时卡一下 */
    private static int warmTicker = 80;

    @Override
    public void onInitializeClient() {
        Settings.load();
        TaczSelection.load();
        registerHostPack();
        PunchyApi.drawItem(MOD_ID, TaczRenderer::draw);
        // Punchy 自己也会画一对第一人称手臂；TACZ rig 内部已画真实手臂，必须藏掉 Punchy 那对
        PunchyApi.hideArms(MOD_ID, TaczRenderer::hideArmsNow);
        PunchyApi.onReload(MOD_ID, reason -> {
            // 只置脏：重载**过程中**扫描会拿到不完整的资源管理器（实测武器数会 6→0→1 乱跳）。
            // 真正扫描交给下面注册的重载监听器，那时资源已经换好了。
            TaczPacks.invalidate();
            TaczRenderer.resetActive();
        });
        registerReloadListener();
        // 切换动画包时立即停掉旧的 TACZ rig，避免和 Punchy 同时画手
        PunchyApi.onSettingsChanged(MOD_ID, TaczRenderer::resetActive);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (syncTicker > 0 && --syncTicker == 0) {
                TaczPacks.syncPunchySelection();
            }
            if (warmTicker > 0 && --warmTicker == 0) {
                TaczPacks.warmSelected();
            }
            pollPunchyInspect(client);
            // 照 TACZ SoundPlayManager.onClientTick：清理已播完的音效引用；
            // 退出世界（level == null）时全部停掉
            com.punchytacz.sound.WeaponSoundSystem.clientTick(client);
            while (INSPECT_KEY.consumeClick()) {
                // 不要判断 gui.screen()：和下面 G 唯一的差别就在这里，
                // 26.3 里它在游戏内不一定返回 null，会把按键整个吞掉。
                if (client.player != null) {
                    TaczRenderer.requestInspect();
                }
            }
            while (REFIT_KEY.consumeClick()) {
                if (client.player == null || TaczRefitScreen.isOpen()) {
                    continue;
                }
                // 只是**预览**：不动 Punchy 的槽位选择，只有按「应用」才切到宿主包。
                // （之前这里直接 ensureHostSelected()，等于一按 G 就把 Punchy 切到 TACZ，
                //   原生包的起手动画立刻被顶掉 —— 这就是“切出来的动画丢了”的一半原因。）
                client.setScreenAndShow(new TaczRefitScreen());
            }
        });
        LOG.info("punchytacz: Punchy API 已接入");
    }

    /** 监听 Punchy 的检视键（上升沿），不消费它的点击 */
    private static void pollPunchyInspect(Minecraft client) {
        if (punchyInspectKey == null) {
            var mappings = client.options.keyMappings;
            if (mappings == null) {
                return;
            }
            for (KeyMapping mapping : mappings) {
                if ("key.punchy.inspect".equals(mapping.getName())) {
                    punchyInspectKey = mapping;
                    break;
                }
            }
            if (punchyInspectKey == null) {
                return;
            }
        }
        boolean down = punchyInspectKey.isDown();
        if (down && !lastPunchyInspectDown && client.player != null) {
            TaczRenderer.requestInspect();
        }
        lastPunchyInspectDown = down;
    }

    /** 资源重载完成后才重扫武器表，保证读到的是最终资源管理器 */
    private static void registerReloadListener() {
        ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    @Override
                    public Identifier getFabricId() {
                        return Identifier.parse(MOD_ID + ":weapons");
                    }

                    @Override
                    public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                        TaczPacks.invalidate();
                        syncTicker = 40;
                        warmTicker = 80;
                    }
                });
    }

    /**
     * 把宿主包注册成**内置资源包**（不进 resourcepacks 目录、不会被误删、默认常开）。
     * 它只提供 compat + 通用 clip 名，让 Punchy 的包列表里出现唯一一个 TACZ 选项。
     */
    private static void registerHostPack() {
        var container = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
        if (container == null) {
            LOG.error("punchytacz: 取不到 ModContainer，宿主包未注册");
            return;
        }
        boolean ok = ResourceManagerHelper.registerBuiltinResourcePack(
                Identifier.parse(MOD_ID + ":" + MOD_ID),
                container,
                Component.literal("PunchyTACZ"),
                ResourcePackActivationType.ALWAYS_ENABLED);
        if (ok) {
            LOG.info("punchytacz: 宿主包已注册为内置资源包");
        } else {
            LOG.error("punchytacz: 宿主包注册失败（jar 内 resourcepacks/{} 是否存在？）", MOD_ID);
        }
    }
}
