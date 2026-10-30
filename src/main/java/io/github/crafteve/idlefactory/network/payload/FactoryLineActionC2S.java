package io.github.crafteve.idlefactory.network.payload;

import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.line.LineActions;
import io.github.crafteve.idlefactory.menu.FactoryMenu;
import io.github.crafteve.idlefactory.network.FactoryTableSnapshots;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对产线做出的管理动作：新建 / 重命名 / 删除 / 启动 / 暂停 / 放机器 / 移出机器 / 删除机器，
 * 以及机器组的整组动作：改台数 / 整组移出 / 整组删除。
 *
 * <p>只传 id 和名字，不传产线内容；所有校验由 {@link LineActions} 在服务端重新做。名字只在
 * 新建/重命名时有意义，其它动作忽略。台数只在 {@code RESIZE_STACK} 时有意义，由服务端夹紧。</p>
 *
 * @param containerId 菜单容器 id
 * @param action      动作
 * @param lineId      目标产线 id；新建时为 {@code null}
 * @param machineId   机器 id；放机器/移出机器/删除机器，以及整组动作的锚点
 * @param name        新产线名；仅新建/重命名使用
 * @param count       目标台数；仅 {@code RESIZE_STACK} 使用，其它动作传 0
 */
public record FactoryLineActionC2S(int containerId, Action action, @Nullable UUID lineId,
        @Nullable UUID machineId, @Nullable String name, int count)
        implements CustomPacketPayload {

    /** 目标台数的合法上限；和 {@link LineActions#MAX_STACK} 保持一致。 */
    public static final int MAX_STACK_COUNT = LineActions.MAX_STACK;

    /** 包类型：{@code idlefactory:factory_line_action}。 */
    public static final Type<FactoryLineActionC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_line_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryLineActionC2S> STREAM_CODEC =
            StreamCodec.of(FactoryLineActionC2S::write, FactoryLineActionC2S::read);

    @Override
    public Type<FactoryLineActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：先校验菜单，再交给动作层，最后回反馈并按需推新快照。
     *
     * @param payload 动作
     * @param context 上下文
     */
    public static void handle(FactoryLineActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryMenu menu)
                    || menu.containerId != payload.containerId()) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                sendFeedback(player, payload.containerId(), false, "Idle Factory 尚未启动", null);
                return;
            }
            Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
            if (factory.isEmpty()) {
                sendFeedback(player, payload.containerId(), false, "你还没有工厂", null);
                return;
            }
            LineActions.Result result = dispatch(factory.get(), payload);
            sendFeedback(player, payload.containerId(), result.ok(), result.message(),
                    result.lineId());
            if (result.ok()) {
                FactoryTableSnapshots.send(player, payload.containerId());
            }
        });
    }

    private static LineActions.Result dispatch(Factory factory, FactoryLineActionC2S payload) {
        return switch (payload.action()) {
            case CREATE_LINE -> LineActions.create(factory, payload.name() == null ? "" : payload.name());
            case RENAME_LINE -> LineActions.rename(factory, payload.lineId(), payload.name() == null
                    ? "" : payload.name());
            case DELETE_LINE -> LineActions.delete(factory, payload.lineId());
            case START_LINE -> LineActions.setRunning(factory, payload.lineId(), true);
            case PAUSE_LINE -> LineActions.setRunning(factory, payload.lineId(), false);
            case PLACE_MACHINE -> LineActions.placeMachine(factory, payload.lineId(),
                    payload.machineId());
            case REMOVE_MACHINE -> LineActions.removeMachine(factory, payload.lineId(),
                    payload.machineId());
            case DELETE_MACHINE -> FactoryService.deleteMachineById(factory, payload.machineId(),
                    true);
            case RESIZE_STACK -> LineActions.resizeStack(factory, payload.lineId(),
                    payload.machineId(), payload.count());
            case REMOVE_STACK -> LineActions.removeStack(factory, payload.lineId(),
                    payload.machineId());
            case DELETE_STACK -> FactoryService.deleteStack(factory, payload.machineId(), true);
        };
    }

    private static void sendFeedback(ServerPlayer player, int containerId, boolean ok,
            String message, @Nullable UUID lineId) {
        PacketDistributor.sendToPlayer(player, new FactoryEditFeedbackS2C(
                containerId, ok, FactoryEditFeedbackS2C.clip(message), lineId));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryLineActionC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.action().ordinal());
        writeNullableUuid(buf, payload.lineId());
        writeNullableUuid(buf, payload.machineId());
        buf.writeBoolean(payload.name() != null);
        if (payload.name() != null) {
            buf.writeUtf(payload.name(), LineActions.MAX_NAME_LENGTH);
        }
        buf.writeVarInt(payload.count());
    }

    private static FactoryLineActionC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= Action.values().length) {
            throw new DecoderException("invalid line action: " + ordinal);
        }
        Action action = Action.values()[ordinal];
        UUID lineId = readNullableUuid(buf);
        UUID machineId = readNullableUuid(buf);
        String name = buf.readBoolean() ? buf.readUtf(LineActions.MAX_NAME_LENGTH) : null;
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_STACK_COUNT) {
            throw new DecoderException("invalid stack count: " + count);
        }
        validate(action, lineId, machineId, name, count);
        return new FactoryLineActionC2S(containerId, action, lineId, machineId, name, count);
    }

    private static void validate(Action action, @Nullable UUID lineId, @Nullable UUID machineId,
            @Nullable String name, int count) {
        switch (action) {
            case CREATE_LINE -> {
                if (name == null || name.isBlank()) {
                    throw new DecoderException("create line requires a name");
                }
            }
            case RENAME_LINE -> {
                if (lineId == null || name == null || name.isBlank()) {
                    throw new DecoderException("rename line requires id and name");
                }
            }
            case DELETE_LINE, START_LINE, PAUSE_LINE -> {
                if (lineId == null) {
                    throw new DecoderException("line action requires a line id");
                }
            }
            case PLACE_MACHINE, REMOVE_MACHINE -> {
                if (lineId == null || machineId == null) {
                    throw new DecoderException("machine action requires line and machine ids");
                }
            }
            case DELETE_MACHINE -> {
                if (machineId == null) {
                    throw new DecoderException("delete machine requires a machine id");
                }
            }
            case RESIZE_STACK -> {
                if (lineId == null || machineId == null || count < 1) {
                    throw new DecoderException("resize stack requires line, machine and count");
                }
            }
            case REMOVE_STACK -> {
                if (lineId == null || machineId == null) {
                    throw new DecoderException("remove stack requires line and machine ids");
                }
            }
            case DELETE_STACK -> {
                if (machineId == null) {
                    throw new DecoderException("delete stack requires a machine id");
                }
            }
        }
    }

    private static void writeNullableUuid(RegistryFriendlyByteBuf buf, @Nullable UUID value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeUUID(value);
        }
    }

    @Nullable
    private static UUID readNullableUuid(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    /** 产线管理动作。 */
    public enum Action {
        /** 新建产线。 */
        CREATE_LINE,
        /** 只改名字，id 不变。 */
        RENAME_LINE,
        /** 删除产线。 */
        DELETE_LINE,
        /** 启动产线。 */
        START_LINE,
        /** 暂停产线。 */
        PAUSE_LINE,
        /** 放入机器；机器在别的产线时为移动。 */
        PLACE_MACHINE,
        /** 把机器移出产线。 */
        REMOVE_MACHINE,
        /** 删除机器（界面确认后）。 */
        DELETE_MACHINE,
        /** 把一组机器的台数改成 {@code count}；服务端夹紧实际数量。 */
        RESIZE_STACK,
        /** 把一组机器整体移出产线，规则保留。 */
        REMOVE_STACK,
        /** 删除一组没编线的机器，连规则一起删。 */
        DELETE_STACK
    }
}
