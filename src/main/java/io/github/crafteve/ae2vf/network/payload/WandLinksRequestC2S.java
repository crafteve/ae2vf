package io.github.crafteve.ae2vf.network.payload;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.factory.Factory;
import io.github.crafteve.ae2vf.factory.FactoryLifecycle;

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
 * <p>进服、换维度、打开终端时发一次；服务端回 {@link WandLinksS2C}。没有工厂时回空快照，
 * 让客户端把旧缓存清掉。结构变更后的刷新靠服务端推送，不再轮询。</p>
 */
public record WandLinksRequestC2S() implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_links_request}。 */
    public static final Type<WandLinksRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_links_request"));

    /** 空负载编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandLinksRequestC2S> STREAM_CODEC =
            StreamCodec.unit(new WandLinksRequestC2S());

    @Override
    public Type<WandLinksRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：回当前快照。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(WandLinksRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FactoryLifecycle runtime = FactoryLifecycle.get();
            if (runtime == null) {
                PacketDistributor.sendToPlayer(player, WandLinksS2C.empty());
                return;
            }
            Factory factory = runtime.manager().byOwner(player.getUUID()).orElse(null);
            PacketDistributor.sendToPlayer(player, factory == null
                    ? WandLinksS2C.empty() : WandLinksS2C.of(factory));
        });
    }
}
