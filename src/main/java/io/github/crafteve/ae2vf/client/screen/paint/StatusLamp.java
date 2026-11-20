package io.github.crafteve.ae2vf.client.screen.paint;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.client.screen.theme.GuiTextures;

import net.minecraft.resources.ResourceLocation;

/**
 * 状态贴图：1px 状态条与状态灯。
 *
 * <p>贴图由 {@code tools/gui/gen_textures.py} 生成，Java 只 blit；颜色语义与 {@link StatusColor}
 * 对齐，不一致时以状态灯为准重新生成。</p>
 */
public final class StatusLamp {
    private StatusLamp() {
        // 纯静态查表
    }

    /**
     * 档位对应的状态灯贴图。
     *
     * @param kind 档位
     * @return 贴图
     */
    public static ResourceLocation lamp(StatusKind kind) {
        return switch (kind) {
            case RUNNING, INPUT_OK -> GuiTextures.STATUS_LAMP_RUN;
            case OFFLINE, STALE -> GuiTextures.STATUS_LAMP_ERROR;
            case WAITING_MATERIAL, INPUT_BLOCKED, UNPROBED, PARTIAL -> GuiTextures.STATUS_LAMP_WARN;
            case IDLE, UNKNOWN, UNCONFIGURED -> GuiTextures.STATUS_LAMP_IDLE;
        };
    }
}
