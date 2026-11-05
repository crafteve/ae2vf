package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 绑定服务：把方块接入 / 移出工厂。
 *
 * <p>P1b 只实现 {@code SINGLE} 语义——一次接入一个方块、生成「1 节点 1 机器 1 方块」，
 * 保证数据切换后仍有入口；P7 再扩成绑定杖草稿的全量校验与原子提交。</p>
 *
 * <p>整组 toggle 的判定是「所有目标都已连接」才断开；只要有一台没连，就补齐整组。
 * 这样连点两次不会出现一半连、一半断。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调、观测服务与删除出口都从构造注入。</p>
 */
final class BindingService {
    private final FactoryManager manager;
    @Nullable
    private final ObservationService observations;
    private final NodeLibraryService nodeLibrary;
    private final MachineLinkService machineLinks;

    /**
     * @param manager       工厂档案柜
     * @param observations  观测服务；服务端未就绪时为 {@code null}
     * @param nodeLibrary   节点库写入口（整组修改只落盘一次）
     * @param machineLinks  机器接线服务，提供删除出口
     */
    BindingService(FactoryManager manager, @Nullable ObservationService observations,
            NodeLibraryService nodeLibrary, MachineLinkService machineLinks) {
        this.manager = manager;
        this.observations = observations;
        this.nodeLibrary = nodeLibrary;
        this.machineLinks = machineLinks;
    }

