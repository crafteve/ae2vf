package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientNodeTableHandler;
import io.github.crafteve.idlefactory.factory.FactoryNodeSnapshotBuilder;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.machine.BlockingMode;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;
import io.github.crafteve.idlefactory.factory.probe.Health;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的节点表。
 *
 * <p>节点表与被动 IO 的机器表分开：节点终端只需要身份、状态、provider 与成员，不需要规则明细。
 * 成员按方块列表建模，是为堆叠与多方块预留的形状；当前每个成员只有一个方块。</p>
 *
 * @param containerId   菜单容器 id
 * @param hasFactory    玩家是否已有工厂
 * @param meStorage     仓库是否是 ME 模式
 * @param storageOnline 仓库是否在线
 * @param nodes         节点视图
 */
public record FactoryNodeTableS2C(int containerId, boolean hasFactory, boolean meStorage,
        boolean storageOnline, List<FactoryNodeSnapshotBuilder.NodeView> nodes)
        implements CustomPacketPayload {

    /** 单个成员最多几个方块；多方块落地后改由机器模型提供常量。 */
    public static final int MAX_BLOCKS_PER_MEMBER = 64;

    /** 包类型：{@code idlefactory:node_table}。 */
    public static final Type<FactoryNodeTableS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "node_table"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryNodeTableS2C> STREAM_CODEC =
            StreamCodec.of(FactoryNodeTableS2C::write, FactoryNodeTableS2C::read);

    public FactoryNodeTableS2C {
        nodes = List.copyOf(nodes);
    }

    /** 没有工厂时的空表。 */
    public static FactoryNodeTableS2C empty(int containerId) {
        return new FactoryNodeTableS2C(containerId, false, false, false, List.of());
    }

    @Override
    public Type<FactoryNodeTableS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryNodeTableS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientNodeTableHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryNodeTableS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.hasFactory());
        buf.writeBoolean(payload.meStorage());
        buf.writeBoolean(payload.storageOnline());
        buf.writeVarInt(payload.nodes().size());
        for (FactoryNodeSnapshotBuilder.NodeView node : payload.nodes()) {
            writeNode(buf, node);
        }
    }

    private static FactoryNodeTableS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        boolean hasFactory = buf.readBoolean();
        boolean meStorage = buf.readBoolean();
        boolean storageOnline = buf.readBoolean();
        int count = readCount(buf, MachineLibrary.MAX_MACHINES, "nodes");
        List<FactoryNodeSnapshotBuilder.NodeView> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(readNode(buf));
        }
        return new FactoryNodeTableS2C(containerId, hasFactory, meStorage, storageOnline, nodes);
    }

    private static void writeNode(RegistryFriendlyByteBuf buf,
            FactoryNodeSnapshotBuilder.NodeView node) {
        buf.writeUUID(node.nodeId());
        buf.writeBoolean(node.name() != null);
        if (node.name() != null) {
            buf.writeUtf(node.name(), Machine.MAX_NAME_LENGTH);
        }
        writeNullableResourceLocation(buf, node.blockId());
        buf.writeBoolean(node.machineMissing());
        buf.writeByte(node.health().ordinal());
        buf.writeByte(node.totalState().ordinal());
        buf.writeVarInt(node.ruleCount());
        buf.writeVarInt(node.patternCount());
        buf.writeBoolean(node.provider().available());
        buf.writeByte(node.provider().blocking().ordinal());
        buf.writeVarInt(node.members().size());
        for (FactoryNodeSnapshotBuilder.MemberView member : node.members()) {
            writeMember(buf, member);
        }
    }

    private static FactoryNodeSnapshotBuilder.NodeView readNode(RegistryFriendlyByteBuf buf) {
        UUID nodeId = buf.readUUID();
        String name = buf.readBoolean() ? buf.readUtf(Machine.MAX_NAME_LENGTH) : null;
        ResourceLocation blockId = readNullableResourceLocation(buf);
        boolean machineMissing = buf.readBoolean();
        Health health = readEnum(buf, Health.values(), "health");
        FactorySnapshotBuilder.TotalState totalState =
                readEnum(buf, FactorySnapshotBuilder.TotalState.values(), "total state");
        int ruleCount = buf.readVarInt();
        int patternCount = buf.readVarInt();
        boolean available = buf.readBoolean();
        BlockingMode blocking = readEnum(buf, BlockingMode.values(), "blocking");
        int memberCount = readCount(buf, MachineLibrary.MAX_MACHINES, "members");
        List<FactoryNodeSnapshotBuilder.MemberView> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            members.add(readMember(buf));
        }
        return new FactoryNodeSnapshotBuilder.NodeView(nodeId, name, blockId, machineMissing, health,
                totalState, ruleCount, patternCount,
                new FactoryNodeSnapshotBuilder.ProviderView(available, blocking), members);
    }

    private static void writeMember(RegistryFriendlyByteBuf buf,
            FactoryNodeSnapshotBuilder.MemberView member) {
        buf.writeUUID(member.machineId());
        ResourceLocation.STREAM_CODEC.encode(buf, member.dimension());
        buf.writeVarInt(member.blocks().size());
        for (BlockPos pos : member.blocks()) {
            buf.writeLong(pos.asLong());
        }
        writeNullableResourceLocation(buf, member.blockId());
        buf.writeBoolean(member.machineMissing());
        buf.writeByte(member.health().ordinal());
    }

    private static FactoryNodeSnapshotBuilder.MemberView readMember(RegistryFriendlyByteBuf buf) {
        UUID machineId = buf.readUUID();
        ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
        int blockCount = readCount(buf, MAX_BLOCKS_PER_MEMBER, "blocks");
        List<BlockPos> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            blocks.add(BlockPos.of(buf.readLong()));
        }
        ResourceLocation blockId = readNullableResourceLocation(buf);
        boolean machineMissing = buf.readBoolean();
        Health health = readEnum(buf, Health.values(), "member health");
        return new FactoryNodeSnapshotBuilder.MemberView(machineId, dimension, blocks, blockId,
                machineMissing, health);
    }

    private static void writeNullableResourceLocation(RegistryFriendlyByteBuf buf,
            @Nullable ResourceLocation id) {
        buf.writeBoolean(id != null);
        if (id != null) {
            ResourceLocation.STREAM_CODEC.encode(buf, id);
        }
    }

    @Nullable
    private static ResourceLocation readNullableResourceLocation(RegistryFriendlyByteBuf buf) {
        return buf.readBoolean() ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
    }

    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buf, E[] values,
            String name) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid " + name + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int max, String name) {
        int count = buf.readVarInt();
        if (count < 0 || count > max) {
            throw new DecoderException("invalid " + name + " count: " + count);
        }
        return count;
    }
}
