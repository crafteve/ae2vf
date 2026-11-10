package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：1:1 连接 / 断开的回执。
 *
 * <p>连接与断开都用 {@link WandCommitOutcome}：成功时带落到哪个节点与新增 / 删除台数，失败时
 * 带原因枚举；客户端据此出 HUD 闪条。</p>
 *
 * @param outcome 回执
 */
public record WandToggleS2C(WandCommitOutcome outcome) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_toggle_result}。 */
    public static final Type<WandToggleS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_toggle_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandToggleS2C> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> WandDraftCodec.OUTCOME.encode(buf, payload.outcome()),
                    buf -> new WandToggleS2C(WandDraftCodec.OUTCOME.decode(buf)));

    @Override
    public Type<WandToggleS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：交给草稿协调器出 HUD。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandToggleS2C payload, IPayloadContext context) {
    }
}
