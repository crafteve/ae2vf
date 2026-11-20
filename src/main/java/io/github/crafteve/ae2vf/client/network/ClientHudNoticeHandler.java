package io.github.crafteve.ae2vf.client.network;

import io.github.crafteve.ae2vf.client.screen.hud.HUDRenderer;
import io.github.crafteve.ae2vf.network.payload.HudNoticeS2C;

/**
 * HUD 提示的客户端落点：按档位交给统一的 HUD 条，不走原版动作栏。
 */
public final class ClientHudNoticeHandler {
    private ClientHudNoticeHandler() {
        // 纯静态处理
    }

    /**
     * @param payload 服务端提示
     */
    public static void handle(HudNoticeS2C payload) {
        switch (payload.kind()) {
            case SUCCESS -> HUDRenderer.success(payload.text());
            case NEUTRAL -> HUDRenderer.neutral(payload.text());
            case FAILURE -> HUDRenderer.failure(payload.text());
        }
    }
}
