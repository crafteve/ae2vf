package io.github.crafteve.idlefactory.network.payload.wand;

import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.wand.WandCoordinator;
import io.github.crafteve.idlefactory.factory.FactoryLinkFinder;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：连锁查找到的机器群。
 *
 * <p>机器列表已经过占用过滤，客户端只需再按「已在草稿 / 类型不一致」跳过。失败时列表为空，
 * 原因在 {@code failure}。条目上限沿用 {@link FactoryLinkFinder#MAX_MACHINES}。</p>
 *
 * @param machines 可加入的机器方块
 * @param failure  查找失败原因；成功时为 {@link WandFailure#NONE}
 */
public record WandChainS2C(List<BlockKey> machines, WandFailure failure)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_chain_result}。 */
    public static final Type<WandChainS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_chain_result"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandChainS2C> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        WandDraftCodec.writeBlockKeys(buf, payload.machines(),
                                FactoryLinkFinder.MAX_MACHINES);
                        WandDraftCodec.writeFailure(buf, payload.failure());
                    },
                    buf -> new WandChainS2C(
                            WandDraftCodec.readBlockKeys(buf, FactoryLinkFinder.MAX_MACHINES),
                            WandDraftCodec.readFailure(buf)));

    public WandChainS2C {
        machines = List.copyOf(machines);
        if (machines.size() > FactoryLinkFinder.MAX_MACHINES) {
            throw new IllegalArgumentException("连锁结果超过上限");
        }
    }

    @Override
    public Type<WandChainS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理：把机器批量加入草稿。
     *
     * @param payload 回执
     * @param context 上下文
     */
    public static void handle(WandChainS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> WandCoordinator.handleChain(payload));
    }
}
