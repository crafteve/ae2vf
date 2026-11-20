package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.io.BlockingMode;
import io.github.crafteve.idlefactory.io.DispatchMode;
import io.github.crafteve.idlefactory.io.IoReason;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.MachineHealth;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.network.IdleFactoryNetwork;
import io.github.crafteve.idlefactory.network.ResourceKeyCodec;
import io.github.crafteve.idlefactory.node.Multiplier;
import io.github.crafteve.idlefactory.node.MultiplierMode;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.observation.NodeHealth;
import io.github.crafteve.idlefactory.view.ViewHeader;
import io.github.crafteve.idlefactory.view.ViewIoRule;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewNode.Activity;
import io.github.crafteve.idlefactory.view.ViewNodeCore;
import io.github.crafteve.idlefactory.view.ViewNodeDiff;
import io.github.crafteve.idlefactory.view.ViewTable;
import io.github.crafteve.idlefactory.view.ViewTableDiff;

import appeng.api.stacks.AEKey;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 视图表族（{@link ViewTable} / {@link ViewTableDiff}）的唯一线协议编解码。
 *
 * <p>全量与差量是同一张表的两种形态：全量页、差量包都要写 {@link ViewNodeCore} /
 * {@link ViewIoRule} / {@link ViewMachine}，所以编解码只放在这里一处，两个报文只负责包头。
 * 写在某个报文里会让另一个报文反向依赖它的私有方法。</p>
 *
 * <p>枚举一律写 ordinal、读侧越界抛 {@link DecoderException}；计数用 varint 并在读侧夹到
 * 配置上限与线协议硬上限，避免畸形包把内存打爆。资源 key 统一走
 * {@link ResourceKeyCodec}（第 3c 步改名 {@code AeKeyCodec}）。</p>
 */
public final class ViewTableCodec {
    private ViewTableCodec() {
        // 纯静态编解码
    }

    /**
     * 写一张表（全量页；每页都带表头，客户端拼装时不依赖页序）。
     *
     * @param buf   目标缓冲
     * @param table 表
     */
    public static void writeTable(RegistryFriendlyByteBuf buf, ViewTable table) {
        writeHeader(buf, table.header());
        buf.writeVarInt(table.nodes().size());
        for (ViewNode node : table.nodes()) {
            writeNode(buf, node);
        }
    }

