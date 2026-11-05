package io.github.crafteve.idlefactory.factory;

import java.util.Locale;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.node.BlockingMode;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.factory.node.NodeProvider;
import io.github.crafteve.idlefactory.factory.node.MachineNode;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 机器指令的逻辑层：{@code /idf machine ...} 的读写动作。
 *
 * <p>P1 删除产线后，原来挂在 {@code /idf line <line> manage ...} 下的 IO 规则操作改为直接以机器为主语；
 * 本类只做「解析引用 + 调 {@link NodeActions} + 拼聊天栏回执」，规则本身仍然跟着机器走。</p>
 *
 * <p>引用解析统一走 {@link FactoryRefs}，消息统一走 {@link FactoryMessages}。</p>
 */
public final class MachineService {
    /** 一次列表最多列多少台。 */
    private static final int LIST_LIMIT = 24;

    private MachineService() {
        // 纯静态工具类
    }

    /**
     * 列出玩家工厂里的机器。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int list(ServerPlayer player) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        int total = factory.config().nodes().totalMachineCount();
        if (total == 0) {
            FactoryMessages.send(player, FactoryMessages.info(
                    "还没有机器。对准方块执行 /idf factory connect 接入"));
            return 1;
        }
        FactoryMessages.send(player, FactoryMessages.info("机器 " + total + " 台："));
        int shown = 0;
        for (Node node : factory.config().nodes().all()) {
            for (Machine machine : node.machines()) {
                if (shown >= LIST_LIMIT) {
                    FactoryMessages.send(player, FactoryMessages.info(
                            "… 还有 " + (total - shown) + " 台"));
                    return 1;
                }
                BlockKey typeBlock = machine.typeBlock();
                String where = typeBlock.dimension().location() + " "
                        + typeBlock.pos().toShortString();
                FactoryMessages.send(player, Component.empty()
                        .append(FactoryMessages.hover(machine.shortId(), where))
                        .append(FactoryMessages.text("  输入 " + node.io().inputs().size()
                                + " / 输出 " + node.io().outputs().size() + " 条 · "
                                + where, FactoryMessages.INFO)));
                shown++;
            }
        }
        return 1;
    }

    /**
     * 查看一台机器的 IO 规则。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int info(ServerPlayer player, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Machine machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        Optional<Node> entry = factory.config().nodes().nodeOfMachine(machine.id());
        if (entry.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("这台机器不在节点库"));
            return 0;
        }
        NodeIoGroup io = entry.get().io();
        BlockKey typeBlock = machine.typeBlock();
        String where = typeBlock.dimension().location() + " " + typeBlock.pos().toShortString();
        FactoryMessages.send(player, FactoryMessages.info(
                "机器 [" + machine.shortId() + "] " + where));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输入: " + describeSpecs(io.inputs()), FactoryMessages.PLAIN));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输出: " + describeSpecs(io.outputs()), FactoryMessages.PLAIN));
        return 1;
    }

    /**
     * 加一条输入规则（物品）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @param face       面名；{@code null} 或 {@code any} 表示不限
     * @return 命令结果
     */
    public static int inputAdd(ServerPlayer player, String machineRef, ItemStack item,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, AeKeySupport.item(item), face, IoSide.INPUT);
    }

    /**
     * 加一条输入规则（流体）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @param face       面名
     * @return 命令结果
     */
    public static int inputAddFluid(ServerPlayer player, String machineRef, String fluidId,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, parseFluid(fluidId), face, IoSide.INPUT);
    }

    /**
     * 加一条输入规则（能量）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param face       面名
     * @return 命令结果
     */
    public static int inputAddEnergy(ServerPlayer player, String machineRef,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, AeKeySupport.energy(), face, IoSide.INPUT);
    }

    /**
     * 移除一条输入规则（物品）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int inputRemove(ServerPlayer player, String machineRef, ItemStack item) {
        return remove(player, machineRef, AeKeySupport.item(item), IoSide.INPUT);
    }

    /**
     * 移除一条输入规则（流体）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @return 命令结果
     */
    public static int inputRemoveFluid(ServerPlayer player, String machineRef, String fluidId)
            throws CommandSyntaxException {
        return remove(player, machineRef, parseFluid(fluidId), IoSide.INPUT);
    }

    /**
     * 移除一条输入规则（能量）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int inputRemoveEnergy(ServerPlayer player, String machineRef) {
        return remove(player, machineRef, AeKeySupport.energy(), IoSide.INPUT);
    }

    /**
     * 加一条输出规则（物品）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @param face       面名
     * @return 命令结果
     */
    public static int outputAdd(ServerPlayer player, String machineRef, ItemStack item,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, AeKeySupport.item(item), face, IoSide.OUTPUT);
    }

    /**
     * 加一条输出规则（流体）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @param face       面名
     * @return 命令结果
     */
    public static int outputAddFluid(ServerPlayer player, String machineRef, String fluidId,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, parseFluid(fluidId), face, IoSide.OUTPUT);
    }

    /**
     * 加一条输出规则（能量）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param face       面名
     * @return 命令结果
     */
    public static int outputAddEnergy(ServerPlayer player, String machineRef,
            @Nullable String face) throws CommandSyntaxException {
        return add(player, machineRef, AeKeySupport.energy(), face, IoSide.OUTPUT);
    }

    /**
     * 移除一条输出规则（物品）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int outputRemove(ServerPlayer player, String machineRef, ItemStack item) {
        return remove(player, machineRef, AeKeySupport.item(item), IoSide.OUTPUT);
    }

    /**
     * 移除一条输出规则（流体）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @return 命令结果
     */
    public static int outputRemoveFluid(ServerPlayer player, String machineRef, String fluidId)
            throws CommandSyntaxException {
        return remove(player, machineRef, parseFluid(fluidId), IoSide.OUTPUT);
    }

    /**
     * 移除一条输出规则（能量）。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int outputRemoveEnergy(ServerPlayer player, String machineRef) {
        return remove(player, machineRef, AeKeySupport.energy(), IoSide.OUTPUT);
    }

    /**
     * 设置机器的阻挡开关。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @param mode       新开关
     * @return 命令结果
     */
    public static int blocking(ServerPlayer player, String machineRef, BlockingMode mode) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Machine machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        return send(player, NodeActions.setBlocking(factory,
                nodeId(factory, machine), mode));
    }

    /**
     * 打印一台机器的样板供应器状态：阻挡、样板槽、余量与虚拟节点。
     *
     * @param player     玩家
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int provider(ServerPlayer player, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Machine ref = requireMachine(player, factory, machineRef);
        if (ref == null) {
            return 0;
        }
        Optional<Node> entry = factory.config().nodes().nodeOfMachine(ref.id());
        if (entry.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("这台机器不在节点库"));
            return 0;
        }
        NodeProvider provider = entry.get().provider();
        BlockKey typeBlock = ref.typeBlock();
        FactoryMessages.send(player, FactoryMessages.info(
                "机器 [" + ref.shortId() + "] " + typeBlock.dimension().location() + " "
                        + typeBlock.pos().toShortString()));
        FactoryMessages.send(player, FactoryMessages.text(
                "  阻挡: " + (provider.blocking().isOn() ? "开" : "关")
                        + " · 样板: " + provider.patternCount() + "/" + provider.capacity()
                        + " · 余量: " + provider.pendingCount() + " 条",
                FactoryMessages.PLAIN));
        FactoryRuntime runtime = FactoryRuntime.get();
        MachineNode node = runtime == null ? null : runtime.nodeManager().node(entry.get().id());
        FactoryMessages.send(player, FactoryMessages.text(
                "  节点: " + describeNode(node), FactoryMessages.PLAIN));
        if (node != null) {
            FactoryMessages.send(player, FactoryMessages.text(
                    "  解码样板: " + node.decodedPatternCount()
                            + " · 产物 key: " + node.productCount(),
                    FactoryMessages.PLAIN));
            long now = runtime == null ? 0L : runtime.server().overworld().getGameTime();
            FactoryMessages.send(player, FactoryMessages.text(
                    "  回收: " + describeSweep(node, now), FactoryMessages.PLAIN));
        }
        return 1;
    }

    /**
     * 一句话描述产物回收状态；抽不到时把原因点出来。
     *
     * @param node 节点
     * @param now  当前游戏时间
     * @return 给玩家看的短句
     */
    private static String describeSweep(MachineNode node, long now) {
        if (node.returnSweep().lastSweepTick() == 0L) {
            return "还没扫过";
        }
        StringBuilder text = new StringBuilder();
        text.append(node.returnSweep().storageFaces()).append("/6 面可抽取");
        long lastExtract = node.returnSweep().lastExtractTick();
        if (lastExtract > 0L) {
            text.append(" · 上次抽到 ").append(Math.max(0L, now - lastExtract)).append(" tick 前");
        } else {
            text.append(" · 还没抽到过");
        }
        text.append(" · 退避档 ").append(node.returnSweep().idleStep());
        if (node.isAvailable() && node.returnSweep().storageFaces() == 0
                && node.productCount() > 0) {
            text.append("（六面都没有可抽取的存储，产物抽不回来）");
        }
        return text.toString();
    }

    /**
     * 一句话描述虚拟节点状态。
     *
     * @param node 节点；未建时为 {@code null}
     * @return 给玩家看的短句
     */
    private static String describeNode(@Nullable MachineNode node) {
        if (node == null) {
            return "未建（仓库不是 ME 模式，或锚点解析不出 ME 节点）";
        }
        if (!node.isAttached()) {
            return "未接入";
        }
        return node.isNodeOnline() ? "在线" : "离线（网络没电 / 未启动）";
    }

    private static int add(ServerPlayer player, String machineRef, AEKey key,
            @Nullable String face, IoSide side) throws CommandSyntaxException {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Machine machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        // mode 传 null = 沿用旧命令默认（无组件物品模糊），界面会显式传精确。
        return send(player, NodeActions.addRule(factory, nodeId(factory, machine), side, key,
                parseFace(face), null));
    }

    private static int remove(ServerPlayer player, String machineRef, AEKey key, IoSide side) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Machine machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        return send(player, NodeActions.removeRule(factory, nodeId(factory, machine), side, key));
    }

    @Nullable
    private static Factory requireFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.send(player, FactoryMessages.err("Idle Factory 尚未启动（服务端未就绪）"));
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("你还没有工厂"));
            FactoryMessages.send(player, FactoryMessages.info("先执行 /idf create"));
            return null;
        }
        return factory.get();
    }

    @Nullable
    private static Machine requireMachine(ServerPlayer player, Factory factory, String ref) {
        Optional<Machine> target = FactoryRefs.machine(factory, ref);
        if (target.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("找不到机器 " + ref));
            FactoryMessages.send(player, FactoryMessages.info(
                    "用 /idf machine 查看机器库（短 id 8 位）"));
            return null;
        }
        return target.get();
    }

    @Nullable
    private static Direction parseFace(@Nullable String face) throws CommandSyntaxException {
        if (face == null || face.isEmpty() || ResourceSpec.ANY.equalsIgnoreCase(face)) {
            return null;
        }
        Direction direction = Direction.byName(face.toLowerCase(Locale.ROOT));
        if (direction == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "未知的面：" + face + "（可用 any/north/south/east/west/up/down）")).create();
        }
        return direction;
    }

    private static AEKey parseFluid(String fluidId) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocation.tryParse(fluidId);
        Fluid fluid = id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.get(id);
        if (fluid == Fluids.EMPTY) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "未知流体：" + fluidId)).create();
        }
        return AeKeySupport.fluid(fluid);
    }

    private static String describeSpecs(java.util.List<ResourceSpec> specs) {
        if (specs.isEmpty()) {
            return "(空)";
        }
        StringBuilder builder = new StringBuilder();
        for (ResourceSpec spec : specs) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(spec.describe());
        }
        return builder.toString();
    }

    /**
     * 把动作层的结果转成聊天栏消息。
     *
     * @param player 接收者
     * @param result 动作结果
     * @return 命令返回值：成功 {@code 1}，失败 {@code 0}
     */
    /**
     * 机器所属节点 id；调用方已确认机器存在，这里取不到时退回机器 id 只为不抛异常。
     *
     * @param factory 工厂
     * @param machine 机器
     * @return 节点 id
     */
    private static java.util.UUID nodeId(Factory factory, Machine machine) {
        return factory.config().nodes().nodeOfMachine(machine.id())
                .map(node -> node.id()).orElse(machine.id());
    }

    private static int send(ServerPlayer player, NodeActions.Result result) {
        FactoryMessages.send(player, result.ok()
                ? FactoryMessages.ok(result.message())
                : FactoryMessages.err(result.message()));
        return result.ok() ? 1 : 0;
    }
}
