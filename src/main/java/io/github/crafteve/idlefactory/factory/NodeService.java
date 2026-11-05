package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.node.BlockingMode;
import io.github.crafteve.idlefactory.factory.node.DispatchMode;
import io.github.crafteve.idlefactory.factory.node.Multiplier;
import io.github.crafteve.idlefactory.factory.node.MultiplierMode;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.probe.NodeHealth;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /idf node ...} 的指令逻辑：节点信息、改名、阻挡、倍率、派单与成员增删。
 *
 * <p>与 {@link MachineService} 分工：机器指令仍按机器定位，但规则作用于节点；节点指令直接以
 * 节点为主语。回执控制在 60 字以内，Tab 补全按节点 / 成员过滤。</p>
 */
public final class NodeService {
    private NodeService() {
        // 纯静态指令层
    }

    /**
     * 列出玩家工厂里的节点。
     *
     * @param player 玩家
     * @return 命令结果
     */
    public static int list(ServerPlayer player) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        List<Node> nodes = factory.config().nodes().all();
        if (nodes.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.info(
                    "还没有节点。对准方块执行 /idf factory connect 或用绑定杖接入"));
            return 1;
        }
        FactoryMessages.send(player, FactoryMessages.info("节点 " + nodes.size() + " 个："));
        int shown = 0;
        for (Node node : nodes) {
            if (shown >= 24) {
                FactoryMessages.send(player, FactoryMessages.info(
                        "… 还有 " + (nodes.size() - shown) + " 个"));
                break;
            }
            FactoryMessages.send(player, FactoryMessages.text("  " + describe(node),
                    FactoryMessages.INFO));
            shown++;
        }
        return 1;
    }

    /**
     * 打印一个节点的信息：健康、机器、方块、规则、样板、倍率与派单。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @return 命令结果
     */
    public static int info(ServerPlayer player, String ref) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        NodeHealth health = runtime == null ? NodeHealth.OFFLINE : nodeHealth(factory, node);
        FactoryMessages.send(player, FactoryMessages.info(describe(node)));
        FactoryMessages.send(player, FactoryMessages.text(
                "  健康: " + health.describe() + " · 机器 " + node.machineCount()
                        + " 台 · 方块 " + node.blockCount() + " 个",
                FactoryMessages.PLAIN));
        FactoryMessages.send(player, FactoryMessages.text(
                "  规则: 输入 " + node.io().inputs().size() + " / 输出 "
                        + node.io().outputs().size() + " 条 · 样板 " + node.patternCount() + " 张",
                FactoryMessages.PLAIN));
        FactoryMessages.send(player, FactoryMessages.text(
                "  倍率: " + NodeActions.describeMultiplier(node.provider().multiplier())
                        + " · 派单: "
                        + (node.provider().dispatch() == DispatchMode.ROUND_ROBIN ? "轮询" : "顺序")
                        + " · 阻挡: " + (node.provider().blocking().isOn() ? "开" : "关"),
                FactoryMessages.PLAIN));
        return 1;
    }

    /**
     * 改节点名字。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @param name   新名字；空串表示恢复方块名
     * @return 命令结果
     */
    public static int rename(ServerPlayer player, String ref, String name) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        return send(player, NodeActions.rename(factory, node.id(), name));
    }

    /**
     * 设置节点阻挡。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @param mode   开关
     * @return 命令结果
     */
    public static int blocking(ServerPlayer player, String ref, BlockingMode mode) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        return send(player, NodeActions.setBlocking(factory, node.id(), mode));
    }

    /**
     * 设置节点倍率。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @param mode   倍率模式
     * @param factor 固定倍数；非固定模式忽略
     * @return 命令结果
     */
    public static int multiplier(ServerPlayer player, String ref, MultiplierMode mode, int factor) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        if (factor > Multiplier.HARD_MAX_FACTOR) {
            return send(player, NodeActions.Result.err(
                    "倍率最多 ×" + Multiplier.HARD_MAX_FACTOR));
        }
        return send(player, NodeActions.setMultiplier(factory, node.id(), mode, factor));
    }

    /**
     * 设置节点派单模式。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @param mode   派单模式
     * @return 命令结果
     */
    public static int dispatch(ServerPlayer player, String ref, DispatchMode mode) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        return send(player, NodeActions.setDispatch(factory, node.id(), mode));
    }

    /**
     * 把准星对准的方块作为一台单方块机器追加到节点。
     *
     * @param player 玩家
     * @param ref    节点短 id
     * @return 命令结果
     */
    public static int memberAdd(ServerPlayer player, String ref) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        var pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 "
                    + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BindingDraft draft = new BindingDraft(BindingMode.SINGLE, node.id(),
                List.of(new BindingDraft.MachineDraft(
                        List.of(io.github.crafteve.idlefactory.factory.location.BlockKey.of(
                                level.dimension(), pos)))));
        FactoryService.BindingResult result = FactoryService.commitBinding(factory, draft);
        if (result.ok()) {
            FactoryMessages.ok(player, "已加成员：机器 " + result.machines() + " 台 / 方块 "
                    + result.blocks() + " 个");
        } else {
            FactoryMessages.err(player, result.message());
        }
        return result.ok() ? 1 : 0;
    }

    /**
     * 从节点移除一台成员机器；删最后一台等价删节点。
     *
     * @param player  玩家
     * @param ref     节点短 id
     * @param machine 机器短 id
     * @return 命令结果
     */
    public static int memberRemove(ServerPlayer player, String ref, String machine) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        Node node = requireNode(player, factory, ref);
        if (node == null) {
            return 0;
        }
        Optional<io.github.crafteve.idlefactory.factory.machine.Machine> found =
                factory.config().nodes().machineByShortId(machine);
        if (found.isEmpty() || node.machineOf(found.get().id()).isEmpty()) {
            FactoryMessages.err(player, "节点里没有机器 " + machine);
            return 0;
        }
        NodeActions.Result result = FactoryService.deleteMachineById(factory, found.get().id());
        return send(player, result);
    }

    /**
     * 节点一行的简述，列表、补全与 info 共用。
     *
     * @param node 节点
     * @return 如「[ab12cd34] 熔炉 · 机器 8 台 · 规则 3 条」
     */
    public static String describe(Node node) {
        String name = node.name() == null ? node.typeBlockId().toString() : node.name();
        return "[" + shortId(node.id()) + "] " + name + " · 机器 " + node.machineCount()
                + " 台 · 规则 " + node.ruleCount() + " 条";
    }

    /**
     * 取节点短 id。
     *
     * @param id 节点 id
     * @return 前 8 位
     */
    public static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @Nullable
    private static Node requireNode(ServerPlayer player, Factory factory, String ref) {
        Optional<Node> node = FactoryRefs.node(factory, ref);
        if (node.isEmpty()) {
            FactoryMessages.err(player, "找不到节点 " + ref);
            return null;
        }
        return node.get();
    }

    @Nullable
    private static Factory requireFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.err(player, "Idle Factory 尚未启动（服务端未就绪）");
            return null;
        }
        Factory factory = FactoryRefs.owned(runtime.manager(), player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }

    private static NodeHealth nodeHealth(Factory factory, Node node) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null || runtime.observations() == null) {
            return NodeHealth.OFFLINE;
        }
        var observations = runtime.observations();
        java.util.Map<io.github.crafteve.idlefactory.factory.location.BlockKey,
                io.github.crafteve.idlefactory.factory.probe.BlockObservation> map =
                new java.util.HashMap<>();
        for (var machine : node.machines()) {
            for (var block : machine.blocks()) {
                var observation = observations.observe(block);
                map.put(block, new io.github.crafteve.idlefactory.factory.probe.BlockObservation(
                        observation.blockId(), observation.health(),
                        observation.health() != io.github.crafteve.idlefactory.factory.probe.Health.UNKNOWN,
                        observation.observedAtTick()));
            }
        }
        return io.github.crafteve.idlefactory.factory.node.NodeHealthResolver
                .resolve(node, map, observations.currentTick(),
                        FactoryLimitsConfig.staleHealthTicks()).health();
    }

    private static int send(ServerPlayer player, NodeActions.Result result) {
        if (result.ok()) {
            FactoryMessages.ok(player, result.message());
            return 1;
        }
        FactoryMessages.err(player, result.message());
        return 0;
    }
}
