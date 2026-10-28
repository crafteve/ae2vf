package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求当前工厂的链接快照。
 *
 * <p>手持绑定杖、进服、换维度时发；服务端回 {@link FactoryLinksS2C}。没有工厂时回空快照，
 * 让客户端把旧缓存清掉。</p>
 */
public record FactoryLinksRequestC2S() implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_links_request}。 */
    public static final Type<FactoryLinksRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_links_request"));

    /** 空负载编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryLinksRequestC2S> STREAM_CODEC =
            StreamCodec.unit(new FactoryLinksRequestC2S());

    @Override
    public Type<FactoryLinksRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：回当前快照。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryLinksRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                PacketDistributor.sendToPlayer(player, FactoryLinksS2C.empty());
                return;
            }
            Factory factory = runtime.manager().byOwner(player.getUUID()).orElse(null);
            PacketDistributor.sendToPlayer(player, factory == null
                    ? FactoryLinksS2C.empty() : FactoryLinksS2C.of(factory));
        });
    }
}
