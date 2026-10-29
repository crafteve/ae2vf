package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryTableHandler;
import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.io.IoResult;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.probe.WorkState;
import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.network.ResourceKeyCodec;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的机器表。
 *
 * <p>直接携带现有 {@link FactorySnapshotBuilder.MachineView}，不另建界面 DTO；指令和界面看到
 * 的是同一份机器视图。</p>
 *
 * @param containerId     菜单容器 id
 * @param hasFactory      玩家是否已有工厂
 * @param storageOnline   仓库是否在线
 * @param lineCount       产线数
 * @param runningLineCount 运行中的产线数
 * @param lines           产线列表（界面菜单/筛选）
 * @param machines        机器库视图（含回收站）
 */
public record FactoryTableS2C(int containerId, boolean hasFactory,
        boolean storageOnline, int lineCount, int runningLineCount,
        List<FactorySnapshotBuilder.LineView> lines,
        List<FactorySnapshotBuilder.MachineView> machines) implements CustomPacketPayload {

    private static final int MAX_MACHINES = 512;
    private static final int MAX_LINES = 64;
    private static final int MAX_LINE_NAME = 64;
    private static final int MAX_LINE_NAMES = 8;
    private static final int MAX_RULES_PER_SIDE = 16;

    /** 包类型：{@code idlefactory:factory_table}。 */
    public static final Type<FactoryTableS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "factory_table"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTableS2C> STREAM_CODEC =
            StreamCodec.of(FactoryTableS2C::write, FactoryTableS2C::read);

    public FactoryTableS2C {
        lines = List.copyOf(lines);
        machines = List.copyOf(machines);
    }

    /** 没有工厂时的空表。 */
    public static FactoryTableS2C empty(int containerId) {
        return new FactoryTableS2C(containerId, false, false, 0, 0,
                List.of(), List.of());
    }

    @Override
    public Type<FactoryTableS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryTableS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryTableHandler.handle(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryTableS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.hasFactory());
        buf.writeBoolean(payload.storageOnline());
        buf.writeVarInt(payload.lineCount());
        buf.writeVarInt(payload.runningLineCount());
        writeLines(buf, payload.lines());
        buf.writeVarInt(payload.machines().size());
        for (FactorySnapshotBuilder.MachineView machine : payload.machines()) {
            writeMachine(buf, machine);
        }
    }

    private static FactoryTableS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        boolean hasFactory = buf.readBoolean();
        boolean storageOnline = buf.readBoolean();
        int lineCount = buf.readVarInt();
        int runningLineCount = buf.readVarInt();
        List<FactorySnapshotBuilder.LineView> lines = readLines(buf);
        int count = readCount(buf, MAX_MACHINES, "machines");
        List<FactorySnapshotBuilder.MachineView> machines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            machines.add(readMachine(buf));
        }
        return new FactoryTableS2C(containerId, hasFactory, storageOnline,
                lineCount, runningLineCount, lines, machines);
    }

    private static void writeLines(RegistryFriendlyByteBuf buf,
            List<FactorySnapshotBuilder.LineView> lines) {
        int count = Math.min(lines.size(), MAX_LINES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            FactorySnapshotBuilder.LineView line = lines.get(i);
            buf.writeUUID(line.id());
            buf.writeUtf(line.name(), MAX_LINE_NAME);
            buf.writeBoolean(line.running());
            buf.writeVarInt(Math.max(0, line.machineCount()));
            buf.writeBoolean(line.sleeping());
            buf.writeVarLong(Math.max(0L, line.sleepRemainingTicks()));
        }
    }

    private static List<FactorySnapshotBuilder.LineView> readLines(RegistryFriendlyByteBuf buf) {
        int count = readCount(buf, MAX_LINES, "lines");
        List<FactorySnapshotBuilder.LineView> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            String name = buf.readUtf(MAX_LINE_NAME);
            boolean running = buf.readBoolean();
            int machineCount = buf.readVarInt();
            if (machineCount < 0) {
                throw new DecoderException("invalid line machine count: " + machineCount);
            }
            boolean sleeping = buf.readBoolean();
            long sleepRemainingTicks = buf.readVarLong();
            if (sleepRemainingTicks < 0L) {
                throw new DecoderException("invalid line sleep ticks: " + sleepRemainingTicks);
            }
            lines.add(new FactorySnapshotBuilder.LineView(id, name, running, machineCount,
                    sleeping, sleepRemainingTicks));
        }
        return lines;
    }

    private static void writeMachine(RegistryFriendlyByteBuf buf,
            FactorySnapshotBuilder.MachineView machine) {
        MachineRef ref = machine.ref();
        buf.writeUUID(ref.id());
        ResourceLocation.STREAM_CODEC.encode(buf, ref.dimension().location());
        buf.writeLong(ref.pos().asLong());
        buf.writeBoolean(machine.detached());
        buf.writeByte(machine.health().ordinal());
        writeNullableResourceLocation(buf, machine.blockId());
        buf.writeByte(kindMask(machine.presence()));
        buf.writeVarInt(machine.lineNames().size());
        for (String name : machine.lineNames()) {
            buf.writeUtf(name, MAX_LINE_NAME);
        }
        buf.writeVarInt(machine.lineIds().size());
        for (UUID lineId : machine.lineIds()) {
            buf.writeUUID(lineId);
        }
        buf.writeVarInt(machine.ruleCount());
        writeRules(buf, machine.inputs());
        writeRates(buf, machine.inputRates());
        writeReasons(buf, machine.inputReasons());
        writeRules(buf, machine.outputs());
        writeRates(buf, machine.outputRates());
        writeReasons(buf, machine.outputReasons());
        buf.writeVarLong(machine.sinceMoveTicks());
        buf.writeVarLong(machine.blockedTicks());
        buf.writeByte(machine.workState().ordinal());
        buf.writeBoolean(machine.inputFull());
        buf.writeVarLong(machine.sinceInputMoveTicks());
        buf.writeByte(machine.inputReason().ordinal());
        buf.writeByte(machine.outputReason().ordinal());
    }

    private static FactorySnapshotBuilder.MachineView readMachine(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
        long packedPos = buf.readLong();
        boolean detached = buf.readBoolean();
        Health health = readEnum(buf, Health.values(), "health");
        ResourceLocation blockId = readNullableResourceLocation(buf);
        Set<ResourceKind> presence = readKinds(buf.readUnsignedByte());
        int lineCount = readCount(buf, MAX_LINE_NAMES, "line names");
        List<String> lineNames = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            lineNames.add(buf.readUtf(MAX_LINE_NAME));
        }
        int lineIdCount = readCount(buf, MAX_LINE_NAMES, "line ids");
        List<UUID> lineIds = new ArrayList<>(lineIdCount);
        for (int i = 0; i < lineIdCount; i++) {
            lineIds.add(buf.readUUID());
        }
        int ruleCount = buf.readVarInt();
        List<ResourceSpec> inputs = readRules(buf);
        List<Long> inputRates = readRates(buf, inputs.size());
        List<IoResult.Reason> inputReasons = readReasons(buf, inputs.size());
        List<ResourceSpec> outputs = readRules(buf);
        List<Long> outputRates = readRates(buf, outputs.size());
        List<IoResult.Reason> outputReasons = readReasons(buf, outputs.size());
        long sinceMoveTicks = buf.readVarLong();
        long blockedTicks = buf.readVarLong();
        WorkState workState = readEnum(buf, WorkState.values(), "work state");
        boolean inputFull = buf.readBoolean();
        long sinceInputMoveTicks = buf.readVarLong();
        IoResult.Reason inputReason = readEnum(buf, IoResult.Reason.values(), "input reason");
        IoResult.Reason outputReason = readEnum(buf, IoResult.Reason.values(), "output reason");
        MachineRef ref = new MachineRef(id,
                net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(packedPos));
        return new FactorySnapshotBuilder.MachineView(ref, detached, health, blockId,
                presence, lineNames, lineIds, ruleCount, inputs, outputs, inputRates, outputRates,
                sinceMoveTicks, blockedTicks, workState, inputFull, sinceInputMoveTicks,
                inputReason, outputReason, inputReasons, outputReasons);
    }

    private static void writeReasons(RegistryFriendlyByteBuf buf,
            List<IoResult.Reason> reasons) {
        int count = Math.min(reasons.size(), MAX_RULES_PER_SIDE);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeByte(reasons.get(i).ordinal());
        }
    }

    private static List<IoResult.Reason> readReasons(RegistryFriendlyByteBuf buf, int expected) {
        int count = readCount(buf, MAX_RULES_PER_SIDE, "rule reasons");
        List<IoResult.Reason> reasons = new ArrayList<>(expected);
        for (int i = 0; i < count; i++) {
            reasons.add(readEnum(buf, IoResult.Reason.values(), "rule reason"));
        }
        while (reasons.size() < expected) {
            reasons.add(IoResult.Reason.NO_ITEM);
        }
        return reasons;
    }

    private static void writeRates(RegistryFriendlyByteBuf buf, List<Long> rates) {
        int count = Math.min(rates.size(), MAX_RULES_PER_SIDE);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeVarLong(rates.get(i));
        }
    }

    private static List<Long> readRates(RegistryFriendlyByteBuf buf, int expected) {
        int count = readCount(buf, MAX_RULES_PER_SIDE, "rule rates");
        List<Long> rates = new ArrayList<>(expected);
        for (int i = 0; i < count; i++) {
            rates.add(buf.readVarLong());
        }
        while (rates.size() < expected) {
            rates.add(0L);
        }
        return rates;
    }

    private static void writeRules(RegistryFriendlyByteBuf buf, List<ResourceSpec> rules) {
        int count = Math.min(rules.size(), MAX_RULES_PER_SIDE);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ResourceSpec rule = rules.get(i);
            ResourceKeyCodec.write(buf, rule.key());
            buf.writeByte(rule.mode().ordinal());
            buf.writeBoolean(rule.face() != null);
            if (rule.face() != null) {
                buf.writeByte(rule.face().ordinal());
            }
            buf.writeLong(rule.amount());
        }
    }

    private static List<ResourceSpec> readRules(RegistryFriendlyByteBuf buf) {
        int count = readCount(buf, MAX_RULES_PER_SIDE, "rules");
        List<ResourceSpec> rules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceKey key = ResourceKeyCodec.read(buf);
            MatchMode mode = readEnum(buf, MatchMode.values(), "match mode");
            Direction face = null;
            if (buf.readBoolean()) {
                face = readEnum(buf, Direction.values(), "direction");
            }
            long amount = buf.readLong();
            rules.add(new ResourceSpec(key, face, amount, mode));
        }
        return rules;
    }

    private static int kindMask(Set<ResourceKind> kinds) {
        int mask = 0;
        for (ResourceKind kind : kinds) {
            mask |= 1 << kind.ordinal();
        }
        return mask;
    }

    private static Set<ResourceKind> readKinds(int mask) {
        EnumSet<ResourceKind> kinds = EnumSet.noneOf(ResourceKind.class);
        for (ResourceKind kind : ResourceKind.values()) {
            if ((mask & (1 << kind.ordinal())) != 0) {
                kinds.add(kind);
            }
        }
        return kinds;
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
