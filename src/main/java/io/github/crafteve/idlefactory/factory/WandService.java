package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.node.Node;
import io.github.crafteve.idlefactory.factory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.factory.node.NodeLibrary;
import io.github.crafteve.idlefactory.factory.node.NodeProvider;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;
import io.github.crafteve.idlefactory.factory.wand.WandDeleteReason;
import io.github.crafteve.idlefactory.factory.wand.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.factory.wand.WandLimits;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 机器绑定杖的服务端编排：1:1 toggle、连锁、预校验与草稿提交。
 *
 * <p>它是网络负载与节点库之间唯一的一层业务：负载只把线协议 record 换成草稿，这里做判定与
 * 写库，回执再交回负载。所有方法都跑在服务端主线程，不建线程、不建锁。</p>
 *
 * <p>写库统一走 {@link MachineLinkService#applyTopologyChange}，所以「先全量校验、再一次写
 * 索引」只有一套：校验不通过时它返回 {@code deleted=false}，节点库与虚拟节点都不动。新增
 * 机器的观测刷新留在这里——终端没有新增机器的入口。</p>
 */
final class WandService {
    private final FactoryManager manager;
    @Nullable
    private final ObservationService observations;
    private final NodeLibraryService nodeLibrary;
    private final MachineLinkService machineLinks;
    private final WandValidator validator;
    private final MinecraftServer server;

    /**
     * @param manager      工厂档案柜
     * @param observations 观测服务；服务端未就绪时为 {@code null}
     * @param nodeLibrary  节点库写入口
     * @param machineLinks 节点拓扑变更出口
     * @param server       服务端
     */
    WandService(FactoryManager manager, @Nullable ObservationService observations,
            NodeLibraryService nodeLibrary, MachineLinkService machineLinks,
            MinecraftServer server) {
        this.manager = manager;
        this.observations = observations;
        this.nodeLibrary = nodeLibrary;
        this.machineLinks = machineLinks;
        this.validator = new WandValidator(manager, server);
        this.server = server;
    }

    /**
     * 空闲态右键方块：已绑定就断开，未绑定就建一个 1 台机器的节点。
     *
     * @param player 发起请求的玩家
     * @param pos    目标方块
     * @return 回执
     */
    WandCommitOutcome toggle(ServerPlayer player, BlockPos pos) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return WandCommitOutcome.failure(WandFailure.NOT_HOLDING_WAND);
        }
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            return WandCommitOutcome.failure(WandFailure.NO_FACTORY);
        }
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) {
            return WandCommitOutcome.failure(WandFailure.BLOCK_UNLOADED);
        }
        BlockKey block = BlockKey.of(level.dimension(), pos);
        Optional<Machine> bound = factory.config().nodes().machineAt(block);
        if (bound.isPresent()) {
            return disconnect(factory, bound.get(), block, player);
        }
        return connect(factory, level, block, player);
    }

    /**
     * 连锁查找：只返回过滤后的机器方块，不写库、不推快照。
     *
     * @param player 发起请求的玩家
     * @param origin 连锁起点
     * @return 机器列表与失败原因
     */
    FactoryService.ChainResult chain(ServerPlayer player, BlockKey origin) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return new FactoryService.ChainResult(List.of(), WandFailure.NOT_HOLDING_WAND);
        }
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            return new FactoryService.ChainResult(List.of(), WandFailure.NO_FACTORY);
        }
        ServerLevel level = this.server.getLevel(origin.dimension());
        if (level == null || !level.isLoaded(origin.pos())) {
            return new FactoryService.ChainResult(List.of(), WandFailure.BLOCK_UNLOADED);
        }
        if (!FactoryLinkFinder.isMachine(level, origin.pos())) {
            return new FactoryService.ChainResult(List.of(), WandFailure.NOT_A_MACHINE);
        }
        List<BlockPos> found = FactoryLinkFinder.machines(level, origin.pos());
        if (found.isEmpty()) {
            return new FactoryService.ChainResult(List.of(), WandFailure.NONE);
        }
        // BFS 的半径与数量上限按筛选前计算；这里只按占用表分流，过滤后不补位、不重扫。
        LinkGate.Filter filter = LinkGate.of(this.manager).filter(factory.id(), LinkRole.MACHINE,
                level.dimension(), found);
        List<BlockKey> blocks = new ArrayList<>(filter.open().size());
        for (BlockPos pos : filter.open()) {
            blocks.add(BlockKey.of(level.dimension(), pos));
        }
        return new FactoryService.ChainResult(blocks, WandFailure.NONE);
    }

    /**
     * 打开面板前的只读预校验。
     *
     * <p>逐块问题不算「不打开」：面板要照样打开并标红、灰显确定。只有草稿级问题（未持杖、
     * 无工厂、草稿为空……）才回非 {@code NONE} 的失败，客户端只出 HUD。</p>
     *
     * @param player 发起请求的玩家
     * @param draft  当前草稿
     * @return 问题与是否打开面板
     */
    FactoryService.OpenResult open(ServerPlayer player, WandDraft draft) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return new FactoryService.OpenResult(List.of(), 0, WandFailure.NOT_HOLDING_WAND);
        }
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            return new FactoryService.OpenResult(List.of(), 0, WandFailure.NO_FACTORY);
        }
        List<WandIssue> issues = this.validator.validate(factory, player, draft);
        WandFailure failure = WandFailure.NONE;
        for (WandIssue issue : issues) {
            if (issue.block() == null) {
                failure = issue.failure();
                break;
            }
        }
        return new FactoryService.OpenResult(issues, issues.size(), failure);
    }

    /**
     * 提交整份草稿：全量重验后原子写库。
     *
     * @param player          发起请求的玩家
     * @param draft           完整草稿
     * @param lastAcceptedSeq 服务端已经接受过的最大提交序号
     * @return 回执
     */
    WandCommitOutcome commit(ServerPlayer player, WandDraft draft, long lastAcceptedSeq) {
        if (!(player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return WandCommitOutcome.failure(WandFailure.NOT_HOLDING_WAND);
        }
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            return WandCommitOutcome.failure(WandFailure.NO_FACTORY);
        }
        List<WandIssue> issues = this.validator.validateForCommit(factory, player, draft,
                lastAcceptedSeq);
        if (!issues.isEmpty()) {
            return outcomeFromIssues(issues);
        }
        Node base = draft.nodeId() == null ? null
                : factory.config().nodes().byId(draft.nodeId()).orElse(null);
        int baseMachines = base == null ? 0 : base.machineCount();
        int finalMachines = baseMachines - draft.removed().size() + draft.added().size();
        Node candidate = finalMachines == 0 ? null : buildCandidate(base, draft);
        if (finalMachines > 0 && candidate == null) {
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        MachineLinkService.TopologyOutcome outcome = this.machineLinks.applyTopologyChange(factory,
                base, candidate, draft.removed(), player, batch);
        batch.close();
        if (!outcome.deleted()) {
            IdleFactory.LOGGER.warn("Idle Factory: 绑定杖提交写库结果与预期不符：result={}, node={},"
                    + " added={}, removed={}", outcome.result(), draft.nodeId(),
                    draft.added().size(), draft.removed().size());
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        for (BlockKey block : draft.added()) {
            refresh(block);
        }
        UUID nodeId = candidate != null ? candidate.id() : base.id();
        return WandCommitOutcome.success(nodeId, draft.added().size(), draft.removed().size());
    }

    /**
     * 按草稿构造候选节点：新建用随机 id，改节点先删后加。
     *
     * @param base  原节点；新建时为 {@code null}
     * @param draft 草稿
     * @return 候选节点；方块维度读不到时为 {@code null}
     */
    @Nullable
    private Node buildCandidate(@Nullable Node base, WandDraft draft) {
        ResourceLocation typeId = base == null ? null : base.typeBlockId();
        List<Machine> machines = new ArrayList<>(draft.added().size());
        for (BlockKey block : draft.added()) {
            if (typeId == null) {
                ServerLevel level = this.server.getLevel(block.dimension());
                if (level == null) {
                    return null;
                }
                typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.pos()).getBlock());
            }
            machines.add(Machine.of(block.dimension(), block.pos()));
        }
        if (base == null) {
            return new Node(UUID.randomUUID(), null, typeId, new NodeIoGroup(), new NodeProvider(),
                    machines, 0L);
        }
        Node candidate = base;
        for (UUID machineId : draft.removed()) {
            candidate = candidate.withRemovedMachine(machineId);
        }
        for (Machine machine : machines) {
            candidate = candidate.withAddedMachine(machine);
        }
        return candidate;
    }

    /**
     * 新建节点：先过占用门禁，再走统一拓扑出口。
     *
     * @param factory 工厂
     * @param level   方块所在世界
     * @param block   方块
     * @param player  发起请求的玩家
     * @return 回执
     */
    private WandCommitOutcome connect(Factory factory, ServerLevel level, BlockKey block,
            ServerPlayer player) {
        if (level.getBlockEntity(block.pos()) == null) {
            return WandCommitOutcome.failure(WandFailure.NOT_A_MACHINE);
        }
        if (!FactoryLinkFinder.isMachine(level, block.pos())) {
            return WandCommitOutcome.failure(WandFailure.NOT_A_MACHINE);
        }
        if (LinkGate.of(this.manager).check(block, factory.id(), LinkRole.MACHINE).kind()
                != LinkGate.ClaimCheck.Kind.FREE) {
            return WandCommitOutcome.failure(WandFailure.BLOCK_CLAIMED);
        }
        ResourceLocation typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(block.pos()).getBlock());
        Machine machine = Machine.of(block.dimension(), block.pos());
        Node candidate = new Node(UUID.randomUUID(), null, typeId, new NodeIoGroup(),
                new NodeProvider(), List.of(machine), 0L);
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        MachineLinkService.TopologyOutcome outcome = this.machineLinks.applyTopologyChange(factory,
                null, candidate, Set.of(), player, batch);
        batch.close();
        if (!outcome.deleted()) {
            return mapAddFailure(outcome.result(), block);
        }
        refresh(block);
        return WandCommitOutcome.success(candidate.id(), 1, 0);
    }

    /**
     * 1:1 断开：只对 1 台机器的节点生效，有规则或样板都拒绝。
     *
     * @param factory 工厂
     * @param machine 已绑定的机器
     * @param block   机器方块
     * @param player  发起请求的玩家
     * @return 回执
     */
    private WandCommitOutcome disconnect(Factory factory, Machine machine, BlockKey block,
            ServerPlayer player) {
        Optional<Node> found = factory.config().nodes().nodeOfMachine(machine.id());
        if (found.isEmpty()) {
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        Node node = found.get();
        if (node.machineCount() > 1) {
            return WandCommitOutcome.failure(WandFailure.MEMBER_NOT_REMOVABLE);
        }
        if (node.ruleCount() > 0) {
            return WandCommitOutcome.failure(WandFailure.DELETE_BLOCKED,
                    WandDeleteReason.HAS_RULES, List.of(block), 1);
        }
        if (node.patternCount() > 0) {
            return WandCommitOutcome.failure(WandFailure.DELETE_BLOCKED,
                    WandDeleteReason.HAS_PATTERNS, List.of(block), 1);
        }
        NodeLibraryService.Batch batch = this.nodeLibrary.begin(factory);
        MachineLinkService.TopologyOutcome outcome = this.machineLinks.applyTopologyChange(factory,
                node, null, Set.of(machine.id()), player, batch);
        batch.close();
        if (!outcome.deleted()) {
            return WandCommitOutcome.failure(WandFailure.INVALID_REQUEST);
        }
        return WandCommitOutcome.success(node.id(), 0, 1);
    }

    /**
     * 把节点库结果映射成绑定杖失败原因。
     *
     * @param result 节点库结果
     * @param block  目标方块
     * @return 失败回执
     */
    private static WandCommitOutcome mapAddFailure(NodeLibrary.Result result, BlockKey block) {
        WandFailure failure = switch (result) {
            case FULL -> WandFailure.LIMIT_NODES;
            case DUPLICATE, CONFLICT -> WandFailure.BLOCK_CLAIMED;
            case ADDED, REPLACED, REMOVED, NOT_FOUND -> WandFailure.INVALID_REQUEST;
        };
        return WandCommitOutcome.failure(failure, WandDeleteReason.NONE, List.of(block), 1);
    }

    /**
     * 把校验问题折成回执：失败原因取第一条，rejected 只带前
     * {@link WandLimits#MAX_ISSUES_WIRE} 个方块。
     *
     * @param issues 校验问题
     * @return 失败回执
     */
    private static WandCommitOutcome outcomeFromIssues(List<WandIssue> issues) {
        WandIssue first = issues.get(0);
        List<BlockKey> rejected = new ArrayList<>();
        int rejectedCount = 0;
        for (WandIssue issue : issues) {
            if (issue.block() == null) {
                continue;
            }
            rejectedCount++;
            if (rejected.size() < WandLimits.MAX_ISSUES_WIRE) {
                rejected.add(issue.block());
            }
        }
        return WandCommitOutcome.failure(first.failure(), first.reason(), rejected, rejectedCount);
    }

    /**
     * 立刻刷新一个方块的观测，别让界面看到 UNKNOWN。
     *
     * @param block 方块
     */
    private void refresh(BlockKey block) {
        if (this.observations != null) {
            this.observations.refresh(block);
        }
    }
}
