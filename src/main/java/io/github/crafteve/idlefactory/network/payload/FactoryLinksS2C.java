package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryLinksHandler;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryLimitsConfig;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.MeAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageMode;
import io.github.crafteve.idlefactory.network.ModNetwork;
import io.github.crafteve.idlefactory.network.payload.wand.WandDraftCodec;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前仓库模式与「节点 / 机器 / 方块」三层链接快照。
 *
 * <p>客户端只用它画世界框与判断草稿三态，不拿它做业务判断。快照是整体替换：没有 revision、
 * 不合并，单线程生成加 TCP 有序已经保证不会乱序。节点里包含全部成员机器（失联的也在），
 * 因为渲染与「属于本节点」的判断都要用到它们。</p>
 *
 * @param mode    当前仓库模式
 * @param storage 仓库坐标（ME 一个、容器最多 {@link ContainerAnchor#MAX_CONTAINERS} 个）
 * @param nodes   全部节点
 */
public record FactoryLinksS2C(StorageMode mode, List<Link> storage, List<NodeLink> nodes)
        implements CustomPacketPayload {
    /** 仓库坐标上限，与容器上限一致。 */
    public static final int MAX_STORAGE = 16;

    /** 包类型：{@code idlefactory:factory_links}。 */
    public static final Type<FactoryLinksS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_links"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryLinksS2C> STREAM_CODEC =
            StreamCodec.of(FactoryLinksS2C::write, FactoryLinksS2C::read);

    public FactoryLinksS2C {
        storage = List.copyOf(storage);
        nodes = List.copyOf(nodes);
    }

    @Override
    public Type<FactoryLinksS2C> type() {
        return TYPE;
    }

    /**
     * 由工厂当前状态生成同步包。
     *
     * @param factory 工厂
     * @return 同步包
     */
    public static FactoryLinksS2C of(Factory factory) {
        List<Link> storage = new ArrayList<>();
        StorageAnchor anchor = factory.config().storageAnchor();
        if (anchor instanceof MeAnchor me) {
            storage.add(new Link(me.dimension(), me.pos()));
        } else if (anchor instanceof ContainerAnchor containers) {
            for (ContainerRef ref : containers.containers()) {
                storage.add(new Link(ref.dimension(), ref.pos()));
            }
        }
        List<NodeLink> nodes = new ArrayList<>();
        for (Node node : factory.config().nodes().all()) {
            List<MachineLink> machines = new ArrayList<>(node.machineCount());
            for (Machine machine : node.machines()) {
                machines.add(new MachineLink(machine.id(), machine.block()));
            }
            nodes.add(new NodeLink(node.id(), node.typeBlockId(), machines));
        }
        return new FactoryLinksS2C(factory.config().storageMode(), storage, nodes);
    }

    /** 空状态：没有工厂或还没绑定。 */
    public static FactoryLinksS2C empty() {
        return new FactoryLinksS2C(StorageMode.ME, List.of(), List.of());
    }

    /**
     * 客户端处理：整体替换本地快照缓存。
     *
     * @param payload 同步包
     * @param context 上下文
     */
    public static void handle(FactoryLinksS2C payload, IPayloadContext context) {
        ClientFactoryLinksHandler.handle(payload, context);
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryLinksS2C payload) {
        buf.writeEnum(payload.mode());
        writeLinks(buf, payload.storage(), MAX_STORAGE);
        int nodeLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxNodes());
        int machinePerNodeLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxMachinesPerNode());
        int totalLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxTotalMachines());
        int nodeCount = Math.min(payload.nodes().size(), nodeLimit);
        buf.writeVarInt(nodeCount);
        int writtenMachines = 0;
        for (int i = 0; i < nodeCount; i++) {
            NodeLink node = payload.nodes().get(i);
            buf.writeUUID(node.nodeId());
            buf.writeResourceLocation(node.typeBlockId());
            int machineCount = Math.min(node.machines().size(), machinePerNodeLimit);
            machineCount = Math.min(machineCount, totalLimit - writtenMachines);
            buf.writeVarInt(machineCount);
            for (int j = 0; j < machineCount; j++) {
                MachineLink machine = node.machines().get(j);
                buf.writeUUID(machine.machineId());
                WandDraftCodec.writeBlockKey(buf, machine.block());
            }
            writtenMachines += machineCount;
        }
    }

    private static FactoryLinksS2C read(RegistryFriendlyByteBuf buf) {
        StorageMode mode = buf.readEnum(StorageMode.class);
        List<Link> storage = readLinks(buf, MAX_STORAGE);
        int nodeLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxNodes());
        int machinePerNodeLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxMachinesPerNode());
        int totalLimit = ModNetwork.wireLimit(FactoryLimitsConfig.maxTotalMachines());
        int nodeCount = readCount(buf, nodeLimit, "节点");
        List<NodeLink> nodes = new ArrayList<>(nodeCount);
        int readMachines = 0;
        for (int i = 0; i < nodeCount; i++) {
            UUID nodeId = buf.readUUID();
            ResourceLocation typeBlockId = buf.readResourceLocation();
            int machineCount = readCount(buf, machinePerNodeLimit, "节点机器");
            if (readMachines + machineCount > totalLimit) {
                throw new DecoderException("机器总数越界：" + (readMachines + machineCount));
            }
            List<MachineLink> machines = new ArrayList<>(machineCount);
            for (int j = 0; j < machineCount; j++) {
                machines.add(new MachineLink(buf.readUUID(),
                        WandDraftCodec.readBlockKey(buf)));
            }
            readMachines += machineCount;
            nodes.add(new NodeLink(nodeId, typeBlockId, machines));
        }
        return new FactoryLinksS2C(mode, storage, nodes);
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int limit, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > limit) {
            throw new DecoderException(what + "数量越界：" + count);
        }
        return count;
    }

    private static void writeLinks(RegistryFriendlyByteBuf buf, List<Link> links, int max) {
        int count = Math.min(links.size(), max);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Link link = links.get(i);
            buf.writeResourceLocation(link.dimension().location());
            buf.writeBlockPos(link.pos());
        }
    }

    private static List<Link> readLinks(RegistryFriendlyByteBuf buf, int max) {
        int count = readCount(buf, max, "仓库坐标");
        List<Link> links = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    buf.readResourceLocation());
            links.add(new Link(dimension, buf.readBlockPos()));
        }
        return links;
    }

    /**
     * 一个仓库坐标。
     *
     * @param dimension 维度
     * @param pos       方块坐标
     */
    public record Link(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /**
     * 一个节点及其全部成员机器。
     *
     * @param nodeId      节点 id
     * @param typeBlockId 节点类型方块 id，编辑已有节点时作类型基准
     * @param machines    成员机器
     */
    public record NodeLink(UUID nodeId, ResourceLocation typeBlockId, List<MachineLink> machines) {
        public NodeLink {
            machines = List.copyOf(machines);
            if (machines.size() > NodeLibrary.HARD_MAX_MACHINES_PER_NODE) {
                throw new IllegalArgumentException("节点机器数超过硬上限");
            }
        }
    }

    /**
     * 一台机器与它所在的唯一方块。
     *
     * @param machineId 机器 id
     * @param block     机器方块
     */
    public record MachineLink(UUID machineId, BlockKey block) {
    }
}
