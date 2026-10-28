package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.item.FactoryTerminalItem;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求用工厂终端打开界面。
 *
 * <p>空负载；服务端不信任客户端，收到后重新检查玩家身上是否带终端。快捷键和右键最终
 * 都走 {@link FactoryService#openGui(ServerPlayer)}。</p>
 */
public record OpenFactoryGuiC2S() implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:open_factory_gui}。 */
    public static final Type<OpenFactoryGuiC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "open_factory_gui"));

    /** 空负载编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenFactoryGuiC2S> STREAM_CODEC =
            StreamCodec.unit(new OpenFactoryGuiC2S());

    @Override
    public Type<OpenFactoryGuiC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：带终端才打开界面。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(OpenFactoryGuiC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && FactoryTerminalItem.isCarriedBy(player)) {
                FactoryService.openGui(player);
            }
        });
    }
}
