package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.wand.WandCoordinator;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：草稿提交结果。
 *
 * <p>回带 {@code containerId} 与 {@code requestSeq}，客户端只接受与当前面板、当前提交序号
 * 都对得上的回执；迟到包直接丢弃。</p>
 *
 * @param containerId 面板菜单的容器 id
 * @param requestSeq  本次提交的草稿版本号
 * @param outcome     结果
 */
public record WandCommitS2C(int containerId, long requestSeq, WandCommitOutcome outcome)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_commit_result}。 */
    public static final Type<WandCommitS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_commit_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandCommitS2C> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId());
                        buf.writeVarLong(payload.requestSeq());
                        WandDraftCodec.OUTCOME.encode(buf, payload.outcome());
                    },
                    buf -> new WandCommitS2C(buf.readVarInt(), buf.readVarLong(),
                            WandDraftCodec.OUTCOME.decode(buf)));

    @Override
    public Type<WandCommitS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：按容器 id 与序号匹配后交给协调器。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandCommitS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandCoordinator.handleCommit(payload));
    }
}
