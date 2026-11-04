package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.network.FactoryNodeSnapshots;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求当前工厂的节点表。
 *
 * <p>与被动 IO 的机器表请求同构：界面每 20 tick 请求一次；服务端只接受当前打开的
 * {@link FactoryNodeMenu}，容器 id 必须精确匹配。</p>
 *
 * @param containerId 菜单容器 id
 */
public record FactoryNodeTableRequestC2S(int containerId) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:node_table_request}。 */
    public static final Type<FactoryNodeTableRequestC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "node_table_request"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryNodeTableRequestC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.containerId()),
                    buf -> new FactoryNodeTableRequestC2S(buf.readVarInt()));

    @Override
    public Type<FactoryNodeTableRequestC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单与容器，回一份节点表。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryNodeTableRequestC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryNodeMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            FactoryNodeSnapshots.send(player, payload.containerId());
        });
    }
}
