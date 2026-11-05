package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.factory.node.BlockingMode;
import io.github.crafteve.idlefactory.factory.node.DispatchMode;
import io.github.crafteve.idlefactory.factory.node.MultiplierMode;
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
 * <p>阻挡、倍率、派单都归节点。{@code value} 按 {@code setting} 解释：阻挡用 0/1，
 * 倍率用模式 ordinal，派单用模式 ordinal；倍率的固定倍数另走 {@code factor}。
 * 服务端一律重新校验，不信任客户端自报的枚举与倍数。</p>
 *
 * @param containerId 菜单容器 id
 * @param nodeId      节点 id
 * @param setting     设置项
 * @param value       新值；按 setting 解释
 * @param factor      倍率的固定倍数；非倍率设置忽略
 */
public record FactoryNodeProviderC2S(int containerId, UUID nodeId, ProviderSetting setting,
        int value, int factor) implements CustomPacketPayload {

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
                case MULTIPLIER -> handleMultiplier(factory, payload);
                case DISPATCH -> handleDispatch(factory, payload);
            };
            feedback(player, payload.containerId(), result.ok(), result.message());
            if (result.ok()) {
                FactoryNodeSnapshots.send(player, payload.containerId());
            }
        });
    }

    /**
     * 倍率设置：value 是模式 ordinal，factor 是固定倍数；越界一律拒绝。
     *
     * @param factory 工厂
     * @param payload 请求
     * @return 结果
     */
    private static NodeActions.Result handleMultiplier(Factory factory,
            FactoryNodeProviderC2S payload) {
        MultiplierMode[] modes = MultiplierMode.values();
        if (payload.value() < 0 || payload.value() >= modes.length) {
            return NodeActions.Result.err("倍率模式非法");
        }
        return NodeActions.setMultiplier(factory, payload.nodeId(), modes[payload.value()],
                payload.factor());
    }

    /**
     * 派单设置：value 是模式 ordinal；越界一律拒绝。
     *
     * @param factory 工厂
     * @param payload 请求
     * @return 结果
     */
    private static NodeActions.Result handleDispatch(Factory factory,
            FactoryNodeProviderC2S payload) {
        DispatchMode[] modes = DispatchMode.values();
        if (payload.value() < 0 || payload.value() >= modes.length) {
            return NodeActions.Result.err("派单模式非法");
        }
        return NodeActions.setDispatch(factory, payload.nodeId(), modes[payload.value()]);
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
        buf.writeVarInt(payload.factor());
    }

    private static FactoryNodeProviderC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        UUID nodeId = buf.readUUID();
        int ordinal = buf.readUnsignedByte();
        ProviderSetting[] values = ProviderSetting.values();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid provider setting ordinal: " + ordinal);
        }
        int value = buf.readVarInt();
        int factor = buf.readVarInt();
        return new FactoryNodeProviderC2S(containerId, nodeId, values[ordinal], value, factor);
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
