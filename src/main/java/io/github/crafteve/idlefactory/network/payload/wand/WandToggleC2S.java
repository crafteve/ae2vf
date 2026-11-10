package io.github.crafteve.idlefactory.network.payload.wand;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：空闲态右键方块，请求 1:1 连接或断开。
 *
 * <p>只带坐标，不带「连还是断」：方块当前是否已绑定由服务端现查，客户端不再猜。回执走
 * {@link WandToggleS2C}。</p>
 *
 * @param pos 目标方块
 */
public record WandToggleC2S(BlockPos pos) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:wand_toggle}。 */
    public static final Type<WandToggleC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "wand_toggle"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandToggleC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos()),
                    buf -> new WandToggleC2S(buf.readBlockPos()));

    @Override
    public Type<WandToggleC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：主线程执行 toggle，并把回执发回发起者。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(WandToggleC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            WandCommitOutcome outcome = FactoryService.toggleMachine(player, payload.pos());
            PacketDistributor.sendToPlayer(player, new WandToggleS2C(outcome));
        });
    }
}