    /**
     * 机器 toggle：已连接就断开，未连接就连接；连锁按整组 toggle。
     *
     * @param player 发起请求的玩家
     * @param pos    目标机器
     * @param chain  是否连锁
     * @return 变更的台数
     */
    int toggleMachines(ServerPlayer player, BlockPos pos, boolean chain) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            FactoryMessages.barErr(player, "目标区块未加载");
            return 0;
        }
        // 轻检查：没有方块实体的不可能是机器。泥土、石头死在这一步，
        // 省掉后面贵的能力探测；注意这只是“跳过贵检查”，结论还是贵检查说了算。
        if (level.getBlockEntity(pos) == null) {
            FactoryMessages.barErr(player, "该方块不是可连接的机器");
            return 0;
        }
        // 本次操作现算一张占用表：先扫一遍档案，之后每个目标 O(1) 查表。
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck origin =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.MACHINE);
        if (origin.blocked()) {
            FactoryMessages.barErr(player, LinkGateMessages.gateMessage(origin));
            return 0;
        }
        if (!FactoryLinkFinder.isMachine(level, pos)) {
            FactoryMessages.barErr(player, "该方块不是可连接的机器");
            return 0;
        }
        List<BlockPos> found = chain
                ? FactoryLinkFinder.machines(level, pos)
                : List.of(pos);
        if (found.isEmpty()) {
            FactoryMessages.barErr(player, "附近没有可连接的机器");
            return 0;
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        LinkGate.Filter filter = gate.filter(factory.id(), LinkRole.MACHINE, level.dimension(), found);
        List<BlockPos> targets = filter.open();
        if (targets.isEmpty()) {
            FactoryMessages.barErr(player, filter.blockedRole() > 0 && filter.blockedOther() == 0
                    ? "这些位置已经是你的仓库，先解除" : "这组机器都被别的工厂占了");
            return 0;
        }
        boolean allConnected = true;
        for (BlockPos targetPos : targets) {
            if (factory.config().nodes().machineAt(BlockKey.of(level.dimension(), targetPos))
                    .isEmpty()) {
                allConnected = false;
                break;
            }
        }
        return allConnected
                ? disconnectMachineTargets(player, factory, level, targets, chain,
                        filter.blockedOther(), filter.blockedRole())
                : connectMachineTargets(player, factory, level, targets, chain,
                        filter.blockedOther(), filter.blockedRole());
    }

    private int connectMachineTargets(ServerPlayer player, Factory factory,
            ServerLevel level, List<BlockPos> targets, boolean chain, int blockedOther,
            int blockedRole) {
        int added = 0;
        int skipped = 0;
        int full = 0;
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        for (BlockPos targetPos : targets) {
            BlockKey block = BlockKey.of(level.dimension(), targetPos);
            if (factory.config().nodes().machineAt(block).isPresent()) {
                skipped++;
                continue;
            }
            Node node = Node.of(null, blockId(level, targetPos), block);
            switch (batch.add(node)) {
                case ADDED -> {
                    refreshObservation(block);
                    added++;
                }
                case DUPLICATE, CONFLICT -> skipped++;
                case FULL -> full++;
                default -> skipped++;
            }
        }
        batch.close();
        if (!chain) {
            String label = FactoryMessages.machineLabel(level, targets.getFirst());
            if (added > 0) {
                FactoryMessages.barOk(player, "已连接机器 " + label);
            } else if (full > 0) {
                FactoryMessages.barErr(player, "节点库已满（上限 "
                        + FactoryLimitsConfig.maxNodes() + " 个节点）");
            } else {
                FactoryMessages.barErr(player, "该机器已被连接 " + label);
            }
            return added;
        }
        if (added == 0) {
            FactoryMessages.barErr(player, full > 0
                    ? "节点库已满（上限 " + FactoryLimitsConfig.maxNodes() + " 个节点），未新增节点"
                    : "没有新增机器（都已连接）");
            return 0;
        }
        String text = "已连接 " + added + " 台";
        if (skipped > 0) {
            text += "，跳过 " + skipped + " 台";
        }
        if (full > 0) {
            text += "，节点库已满跳过 " + full + " 台";
        }
        if (blockedOther > 0) {
            text += "，被别家占用 " + blockedOther + " 台";
        }
        if (blockedRole > 0) {
            text += "，角色冲突 " + blockedRole + " 台";
        }
        FactoryMessages.barOk(player, text);
        return added;
    }

    private int disconnectMachineTargets(ServerPlayer player, Factory factory,
            ServerLevel level, List<BlockPos> targets, boolean chain, int blockedOther,
            int blockedRole) {
        int deleted = 0;
        int skippedRules = 0;
        int skipped = 0;
        int returned = 0;
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        for (BlockPos targetPos : targets) {
            BlockKey block = BlockKey.of(level.dimension(), targetPos);
            Optional<Machine> machineFound = factory.config().nodes().machineAt(block);
            if (machineFound.isEmpty()) {
                skipped++;
                continue;
            }
            Machine machine = machineFound.get();
            Optional<Node> nodeFound = factory.config().nodes().nodeOfMachine(machine.id());
            if (nodeFound.isEmpty()) {
                skipped++;
                continue;
            }
            Node node = nodeFound.get();
            MachineLinkService.DeleteOutcome outcome = this.machineLinks.removeMachine(
                    factory, node, machine, MachineLinkService.DeletePolicy.SKIP_WITH_RULES, player,
                    batch);
            if (!outcome.deleted()) {
                // 连锁不删有规则的机器：规则丢了没法撤销，交给界面逐台确认。
                skippedRules++;
                continue;
            }
            returned += outcome.patterns();
            deleted++;
        }
        batch.close();
        if (!chain) {
            String label = FactoryMessages.machineLabel(level, targets.getFirst());
            if (deleted > 0) {
                FactoryMessages.barOk(player,
                        "已删除链接 " + label + FactoryMessages.patternSuffix(returned));
            } else if (skippedRules > 0) {
                FactoryMessages.barErr(player, "该机器仍有 IO 配置，请在机器库界面删除 " + label);
            } else {
                FactoryMessages.barErr(player, "没有可删除的链接");
            }
            return deleted;
        }
        if (deleted == 0) {
            if (skippedRules > 0) {
                FactoryMessages.barErr(player, "跳过了 " + skippedRules + " 台有 IO 配置的机器，请在机器库界面删除");
            } else {
                FactoryMessages.barErr(player, "没有可删除的链接");
            }
            return 0;
        }
        String text = "已删除 " + deleted + " 台" + FactoryMessages.patternSuffix(returned);
        if (skippedRules > 0) {
            text += "，跳过 " + skippedRules + " 台有 IO 配置的机器（请在机器库界面删除）";
        }
        if (skipped > 0) {
            text += "，跳过 " + skipped + " 台";
        }
        if (blockedOther > 0) {
            text += "，被别家占用 " + blockedOther + " 台";
        }
        if (blockedRole > 0) {
            text += "，角色冲突 " + blockedRole + " 台";
        }
        FactoryMessages.barOk(player, text);
        return deleted;
    }

    /**
     * 取玩家自己的工厂；没有就发聊天栏错误。
     *
     * @param player 玩家
     * @return 工厂；没有时为 {@code null}
     */
    @Nullable
    private Factory owned(ServerPlayer player) {
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }

    /**
     * 读方块 id；读不到时用空气占位，节点仍会建出来但类型判定会失败。
     *
     * @param level 方块所在维度
     * @param pos   方块坐标
     * @return 方块 id
     */
    private static net.minecraft.resources.ResourceLocation blockId(ServerLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
    }

    /**
     * 立刻刷新一个方块的观测（接入之后调，别让界面看到 UNKNOWN）。
     *
     * @param block 方块
     * @return 刷新后的观测；观测服务未就绪时为 {@code null}
     */
    @Nullable
    private Observation refreshObservation(BlockKey block) {
        return this.observations == null ? null : this.observations.refresh(block);
    }
}
