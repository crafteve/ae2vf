package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.NodeActions;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.machine.MatchMode;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.menu.FactoryGuiOpening;
import io.github.crafteve.idlefactory.network.FactoryTableSnapshots;
import io.github.crafteve.idlefactory.network.ResourceKeyCodec;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对一台机器的 IO 规则做出的动作：新增 / 更新面或匹配模式 / 换方向 / 删除。
 *
 * <p>只传资源 key、面、匹配模式和机器 id；规则数量固定「塞满」，服务端不接受客户端指定数量。
 * 规则身份是资源 key，更新、换方向和删除都按 key 找。</p>
 *
 * <p>换方向用 {@link Action#MOVE}：{@code side} 是当前侧，{@code targetSide} 是目标侧。客户端不
 * 自己发「先删后加」——两个包之间没有原子性，目标侧满或重名时会把规则删丢。其它动作
 * {@code targetSide} 为 {@code null}。</p>
 *
 * @param containerId 菜单容器 id
 * @param action      动作
 * @param nodeId      节点 id
 * @param side        输入 / 输出；MOVE 时是「从哪一侧搬走」
 * @param key         资源
 * @param face        面；{@code null} 表示不限；删除与 MOVE 忽略
 * @param mode        匹配模式；删除与 MOVE 忽略
 * @param targetSide  MOVE 的目标侧；其它动作传 {@code null}
 */
public record FactoryRuleActionC2S(int containerId, Action action, UUID nodeId,
        IoSide side, AEKey key, @Nullable Direction face,
        MatchMode mode, @Nullable IoSide targetSide) implements CustomPacketPayload {

    /** 包类型：{@code idlefactory:factory_rule_action}。 */
    public static final Type<FactoryRuleActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_rule_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryRuleActionC2S> STREAM_CODEC =
            StreamCodec.of(FactoryRuleActionC2S::write, FactoryRuleActionC2S::read);

    /** 不带目标侧的便捷构造：ADD / UPDATE / REMOVE 用。 */
    public FactoryRuleActionC2S(int containerId, Action action, UUID nodeId, IoSide side,
            AEKey key, @Nullable Direction face, MatchMode mode) {
        this(containerId, action, nodeId, side, key, face, mode, null);
    }

    @Override
    public Type<FactoryRuleActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单，交给动作层，回反馈并按需推新快照。
     *
     * @param payload 动作
     * @param context 上下文
     */
    public static void handle(FactoryRuleActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!FactoryGuiOpening.isFactoryMenu(player.containerMenu, payload.containerId())) {
                return;
            }
            if (payload.action() == Action.MOVE
                    && (payload.targetSide() == null || payload.targetSide() == payload.side())) {
                // 目标侧缺失或与源侧相同都是客户端错误；丢弃，不猜意图。
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                sendFeedback(player, payload.containerId(), false, "Idle Factory 尚未启动");
                return;
            }
            Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                sendFeedback(player, payload.containerId(), false, "你还没有工厂");
                return;
            }
            NodeActions.Result result = dispatch(factory.get(), payload);
            sendFeedback(player, payload.containerId(), result.ok(), result.message());
            if (result.ok()) {
                FactoryTableSnapshots.send(player, payload.containerId());
            }
        });
    }

    private static NodeActions.Result dispatch(Factory factory, FactoryRuleActionC2S payload) {
        return switch (payload.action()) {
            case ADD -> NodeActions.addRule(factory, payload.nodeId(),
                    payload.side(), payload.key(), payload.face(), payload.mode());
            case UPDATE -> NodeActions.updateRule(factory, payload.nodeId(),
                    payload.side(), payload.key(), payload.face(), payload.mode());
            case MOVE -> NodeActions.moveRule(factory, payload.nodeId(),
                    payload.side(), payload.key(), payload.targetSide());
            case REMOVE -> NodeActions.removeRule(factory, payload.nodeId(),
                    payload.side(), payload.key());
        };
    }

    private static void sendFeedback(ServerPlayer player, int containerId, boolean ok,
            String message) {
        PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                containerId, ok, FactoryEditFeedbackS2C.clip(message), null));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryRuleActionC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.action().ordinal());
        buf.writeUUID(payload.nodeId());
        buf.writeByte(payload.side().ordinal());
        ResourceKeyCodec.write(buf, payload.key());
        buf.writeBoolean(payload.face() != null);
        if (payload.face() != null) {
            buf.writeByte(payload.face().ordinal());
        }
        buf.writeByte(payload.mode().ordinal());
        buf.writeBoolean(payload.targetSide() != null);
        if (payload.targetSide() != null) {
            buf.writeByte(payload.targetSide().ordinal());
        }
    }

    private static FactoryRuleActionC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int actionOrdinal = buf.readUnsignedByte();
        if (actionOrdinal >= Action.values().length) {
            throw new DecoderException("invalid rule action: " + actionOrdinal);
        }
        Action action = Action.values()[actionOrdinal];
        UUID nodeId = buf.readUUID();
        int sideOrdinal = buf.readUnsignedByte();
        if (sideOrdinal >= IoSide.values().length) {
            throw new DecoderException("invalid io side: " + sideOrdinal);
        }
        IoSide side = IoSide.values()[sideOrdinal];
        AEKey key = ResourceKeyCodec.read(buf);
        Direction face = null;
        if (buf.readBoolean()) {
            int faceOrdinal = buf.readUnsignedByte();
            if (faceOrdinal >= Direction.values().length) {
                throw new DecoderException("invalid direction: " + faceOrdinal);
            }
            face = Direction.values()[faceOrdinal];
        }
        int modeOrdinal = buf.readUnsignedByte();
        if (modeOrdinal >= MatchMode.values().length) {
            throw new DecoderException("invalid match mode: " + modeOrdinal);
        }
        MatchMode mode = MatchMode.values()[modeOrdinal];
        IoSide targetSide = null;
        if (buf.readBoolean()) {
            int targetOrdinal = buf.readUnsignedByte();
            if (targetOrdinal >= IoSide.values().length) {
                throw new DecoderException("invalid target io side: " + targetOrdinal);
            }
            targetSide = IoSide.values()[targetOrdinal];
        }
        return new FactoryRuleActionC2S(containerId, action, nodeId, side, key, face, mode,
                targetSide);
    }

    /** IO 规则动作。 */
    public enum Action {
        /** 新增规则。 */
        ADD,
        /** 更新面 / 匹配模式。 */
        UPDATE,
        /** 把规则搬到另一侧。 */
        MOVE,
        /** 删除规则。 */
        REMOVE
    }
}