    /**
     * 读一张表。
     *
     * @param buf 来源缓冲
     * @return 表
     */
    public static ViewTable readTable(RegistryFriendlyByteBuf buf) {
        ViewHeader header = readHeader(buf);
        int count = readCount(buf, IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxNodes()), "nodes");
        List<ViewNode> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(readNode(buf));
        }
        return new ViewTable(header, nodes);
    }

    /**
     * 写一个节点行。
     *
     * @param buf  目标缓冲
     * @param node 节点行
     */
    public static void writeNode(RegistryFriendlyByteBuf buf, ViewNode node) {
        writeNodeCore(buf, node.core());
        buf.writeVarInt(node.rules().size());
        for (ViewIoRule rule : node.rules()) {
            writeRule(buf, rule);
        }
        buf.writeVarInt(node.machines().size());
        for (ViewMachine machine : node.machines()) {
            writeMachine(buf, machine);
        }
    }

    /**
     * 读一个节点行。
     *
     * @param buf 来源缓冲
     * @return 节点行
     */
    public static ViewNode readNode(RegistryFriendlyByteBuf buf) {
        ViewNodeCore core = readNodeCore(buf);
        int ruleCount = readCount(buf, ruleLimit(), "rules");
        List<ViewIoRule> rules = new ArrayList<>(ruleCount);
        for (int i = 0; i < ruleCount; i++) {
            rules.add(readRule(buf));
        }
        int machineCount = readCount(buf, IdleFactoryConfig.maxMachinesPerNode(), "machines");
        List<ViewMachine> machines = new ArrayList<>(machineCount);
        for (int i = 0; i < machineCount; i++) {
            machines.add(readMachine(buf));
        }
        return new ViewNode(core, rules, machines);
    }

    /**
     * 写一张差量。
     *
     * @param buf  目标缓冲
     * @param diff 差量
     */
    public static void writeDiff(RegistryFriendlyByteBuf buf, ViewTableDiff diff) {
        buf.writeLong(diff.baseRevision());
        buf.writeLong(diff.revision());
        buf.writeBoolean(diff.header() != null);
        if (diff.header() != null) {
            writeHeader(buf, diff.header());
        }
        buf.writeVarInt(diff.nodes().size());
        for (ViewNodeDiff node : diff.nodes()) {
            writeNodeDiff(buf, node);
        }
    }

    /**
     * 读一张差量。
     *
     * @param buf 来源缓冲
     * @return 差量
     */
    public static ViewTableDiff readDiff(RegistryFriendlyByteBuf buf) {
        long baseRevision = buf.readLong();
        long revision = buf.readLong();
        ViewHeader header = buf.readBoolean() ? readHeader(buf) : null;
        int count = readCount(buf, IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxNodes()), "diff nodes");
        List<ViewNodeDiff> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(readNodeDiff(buf));
        }
        return new ViewTableDiff(baseRevision, revision, header, nodes);
    }

    private static void writeNodeCore(RegistryFriendlyByteBuf buf, ViewNodeCore core) {
        buf.writeUUID(core.nodeId());
        buf.writeBoolean(core.name() != null);
        if (core.name() != null) {
            buf.writeUtf(core.name(), Node.HARD_MAX_NAME_LENGTH);
        }
        ResourceLocation.STREAM_CODEC.encode(buf, core.typeBlockId());
        buf.writeVarInt(core.order());
        buf.writeBoolean(core.probed());
        buf.writeBoolean(core.stale());
        buf.writeByte(core.health().ordinal());
        buf.writeByte(core.activity().ordinal());
        buf.writeVarInt(core.patternCount());
        buf.writeVarInt(core.pendingCount());
        buf.writeBoolean(core.autoEnergyOnly());
        buf.writeLong(core.pendingOldestAt());
        buf.writeBoolean(core.available());
        buf.writeByte(core.blocking().ordinal());
        buf.writeByte(core.multiplier().mode().ordinal());
        buf.writeVarInt(core.multiplier().factor());
        buf.writeByte(core.dispatch().ordinal());
    }

    private static ViewNodeCore readNodeCore(RegistryFriendlyByteBuf buf) {
        UUID nodeId = buf.readUUID();
        String name = buf.readBoolean() ? buf.readUtf(Node.HARD_MAX_NAME_LENGTH) : null;
        ResourceLocation typeBlockId = ResourceLocation.STREAM_CODEC.decode(buf);
        int order = buf.readVarInt();
        boolean probed = buf.readBoolean();
        boolean stale = buf.readBoolean();
        NodeHealth health = readEnum(buf, NodeHealth.values(), "node health");
        Activity activity = readEnum(buf, Activity.values(), "node activity");
        int patternCount = buf.readVarInt();
        int pendingCount = buf.readVarInt();
        boolean autoEnergyOnly = buf.readBoolean();
        long pendingOldestAt = buf.readLong();
        boolean available = buf.readBoolean();
        BlockingMode blocking = readEnum(buf, BlockingMode.values(), "blocking");
        Multiplier multiplier = new Multiplier(
                readEnum(buf, MultiplierMode.values(), "multiplier mode"), buf.readVarInt());
        DispatchMode dispatch = readEnum(buf, DispatchMode.values(), "dispatch");
        return new ViewNodeCore(nodeId, name, typeBlockId, order, probed, stale, health, activity,
                patternCount, pendingCount, autoEnergyOnly, pendingOldestAt, available, blocking,
                multiplier, dispatch);
    }

    private static void writeRule(RegistryFriendlyByteBuf buf, ViewIoRule rule) {
        buf.writeByte(rule.side().ordinal());
        ResourceKeyCodec.write(buf, rule.key());
        buf.writeBoolean(rule.face() != null);
        if (rule.face() != null) {
            buf.writeByte(rule.face().ordinal());
        }
        buf.writeByte(rule.mode().ordinal());
        buf.writeVarInt(rule.order());
        buf.writeVarLong(rule.rate());
        buf.writeByte(rule.reason().ordinal());
    }

    private static ViewIoRule readRule(RegistryFriendlyByteBuf buf) {
        IoSide side = readEnum(buf, IoSide.values(), "io side");
        AEKey key = ResourceKeyCodec.read(buf);
        Direction face = buf.readBoolean() ? readEnum(buf, Direction.values(), "rule face") : null;
        MatchMode mode = readEnum(buf, MatchMode.values(), "match mode");
        int order = buf.readVarInt();
        long rate = buf.readVarLong();
        IoReason reason = readEnum(buf, IoReason.values(), "io reason");
        return new ViewIoRule(side, key, face, mode, order, rate, reason);
    }

    private static void writeRuleKey(RegistryFriendlyByteBuf buf, ViewIoRule.Key key) {
        buf.writeByte(key.side().ordinal());
        ResourceKeyCodec.write(buf, key.key());
    }

    private static ViewIoRule.Key readRuleKey(RegistryFriendlyByteBuf buf) {
        return new ViewIoRule.Key(readEnum(buf, IoSide.values(), "io side"),
                ResourceKeyCodec.read(buf));
    }

    private static void writeMachine(RegistryFriendlyByteBuf buf, ViewMachine machine) {
        buf.writeUUID(machine.machineId());
        ResourceLocation.STREAM_CODEC.encode(buf, machine.block().dimension().location());
        buf.writeLong(machine.block().pos().asLong());
        buf.writeBoolean(machine.blockId() != null);
        if (machine.blockId() != null) {
            ResourceLocation.STREAM_CODEC.encode(buf, machine.blockId());
        }
        buf.writeByte(machine.health().ordinal());
        buf.writeBoolean(machine.probed());
        buf.writeBoolean(machine.stale());
        buf.writeVarInt(machine.order());
    }

    private static ViewMachine readMachine(RegistryFriendlyByteBuf buf) {
        UUID machineId = buf.readUUID();
        BlockKey block = BlockKey.of(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.STREAM_CODEC.decode(buf)),
                BlockPos.of(buf.readLong()));
        ResourceLocation blockId = buf.readBoolean()
                ? ResourceLocation.STREAM_CODEC.decode(buf) : null;
        MachineHealth health = readEnum(buf, MachineHealth.values(), "machine health");
        boolean probed = buf.readBoolean();
        boolean stale = buf.readBoolean();
        int order = buf.readVarInt();
        return new ViewMachine(machineId, block, blockId, health, probed, stale, order);
    }

    private static void writeNodeDiff(RegistryFriendlyByteBuf buf, ViewNodeDiff diff) {
        buf.writeUUID(diff.nodeId());
        buf.writeBoolean(diff.remove());
        buf.writeBoolean(diff.core() != null);
        if (diff.core() != null) {
            writeNodeCore(buf, diff.core());
        }
        buf.writeVarInt(diff.ruleUpserts().size());
        for (ViewIoRule rule : diff.ruleUpserts()) {
            writeRule(buf, rule);
        }
        buf.writeVarInt(diff.ruleRemoves().size());
        for (ViewIoRule.Key key : diff.ruleRemoves()) {
            writeRuleKey(buf, key);
        }
        buf.writeVarInt(diff.machineUpserts().size());
        for (ViewMachine machine : diff.machineUpserts()) {
            writeMachine(buf, machine);
        }
        buf.writeVarInt(diff.machineRemoves().size());
        for (UUID machineId : diff.machineRemoves()) {
            buf.writeUUID(machineId);
        }
    }

    private static ViewNodeDiff readNodeDiff(RegistryFriendlyByteBuf buf) {
        UUID nodeId = buf.readUUID();
        boolean remove = buf.readBoolean();
        ViewNodeCore core = buf.readBoolean() ? readNodeCore(buf) : null;
        int ruleUpsertCount = readCount(buf, ruleLimit(), "rule upserts");
        List<ViewIoRule> ruleUpserts = new ArrayList<>(ruleUpsertCount);
        for (int i = 0; i < ruleUpsertCount; i++) {
            ruleUpserts.add(readRule(buf));
        }
        int ruleRemoveCount = readCount(buf, ruleLimit(), "rule removes");
        List<ViewIoRule.Key> ruleRemoves = new ArrayList<>(ruleRemoveCount);
        for (int i = 0; i < ruleRemoveCount; i++) {
            ruleRemoves.add(readRuleKey(buf));
        }
        int machineUpsertCount = readCount(buf, IdleFactoryConfig.maxMachinesPerNode(),
                "machine upserts");
        List<ViewMachine> machineUpserts = new ArrayList<>(machineUpsertCount);
        for (int i = 0; i < machineUpsertCount; i++) {
            machineUpserts.add(readMachine(buf));
        }
        int machineRemoveCount = readCount(buf, IdleFactoryConfig.maxMachinesPerNode(),
                "machine removes");
        List<UUID> machineRemoves = new ArrayList<>(machineRemoveCount);
        for (int i = 0; i < machineRemoveCount; i++) {
            machineRemoves.add(buf.readUUID());
        }
        return new ViewNodeDiff(nodeId, remove, core, ruleUpserts, ruleRemoves, machineUpserts,
                machineRemoves);
    }

    private static void writeHeader(RegistryFriendlyByteBuf buf, ViewHeader header) {
        buf.writeBoolean(header.hasFactory());
        buf.writeBoolean(header.storageOnline());
    }

    private static ViewHeader readHeader(RegistryFriendlyByteBuf buf) {
        return new ViewHeader(buf.readBoolean(), buf.readBoolean());
    }

    /**
     * 一个节点的规则上限：输入、输出各 {@code maxRulesPerSide} 条。
     *
     * @return 解码时可接受的最大规则数
     */
    private static int ruleLimit() {
        return IdleFactoryConfig.maxRulesPerSide() * 2;
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
