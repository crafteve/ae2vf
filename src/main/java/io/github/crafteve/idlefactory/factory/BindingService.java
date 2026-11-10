package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.factory.node.NodeProvider;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.MinecraftServer;
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
    private final MinecraftServer server;

    /**
     * @param manager       工厂档案柜
     * @param observations  观测服务；服务端未就绪时为 {@code null}
     * @param nodeLibrary   节点库写入口（整组修改只落盘一次）
     * @param machineLinks  机器接线服务，提供删除出口
     * @param server        服务端
     */
    BindingService(FactoryManager manager, @Nullable ObservationService observations,
            NodeLibraryService nodeLibrary, MachineLinkService machineLinks,
            MinecraftServer server) {
        this.manager = manager;
        this.observations = observations;
        this.nodeLibrary = nodeLibrary;
        this.machineLinks = machineLinks;
        this.server = server;
    }

    /**
     * 一次草稿提交的结果。
     *
     * @param ok       是否成功
     * @param nodeId   落到哪个节点；失败时为 {@code null}
     * @param machines 本次新增机器数
     * @param message  给玩家看的一句话
     */
    public record BindingResult(boolean ok, @Nullable UUID nodeId, int machines, String message) {
        static BindingResult err(String message) {
            return new BindingResult(false, null, 0, message);
        }
    }

    /**
     * 提交一份绑定草稿：全量校验通过后原子写入节点库；失败不改旧节点与索引。
     *
     * <p>顺序固定：LinkGate 快照 → 草稿内查重 → 逐块重查归属/类型/上限 → 内存构造候选
     * Node（已有节点原对象不动）→ 只调一次 `add` / `replace` → `markDirty` 触发索引重建。
     * 编辑已有节点只追加机器，绝不删除原有机器 / 方块。</p>
     *
     * @param factory 工厂
     * @param draft   完整草稿
     * @return 提交结果
     */
    public BindingResult commit(Factory factory, BindingDraft draft) {
        if (draft.isEmpty()) {
            return BindingResult.err("草稿为空");
        }
        LinkGate gate = LinkGate.of(this.manager);
        Set<BlockKey> seen = new HashSet<>();
        List<Machine> machines = new ArrayList<>(draft.machines().size());
        ResourceLocation typeId = null;
        for (BindingDraft.MachineDraft machineDraft : draft.machines()) {
            BlockKey block = machineDraft.block();
            if (!seen.add(block)) {
                return BindingResult.err("草稿里有重复方块");
            }
            if (factory.config().nodes().machineAt(block).isPresent()) {
                return BindingResult.err("方块已属于节点：" + shortId(block));
            }
            LinkGate.ClaimCheck claim = gate.check(block, factory.id(), LinkRole.MACHINE);
            if (claim.blocked()) {
                return BindingResult.err(LinkGateMessages.gateMessage(claim));
            }
            ServerLevel level = this.server.getLevel(block.dimension());
            if (level == null || !level.isLoaded(block.pos())) {
                return BindingResult.err("方块所在区块未加载");
            }
            BlockEntity blockEntity = level.getBlockEntity(block.pos());
            if (blockEntity == null) {
                return BindingResult.err("方块没有方块实体");
            }
            ResourceLocation machineType = blockId(level, block.pos());
            if (typeId == null) {
                typeId = machineType;
            } else if (!typeId.equals(machineType)) {
                return BindingResult.err("节点内机器类型不一致");
            }
            machines.add(new Machine(UUID.randomUUID(), block));
        }

        Node candidate;
        if (draft.nodeId() != null) {
            Optional<Node> baseFound = factory.config().nodes().byId(draft.nodeId());
            if (baseFound.isEmpty()) {
                return BindingResult.err("目标节点不存在");
            }
            Node base = baseFound.get();
            if (!base.typeBlockId().equals(typeId)) {
                return BindingResult.err("机器类型与节点不一致");
            }
            candidate = base;
            for (Machine machine : machines) {
                candidate = candidate.withAddedMachine(machine);
            }
        } else {
            candidate = new Node(UUID.randomUUID(), null, typeId, new NodeIoGroup(),
                    new NodeProvider(), machines, 0L);
        }

        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        NodeLibrary.Result result = draft.nodeId() == null
                ? batch.add(candidate) : batch.replace(candidate);
        if (result != NodeLibrary.Result.ADDED && result != NodeLibrary.Result.REPLACED) {
            return BindingResult.err(switch (result) {
                case DUPLICATE -> "方块或 id 已被占用";
                case CONFLICT -> "方块已属于别的节点";
                case FULL -> "超过节点 / 机器 / 方块上限";
                default -> "提交失败：" + result;
            });
        }
        batch.close();
        for (BlockKey block : seen) {
            this.refreshObservation(block);
        }
        return new BindingResult(true, candidate.id(), machines.size(),
                "已绑定 " + machines.size() + " 台机器");
    }

    /**
     * 方块位置的短描述，回执用。
     *
     * @param block 方块
     * @return 如 {@code minecraft:overworld 1, 2, 3}
     */
    private static String shortId(BlockKey block) {
        return block.dimension().location() + " " + block.pos().toShortString();
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
        if (allConnected) {
            // 普通左右键只对「未接入」或「1 机器节点」生效；堆叠节点的成员只提示编辑。
            for (BlockPos targetPos : targets) {
                Optional<Machine> machine = factory.config().nodes()
                        .machineAt(BlockKey.of(level.dimension(), targetPos));
                if (machine.isEmpty()) {
                    continue;
                }
                Optional<Node> node = factory.config().nodes().nodeOfMachine(machine.get().id());
                if (node.isPresent() && node.get().machineCount() > 1) {
                    FactoryMessages.barErr(player,
                            "堆叠节点成员只支持蹲下右键编辑或终端删除");
                    return 0;
                }
            }
            return disconnectMachineTargets(player, factory, level, targets, chain,
                    filter.blockedOther(), filter.blockedRole());
        }
        return connectMachineTargets(player, factory, level, targets, chain,
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
            FactoryMessages.err(player, "你还没有工厂：打开一次终端会自动创建");
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
