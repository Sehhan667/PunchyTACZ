package com.punchytacz;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import punchy.api.PunchyApi;

public final class PunchyTACZClient implements ClientModInitializer {
    public static final String MOD_ID = "punchytacz";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PunchyApi.drawItem(MOD_ID, TaczRenderer::draw);
        // Punchy 自己也会画一对第一人称手臂；TACZ rig 内部已画真实手臂，必须藏掉 Punchy 那对
        PunchyApi.hideArms(MOD_ID, TaczRenderer::hideArmsNow);
        PunchyApi.onReload(MOD_ID, reason -> {
            TaczPacks.reload();
            TaczRenderer.resetActive();
        });
        LOG.info("punchytacz: Punchy API 已接入");
    }
}
