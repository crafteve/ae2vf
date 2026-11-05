package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.menu.FactoryNodeMenu;
import io.github.crafteve.idlefactory.network.FactoryNodeSnapshots;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对一个节点做出的管理动作：删除整个节点。
 *
 * <p>删除成员的语义走 {@link FactoryMachineActionC2S}（移除成员，最后一台等价删节点）；
 * 这个包只表达「明确删节点」。服务端按玩家工厂与节点归属重新校验，不信任客户端。</p>
 *
 * @param containerId 菜单容器 id
 * @param nodeId      目标节点 id
 */
public record FactoryNodeActionC2S(int containerId, UUID nodeId) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:factory_node_action}。 */
    public static final Type<FactoryNodeActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_node_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryNodeActionC2S> STREAM_CODEC =
            StreamCodec.of(FactoryNodeActionC2S::write, FactoryNodeActionC2S::read);

    @Override
    public Type<FactoryNodeActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单与节点归属，再交给动作层。
     *
     * @param payload 动作
     * @param context 上下文
     */
    public static void handle(FactoryNodeActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryNodeMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                feedback(player, payload.containerId(), false, "Idle Factory 尚未启动");
                return;
            }
            Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                feedback(player, payload.containerId(), false, "你还没有工厂");
                return;
            }
            NodeActions.Result result = FactoryService.deleteNodeById(factory.get(),
                    payload.nodeId());
            feedback(player, payload.containerId(), result.ok(), result.message());
            if (result.ok()) {
                FactoryNodeSnapshots.send(player, payload.containerId());
            }
        });
    }

    private static void feedback(ServerPlayer player, int containerId, boolean ok, String message) {
        PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                containerId, ok, FactoryEditFeedbackS2C.clip(message), null));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryNodeActionC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeUUID(payload.nodeId());
    }

    private static FactoryNodeActionC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID nodeId = buf.readUUID();
        if (nodeId == null) {
            throw new DecoderException("node action requires a node id");
        }
        return new FactoryNodeActionC2S(containerId, nodeId);
    }
}
