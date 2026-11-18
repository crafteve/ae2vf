package io.github.crafteve.idlefactory.command;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.BlockingMode;
import io.github.crafteve.idlefactory.io.DispatchMode;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.node.MultiplierMode;

import appeng.api.stacks.AEKey;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * 一条写命令：信封 + 内嵌 body。
 *
 * <p>信封携带「谁发的、哪条菜单、作用在哪个节点 / 方块、请求序号」，body 按种类携带各自的
 * 数据。服务端只认这一种写入口，旧的逐动作包全部退役。</p>
 *
 * <p>序号防重放：同一个序号只接受一次，重登或换界面后序号从头开始。世界命令（绑定 / 解绑
 * 仓库）用 {@code containerId = -1} 跳过会话步骤。</p>
 *
 * @param containerId 菜单容器 id；世界命令为 {@code -1}
 * @param kind        命令种类
 * @param source      发起者
 * @param nodeId      节点 id；非节点命令为 {@code null}
 * @param target      目标方块；非方块命令为 {@code null}
 * @param requestSeq  请求序号
 * @param body        命令数据
 */
public record FactoryCommand(int containerId, CommandKind kind, CommandSource source,
        @Nullable UUID nodeId, @Nullable BlockKey target, long requestSeq, Body body) {

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryCommand> STREAM_CODEC =
            StreamCodec.of(FactoryCommand::write, FactoryCommand::read);

    public FactoryCommand {
        if (requestSeq < 0L) {
            throw new IllegalArgumentException("请求序号不能为负");
        }
    }

    /**
     * 命令数据：按种类取不同形状。
     */
    public sealed interface Body permits Empty, Rename, Rule, Blocking, Multiplier, Dispatch, Migrate {
    }

    /** 没有额外数据的命令。 */
    public record Empty() implements Body {
    }

    /**
     * 改名。
     *
     * @param name 新名字；空串表示清除
     */
    public record Rename(String name) implements Body {
    }

    /**
     * 规则动作。
     *
     * @param side       当前侧
     * @param key        资源
     * @param face       面；{@code null} 表示不限
     * @param mode       匹配模式
     * @param targetSide 换方向时的目标侧；其它动作为 {@code null}
     */
    public record Rule(IoSide side, AEKey key, @Nullable Direction face, MatchMode mode,
            @Nullable IoSide targetSide) implements Body {
    }

    /**
     * 阻挡开关。
     *
     * @param mode 开关
     */
    public record Blocking(BlockingMode mode) implements Body {
    }

    /**
     * 倍率。
     *
     * @param mode   倍率模式
     * @param factor 固定倍数
     */
    public record Multiplier(MultiplierMode mode, int factor) implements Body {
    }

    /**
     * 派单模式。
     *
     * @param mode 模式
     */
    public record Dispatch(DispatchMode mode) implements Body {
    }

    /**
     * 节点类型迁移。
     *
     * @param typeBlockId 迁移目标方块 id
     */
    public record Migrate(ResourceLocation typeBlockId) implements Body {
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryCommand command) {
        buf.writeVarInt(command.containerId());
        buf.writeByte(command.kind().ordinal());
        buf.writeByte(command.source().ordinal());
        buf.writeBoolean(command.nodeId() != null);
        if (command.nodeId() != null) {
            buf.writeUUID(command.nodeId());
        }
        buf.writeBoolean(command.target() != null);
        if (command.target() != null) {
            buf.writeResourceLocation(command.target().dimension().location());
            buf.writeLong(command.target().pos().asLong());
        }
        buf.writeVarLong(command.requestSeq());
        writeBody(buf, command);
    }

    private static void writeBody(RegistryFriendlyByteBuf buf, FactoryCommand command) {
        switch (command.body()) {
            case Empty ignored -> {
                // 无数据
            }
            case Rename rename -> buf.writeUtf(rename.name(), 256);
            case Rule rule -> {
                buf.writeByte(rule.side().ordinal());
                AEKey.STREAM_CODEC.encode(buf, rule.key());
                buf.writeBoolean(rule.face() != null);
                if (rule.face() != null) {
                    buf.writeByte(rule.face().ordinal());
                }
                buf.writeByte(rule.mode().ordinal());
                buf.writeBoolean(rule.targetSide() != null);
                if (rule.targetSide() != null) {
                    buf.writeByte(rule.targetSide().ordinal());
                }
            }
            case Blocking blocking -> buf.writeByte(blocking.mode().ordinal());
            case Multiplier multiplier -> {
                buf.writeByte(multiplier.mode().ordinal());
                buf.writeVarInt(multiplier.factor());
            }
            case Dispatch dispatch -> buf.writeByte(dispatch.mode().ordinal());
            case Migrate migrate -> buf.writeResourceLocation(migrate.typeBlockId());
        }
    }

    private static FactoryCommand read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        CommandKind kind = readEnum(buf, CommandKind.values(), "command kind");
        CommandSource source = readEnum(buf, CommandSource.values(), "command source");
        UUID nodeId = buf.readBoolean() ? buf.readUUID() : null;
        BlockKey target = null;
        if (buf.readBoolean()) {
            ResourceLocation dimension = buf.readResourceLocation();
            target = BlockKey.of(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.DIMENSION, dimension),
                    net.minecraft.core.BlockPos.of(buf.readLong()));
        }
        long requestSeq = buf.readVarLong();
        if (requestSeq < 0L) {
            throw new DecoderException("请求序号不能为负");
        }
        Body body = readBody(buf, kind);
        return new FactoryCommand(containerId, kind, source, nodeId, target, requestSeq, body);
    }

    private static Body readBody(RegistryFriendlyByteBuf buf, CommandKind kind) {
        return switch (kind) {
            case FACTORY_CREATE -> new Empty();
            case NODE_DELETE -> new Empty();
            case NODE_RENAME -> new Rename(buf.readUtf(256));
            case RULE_ADD, RULE_UPDATE, RULE_MOVE, RULE_REMOVE -> new Rule(
                    readEnum(buf, IoSide.values(), "io side"),
                    AEKey.STREAM_CODEC.decode(buf),
                    buf.readBoolean() ? readEnum(buf, Direction.values(), "face") : null,
                    readEnum(buf, MatchMode.values(), "match mode"),
                    buf.readBoolean() ? readEnum(buf, IoSide.values(), "target side") : null);
            case PROVIDER_BLOCKING -> new Blocking(readEnum(buf, BlockingMode.values(), "blocking"));
            case PROVIDER_MULTIPLIER -> new Multiplier(
                    readEnum(buf, MultiplierMode.values(), "multiplier mode"), buf.readVarInt());
            case PROVIDER_DISPATCH -> new Dispatch(readEnum(buf, DispatchMode.values(), "dispatch"));
            case NODE_TYPE_MIGRATE -> new Migrate(buf.readResourceLocation());
            case WAND_TOGGLE, SET_ANCHOR, CLEAR_ANCHOR, WAND_COMMIT -> new Empty();
        };
    }

    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buf, E[] values,
            String name) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid " + name + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
