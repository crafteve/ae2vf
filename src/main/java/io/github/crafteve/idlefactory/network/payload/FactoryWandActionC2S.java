package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.item.MeBindingWandItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ME 绑定杖的操作请求：对目标方块 toggle 仓库绑定。
 *
 * <p>机器绑定杖的请求有独立包，见 {@code network.payload.wand}。客户端只发「我点了哪个方块」，
 * 服务端负责校验主手物品、工厂所有权与目标类型。处理完无论成功失败都回一份
 * {@link FactoryLinksS2C}，让客户端缓存和服务端一致。</p>
 *
 * @param pos 目标方块
 */
public record FactoryWandActionC2S(BlockPos pos) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_wand_action}。 */
    public static final Type<FactoryWandActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_wand_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryWandActionC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeBlockPos(payload.pos()),
                    buf -> new FactoryWandActionC2S(buf.readBlockPos()));

    @Override
    public Type<FactoryWandActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：转给 {@link FactoryService}，再回同步包。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryWandActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.getMainHandItem().getItem() instanceof MeBindingWandItem) {
                FactoryService.toggleStorage(player, payload.pos());
            }
            sendLinks(player);
        });
    }

    private static void sendLinks(ServerPlayer player) {
        Factory factory = factoryOf(player);
        PacketDistributor.sendToPlayer(player, factory == null
                ? FactoryLinksS2C.empty() : FactoryLinksS2C.of(factory));
    }

    private static Factory factoryOf(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        return runtime.manager().byOwner(player.getUUID()).orElse(null);
    }
}
