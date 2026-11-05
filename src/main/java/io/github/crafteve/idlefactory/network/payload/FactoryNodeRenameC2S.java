package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.network.FactoryNodeSnapshots;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端改节点名字。
 *
 * <p>只传原始文本；长度、空白与控制字符由 {@link NodeActions#rename} 统一校验，服务端
 * 重校验客户端输入。</p>
 *
 * @param containerId 菜单容器 id
 * @param nodeId      节点 id
 * @param name        新名字；空串表示恢复方块名
 */
public record FactoryNodeRenameC2S(int containerId, UUID nodeId, String name)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:node_rename}。 */
    public static final Type<FactoryNodeRenameC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "node_rename"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryNodeRenameC2S> STREAM_CODEC =
            StreamCodec.of(FactoryNodeRenameC2S::write, FactoryNodeRenameC2S::read);

    @Override
    public Type<FactoryNodeRenameC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单、工厂，再交给动作层。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryNodeRenameC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryNodeMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            Optional<Factory> found = factoryOf(player);
            if (found.isEmpty()) {
                feedback(player, payload.containerId(), false, "你还没有工厂");
                return;
            }
            NodeActions.Result result = NodeActions.rename(found.get(), payload.nodeId(),
                    payload.name());
            feedback(player, payload.containerId(), result.ok(), result.message());
            if (result.ok()) {
                FactoryNodeSnapshots.send(player, payload.containerId());
            }
        });
    }

    private static Optional<Factory> factoryOf(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return Optional.empty();
        }
        return runtime.manager().byOwner(player.getUUID());
    }

    private static void feedback(ServerPlayer player, int containerId, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                containerId, ok, FactoryEditFeedbackS2C.clip(message), null));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryNodeRenameC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeUUID(payload.nodeId());
        buf.writeUtf(payload.name(), Node.HARD_MAX_NAME_LENGTH);
    }

    private static FactoryNodeRenameC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID nodeId = buf.readUUID();
        String name = buf.readUtf(Node.HARD_MAX_NAME_LENGTH);
        return new FactoryNodeRenameC2S(containerId, nodeId, name);
    }
}
