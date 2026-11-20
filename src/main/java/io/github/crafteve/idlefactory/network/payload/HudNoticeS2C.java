package io.github.crafteve.idlefactory.network.payload;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientHudNoticeHandler;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的 HUD 提示：失联 / 恢复这类没有界面的通知。
 *
 * <p>走模组自己的 HUD 条而不是原版动作栏，颜色语义由 {@link Kind} 表达，客户端只按档位取色。</p>
 *
 * @param text 文案
 * @param kind 提示档位
 */
public record HudNoticeS2C(Component text, Kind kind) implements CustomPacketPayload {
    /** 提示档位。 */
    public enum Kind {
        /** 成功：全绿。 */
        SUCCESS,
        /** 中性：黄色。 */
        NEUTRAL,
        /** 失败：红色。 */
        FAILURE
    }

    /** 负载类型 id。 */
    public static final Type<HudNoticeS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "hud_notice"));

    /** 线编解码。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, HudNoticeS2C> STREAM_CODEC =
            StreamCodec.composite(
                    ComponentSerialization.STREAM_CODEC, HudNoticeS2C::text,
                    ByteBufCodecs.VAR_INT.map(id -> Kind.values()[id], Kind::ordinal),
                    HudNoticeS2C::kind,
                    HudNoticeS2C::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理：交给 HUD 条。
     *
     * @param context 处理上下文
     */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> ClientHudNoticeHandler.handle(this));
    }
}
