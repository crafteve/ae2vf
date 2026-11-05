package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.factory.node.BlockingMode;
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
 * 客户端对节点 provider 组的设置请求。
 *
 * <p>阻挡已经落地；倍增与逐台/轮询是界面上的预留位，服务端明确拒绝，不假装成功。这样
 * 协议形状先稳定，等草稿 13 的数据模型落地时只换服务端实现。</p>
 *
 * @param containerId 菜单容器 id
 * @param nodeId      节点 id
 * @param setting     设置项
 * @param value       新值；阻挡用 0 / 1
 */
public record FactoryNodeProviderC2S(int containerId, UUID nodeId, ProviderSetting setting,
        int value) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:node_provider}。 */
    public static final Type<FactoryNodeProviderC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "node_provider"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryNodeProviderC2S> STREAM_CODEC =
            StreamCodec.of(FactoryNodeProviderC2S::write, FactoryNodeProviderC2S::read);

    @Override
    public Type<FactoryNodeProviderC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单、工厂、节点，再交给动作层。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryNodeProviderC2S payload, IPayloadContext context) {
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
            Factory factory = found.get();
            NodeActions.Result result = switch (payload.setting()) {
                case BLOCKING -> NodeActions.setBlocking(factory, payload.nodeId(),
                        payload.value() == 0 ? BlockingMode.OFF : BlockingMode.ON);
                case MULTIPLIER -> NodeActions.Result.err("倍增尚未接入");
                case DISPATCH -> NodeActions.Result.err("逐台 / 轮询尚未接入");
            };
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

    private static void write(RegistryFriendlyByteBuf buf, FactoryNodeProviderC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeUUID(payload.nodeId());
        buf.writeByte(payload.setting().ordinal());
        buf.writeVarInt(payload.value());
    }

    private static FactoryNodeProviderC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID nodeId = buf.readUUID();
        int ordinal = buf.readUnsignedByte();
        ProviderSetting[] values = ProviderSetting.values();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid provider setting ordinal: " + ordinal);
        }
        return new FactoryNodeProviderC2S(containerId, nodeId, values[ordinal], buf.readVarInt());
    }

    /** provider 组可设置项；顺序即线协议。 */
    public enum ProviderSetting {
        /** 阻挡开关。 */
        BLOCKING,
        /** 样板倍率；预留。 */
        MULTIPLIER,
        /** 逐台 / 轮询；预留。 */
        DISPATCH
    }
}
