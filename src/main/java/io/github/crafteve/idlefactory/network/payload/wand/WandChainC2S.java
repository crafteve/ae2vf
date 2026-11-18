package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.command.WandDraftCodec;
import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：编辑态 Ctrl + 右键，请求找出起点所在的同 id 机器群。
 *
 * <p>服务端只做查找与占用过滤，不写库、不推快照；客户端自己决定把哪几台加进草稿。</p>
 *
 * @param origin 连锁起点
 */
public record WandChainC2S(BlockKey origin) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_chain}。 */
    public static final Type<WandChainC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "wand_chain"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandChainC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> WandDraftCodec.writeBlockKey(buf, payload.origin()),
                    buf -> new WandChainC2S(WandDraftCodec.readBlockKey(buf)));

    @Override
    public Type<WandChainC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：主线程查找机器群并回包。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(WandChainC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FactoryService.ChainResult result = FactoryService.chainMachines(player, payload.origin());
            PacketDistributor.sendToPlayer(player,
                    new WandChainS2C(result.machines(), result.failure()));
        });
    }
}
