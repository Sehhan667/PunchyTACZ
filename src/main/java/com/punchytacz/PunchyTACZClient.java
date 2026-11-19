package com.punchytacz;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import punchy.api.PunchyApi;

public final class PunchyTACZClient implements ClientModInitializer {
    public static final String MOD_ID = "punchytacz";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** 打开动画包选择菜单的按键（默认 O，可在控制设置里改） */
    private static final KeyMapping.Category MENU_CATEGORY =
            KeyMapping.Category.register(Identifier.parse("punchytacz:menu"));
    private static final KeyMapping MENU_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.punchytacz.menu", InputConstants.Type.KEYBOARD, 79, MENU_CATEGORY));

    @Override
    public void onInitializeClient() {
        Settings.load();
        PunchyApi.drawItem(MOD_ID, TaczRenderer::draw);
        // Punchy 自己也会画一对第一人称手臂；TACZ rig 内部已画真实手臂，必须藏掉 Punchy 那对
        PunchyApi.hideArms(MOD_ID, TaczRenderer::hideArmsNow);
        PunchyApi.onReload(MOD_ID, reason -> {
            TaczPacks.reload();
            TaczRenderer.resetActive();
        });
        // 菜单里切换动画包时立即停掉旧的 TACZ rig，避免和 Punchy 同时画手
        PunchyApi.onSettingsChanged(MOD_ID, TaczRenderer::resetActive);
        // 按键：打开动画包选择菜单（不用重载资源包即可切换）
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (MENU_KEY.consumeClick()) {
                if (client.player != null && !TaczMenuScreen.isOpen()) {
                    client.setScreenAndShow(new TaczMenuScreen(null));
                }
            }
        });
        LOG.info("punchytacz: Punchy API 已接入");
    }
}
