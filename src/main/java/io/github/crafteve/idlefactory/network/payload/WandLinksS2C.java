package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.IdleFactory;

import io.github.crafteve.idlefactory.client.network.ClientWandLinksHandler;
import io.github.crafteve.idlefactory.factory.Factory;

import io.github.crafteve.idlefactory.key.BlockKey;
import io.github.crafteve.idlefactory.machine.Machine;

import io.github.crafteve.idlefactory.network.IdleFactoryNetwork;
import io.github.crafteve.idlefactory.command.WandDraftCodec;

import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeLibrary;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：「节点 / 机器」两层链接快照。
 *
 * <p>客户端只用它画世界框与判断草稿三态，不拿它做业务判断。快照是整体替换：没有 revision、
 * 不合并，单线程生成加 TCP 有序已经保证不会乱序。节点里包含全部成员机器（失联的也在），
 * 因为渲染与「属于本节点」的判断都要用到它们。</p>
 *
 * @param storageAnchor 仓库锚点；未绑定为 {@code null}，存储杖用它画蓝色高亮
 * @param nodes 全部节点
 */
public record WandLinksS2C(@Nullable BlockKey storageAnchor, List<NodeLink> nodes) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:factory_links}。 */
    public static final Type<WandLinksS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "wand_links"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, WandLinksS2C> STREAM_CODEC =
            StreamCodec.of(WandLinksS2C::write, WandLinksS2C::read);

    public WandLinksS2C {
        nodes = List.copyOf(nodes);
    }

    @Override
    public Type<WandLinksS2C> type() {
        return TYPE;
    }

    /**
     * 由工厂当前状态生成同步包。
     *
     * @param factory 工厂
     * @return 同步包
     */
    public static WandLinksS2C of(Factory factory) {
        List<NodeLink> nodes = new ArrayList<>();
        for (Node node : factory.config().nodes().all()) {
            List<MachineLink> machines = new ArrayList<>(node.machineCount());
            for (Machine machine : node.machines()) {
                machines.add(new MachineLink(machine.id(), machine.block()));
            }
            nodes.add(new NodeLink(node.id(), node.typeBlockId(), machines));
        }
        return new WandLinksS2C(factory.config().storageAnchor(), nodes);
    }

    /** 空状态：没有工厂或还没有节点。 */
    public static WandLinksS2C empty() {
        return new WandLinksS2C(null, List.of());
    }

    /**
     * 客户端处理：整体替换本地快照缓存。
     *
     * @param payload 同步包
     * @param context 上下文
     */
    public static void handle(WandLinksS2C payload, IPayloadContext context) {
        ClientWandLinksHandler.handle(payload, context);
    }

    private static void write(RegistryFriendlyByteBuf buf, WandLinksS2C payload) {
        int nodeLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxNodes());
        int machinePerNodeLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxMachinesPerNode());
        int totalLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxTotalMachines());
        buf.writeBoolean(payload.storageAnchor() != null);
        if (payload.storageAnchor() != null) {
            WandDraftCodec.writeBlockKey(buf, payload.storageAnchor());
        }
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

    private static WandLinksS2C read(RegistryFriendlyByteBuf buf) {
        int nodeLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxNodes());
        int machinePerNodeLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxMachinesPerNode());
        int totalLimit = IdleFactoryNetwork.wireLimit(IdleFactoryConfig.maxTotalMachines());
        BlockKey storageAnchor = buf.readBoolean() ? WandDraftCodec.readBlockKey(buf) : null;
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
        return new WandLinksS2C(storageAnchor, nodes);
    }

    private static int readCount(RegistryFriendlyByteBuf buf, int limit, String what) {
        int count = buf.readVarInt();
        if (count < 0 || count > limit) {
            throw new DecoderException(what + "数量越界：" + count);
        }
        return count;
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
