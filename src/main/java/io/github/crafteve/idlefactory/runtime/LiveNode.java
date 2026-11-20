package io.github.crafteve.idlefactory.runtime;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.node.NodeCraft;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.io.CapacityPick;
import io.github.crafteve.idlefactory.node.NodeDispatchQueue;
import io.github.crafteve.idlefactory.io.DispatchState;
import io.github.crafteve.idlefactory.io.FacePick;
import io.github.crafteve.idlefactory.io.PortExecutor;
import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternContainer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 一个节点的虚拟节点：节点 owner、样板容器与派单入口。
 *
 * <p>一个逻辑节点对应一个 AE2 节点，所有节点都连到工厂 ME 锚点节点上；节点不在世界里
 * （{@code setInWorldNode(false)}），所以没有面、不占频道、待机耗电为零。实现
 * {@link PatternContainer} 是为了让 AE2 样板访问终端按节点看到一个容器，实现
 * {@link IActionHost} 是为了派单时能给出「节点自己发起的操作」这个动作源。</p>
 *
 * <p>节点不写自己的 NBT：重启后由 {@link LiveNodeLibrary} 按节点记录和锚点重建。节点内的机器
 * 掉线时节点保留，只是不再广告样板。</p>
 */
public final class LiveNode
        implements IGridNodeListener<LiveNode>, PatternContainer, IActionHost {
    /** 同一条节点告警的最短间隔（tick）：避免一个坏节点刷屏。 */
    private static final long WARN_INTERVAL = 100L;

    private final MinecraftServer server;
    private final UUID factoryId;
    private final IGridNode anchor;
    private final ServerLevel anchorLevel;
    private final Consumer<LiveNodeLibrary.Notice> notice;
    private final LiveNodeCraft craft;
    private final LiveNodePatternInventory patternInventory;
    private final LiveNodePatternGroup terminal;
    private final IManagedGridNode managed;
    private final IActionSource actionSource;

    /** 节点记录；结构变化时由 {@link #updateNode(Node)} 换成新实例。 */
    private Node node;
    /** 样板是否已变、等下一次节点 tick 统一刷新解码缓存。 */
    private boolean patternsDirty;
    /** 存档数据是否已变、等下一次节点 tick 统一标脏。 */
    private boolean dataDirty;
    /** 节点内是否至少有一台机器在线（取观测结论，本类不自己探世界）。 */
    private boolean available;
    /** 逐机器在线标记；由运行时按观测结论注入，provider 派单只挑在线机器。 */
    private final Map<UUID, Boolean> machineOnline = new HashMap<>();
    /** 工厂共享的端口缓存；机器侧存储一律从这里取。 */
    private final PortExecutor ports;
    /** 选面：配置面 → 经验面 → 逐面探。 */
    private final FacePick facePick;
    /** 容量查询：主动分配与智能倍率共用。 */
    private final CapacityPick capacityPick;
    private long nextWarnTick;

    /**
     * @param server      服务端实例，用来取机器所在维度
     * @param factoryId   所属工厂 id
     * @param node        节点记录
     * @param anchor      工厂 ME 锚点节点
     * @param anchorLevel 锚点所在维度，虚拟节点只借它做构造参数
     * @param available   节点内是否至少有一台机器在线
     * @param ports       工厂共享的端口缓存
     * @param notice      节点告警出口
     */
    LiveNode(MinecraftServer server, UUID factoryId, Node node, IGridNode anchor,
            ServerLevel anchorLevel, boolean available, PortExecutor ports,
            Consumer<LiveNodeLibrary.Notice> notice) {
        this.server = server;
        this.factoryId = factoryId;
        this.node = node;
        this.anchor = anchor;
        this.anchorLevel = anchorLevel;
        this.available = available;
        this.ports = ports;
        this.facePick = new FacePick(ports);
        this.capacityPick = new CapacityPick(this.facePick, ports);
        this.notice = notice;
        // 建节点时节点上可能已经有样板（读档 / 从容器模式切回 ME），第一 tick 统一解码。
        this.patternsDirty = true;

        this.craft = new LiveNodeCraft(this);
        this.patternInventory = new LiveNodePatternInventory(this);
        this.terminal = new LiveNodePatternGroup(server, node, available);

        this.managed = GridHelper.createManagedNode(this, this)
                .setInWorldNode(false)
                .setIdlePowerUsage(0.0D)
                .setExposedOnSides(Set.of())
                .setVisualRepresentation(this.terminal.icon());
        this.managed.addService(ICraftingProvider.class, this.craft);
        this.actionSource = IActionSource.ofMachine(this);
    }

    /**
     * 建出节点并接到锚点上。
     *
     * @param anchor 工厂 ME 锚点节点
     * @return 是否接上；失败时节点已自行销毁
     */
    boolean attach(IGridNode anchor) {
        try {
            this.managed.create(this.anchorLevel, primaryMachine().block().pos());
            IGridNode node = this.managed.getNode();
            if (node == null) {
                // create 没抛异常却没给出节点：不回滚就会留下一个没人拆的半节点，
                // 调用方拿到 false 就丢引用，锚点网络上再也没人认领它。
                this.discard();
                this.logThrottled(this.server.overworld().getGameTime(), "建节点失败：create 后没有节点");
                return false;
            }
            GridHelper.createConnection(node, anchor);
            return true;
        } catch (RuntimeException e) {
            this.discard();
            this.logThrottled(this.server.overworld().getGameTime(), "建节点失败：" + e);
            return false;
        }
    }

    /**
     * 收拾一个没有接上的节点。
     *
     * <p>清理本身也必须降级：{@code destroy} 再抛异常会盖掉原始失败原因，还会把异常甩给
     * {@link LiveNodeLibrary}，等于 attach 没兜住。</p>
     */
    private void discard() {
        try {
            this.managed.destroy();
        } catch (RuntimeException e) {
            this.logThrottled(this.server.overworld().getGameTime(), "销毁未接上的节点失败：" + e);
        }
    }

    /**
     * 拆节点：队列随节点记录保留，下次建节点继续投递，不在这里动材料。
     */
    void detach() {
        this.managed.destroy();
    }

    /**
     * 拆节点且机器记录马上要删：先把队列尽力退回 ME 网络，退不回的丢弃并记日志。
     */
    void detachReturning() {
        try {
            // 队列属于节点：拆节点时把剩余量全部退回网络，退不回按 D21 记日志丢弃。
            NodeDispatchQueue.RefundReport report = this.node.provider().pending().refundAll(
                    this.networkStorage(), this.gameTime());
            this.logThrottled(this.gameTime(), "拆节点，队列 " + report.orders() + " 单已尽力退回网络");
        } catch (RuntimeException e) {
            this.logThrottled(this.gameTime(), "拆节点时退回订单失败：" + e);
        }
        this.managed.destroy();
    }

    /**
     * 停机拆节点：队列已经随存档保留，这里不碰材料。
     */
    void shutdown() {
        this.managed.destroy();
    }

    /**
     * 每 tick 一次：试投递队首，再把攒下的样板变化与存档变化收口。
     *
     * @return 是否改动了要落盘的数据
     */
    boolean tick() {
        boolean changed = false;
        if (this.patternsDirty) {
            this.craft.rebuild();
            // 先建后清：rebuild 抛异常时标记还留着，下一 tick 自然重试；
            // 先清再建会让 CPU 永远看旧样板。
            this.patternsDirty = false;
            // 重建只换内存里的样板缓存，不改落盘数据，所以不计入 changed。
        }
        if (this.dataDirty) {
            this.dataDirty = false;
            changed = true;
        }
        // 队列由执行层直接改（投递进度、退避），节点每 tick 收口一次脏标记。
        if (this.node.provider().pending().consumeDirty()) {
            changed = true;
        }
        return changed;
    }

    /**
     * 机器方块可用性变化；由观测迁移驱动。
     *
     * @param next 现在是否可用
     */
    void setAvailable(boolean next) {
        if (this.available == next) {
            return;
        }
        this.available = next;
        this.terminal.setAvailable(next);
        // 机器可用性变化会改变智能倍率的容量：重新缩放样板，别让广告出去的一份停在旧倍数。
        this.patternsDirty = true;
        this.craft.publish();
    }

    /**
     * 样板槽被写过。
     *
     * @param capacityChanged 容量是否因这次写入变大
     */
    void onPatternSlotChanged(boolean capacityChanged) {
        this.patternsDirty = true;
        this.dataDirty = true;
        if (capacityChanged) {
            this.terminal.refreshCapacity();
        }
        // 新样板可能带来新产物；回收节奏由执行层按队列增长 / 收获自行激活，这里不越界。
    }

    /** 标记样板需要重新解码（倍率 / 样板槽变化）；下一次节点 tick 统一重建。 */
    void markPatternsDirty() {
        this.patternsDirty = true;
    }

    /**
     * 告诉 CPU 重新读一遍这台机器的样板；节点没接进网络时静默跳过。
     */
    void requestPatternUpdate() {
        if (this.getGrid() != null) {
            ICraftingProvider.requestUpdate(this.managed);
        }
    }

    /** 节点是否在线（有电、已启动、频道要求满足）。 */
    public boolean isNodeOnline() {
        return this.managed.isOnline();
    }

    /** 机器方块是否在线。 */
    public boolean isAvailable() {
        return this.available;
    }

    /** 所属工厂 id。 */
    public UUID factoryId() {
        return this.factoryId;
    }

    /** 节点记录。 */
    Node node() {
        return this.node;
    }

    /**
     * 节点里的代表机器：1:1 时是唯一那台，堆叠时取列表第一台；节点定位与图标都读它。
     *
     * @return 第一台机器
     */
    Machine primaryMachine() {
        return this.node.machines().get(0);
    }

    /**
     * 节点记录变化：名字等非结构变化时同步到运行时；同一个实例直接跳过。
     *
     * <p>结构变化会让 {@link #matches} 返回 false 并触发重建，走不到这里，所以这里不标
     * {@code patternsDirty}，避免每 20 tick 白重建一次样板缓存。</p>
     *
     * @param next 新节点记录
     */
    void updateNode(Node next) {
        if (this.node == next) {
            return;
        }
        this.node = next;
        this.terminal.updateNode(next);
    }

    /** 刷新样板终端分组名。 */
    void refreshName() {
        this.terminal.refreshName();
    }

    /**
     * 按观测结论注入逐机器在线标记。
     *
     * @param online 在线机器 id 集合
     */
    void setOnlineMachines(Set<UUID> online) {
        if (this.machineOnline.size() == online.size()
                && online.stream().allMatch(this.machineOnline::containsKey)) {
            // 集合没变：每轮对账都会调到这里，无脑清缓存会让智能倍率永远重新探测。
            return;
        }
        this.machineOnline.clear();
        for (UUID id : online) {
            this.machineOnline.put(id, Boolean.TRUE);
        }
        // 在线集合变了，智能倍率的「能装几份」跟着变，缓存整体作废；
        // 但已经广告给 CPU 的样板是按旧容量缩放过的，必须重新缩放，否则智能档会一直停在旧倍数。
        this.craft.onOnlineChanged();
        this.patternsDirty = true;
    }

    /**
     * 这台机器此刻是否在线。
     *
     * @param machineId 机器 id
     * @return 在线时为 {@code true}
     */
    boolean isMachineOnline(UUID machineId) {
        return this.machineOnline.containsKey(machineId);
    }

    /**
     * 按节点机器顺序列出在线机器。
     *
     * @return 在线机器列表
     */
    List<Machine> onlineMachines() {
        List<Machine> online = new java.util.ArrayList<>();
        for (Machine machine : this.node.machines()) {
            if (this.machineOnline.containsKey(machine.id())) {
                online.add(machine);
            }
        }
        return online;
    }

    /**
     * 打包这一 tick 主动侧需要的输入。
     *
     * <p>只交值：队列（活引用）、在线机器、派单模式、阻挡开关、样板输入、产物白名单与输入配置面。
     * 执行层不认识节点，节点也不认识执行器。</p>
     *
     * @return 主动侧输入
     */
    public DispatchState dispatchState() {
        Map<AEKey, Direction> inputFaces = new LinkedHashMap<>();
        for (ResourceSpec spec : this.node.io().inputs()) {
            if (spec.face() != null) {
                inputFaces.put(spec.key(), spec.face());
            }
        }
        NodeCraft provider = this.node.provider();
        Set<UUID> members = new java.util.HashSet<>(this.node.machines().size());
        for (Machine machine : this.node.machines()) {
            members.add(machine.id());
        }
        return new DispatchState(this.node.id(), provider.pending(), this.onlineMachines(),
                provider.dispatch(), provider.blocking(), this.craft.patternInputs(),
                this.craft.products(), inputFaces, members);
    }

    /**
     * 按机器 id 取成员。
     *
     * @param machineId 机器 id
     * @return 机器；不属于本节点时为 {@code null}
     */
    @Nullable
    Machine machine(UUID machineId) {
        return this.node.machineOf(machineId).orElse(null);
    }

    /**
     * 取某个方块所在的服务端世界。
     *
     * @param block 方块
     * @return 世界；维度不存在时为 {@code null}
     */
    @Nullable
    ServerLevel levelOf(BlockKey block) {
        return this.server.getLevel(block.dimension());
    }

    /** 建节点用的锚点，用来判断锚点是否换过。 */
    IGridNode anchor() {
        return this.anchor;
    }

    /** 节点类型方块所在维度；派单与回收用。 */
    @Nullable
    ServerLevel machineLevel() {
        return this.server.getLevel(primaryMachine().block().dimension());
    }

    /** 当前游戏时间，告警与回收节奏用。 */
    long gameTime() {
        return this.server.overworld().getGameTime();
    }

    /**
     * 解码样板用的世界：优先机器所在世界，没加载时退回锚点世界。
     *
     * <p>处理样板的解码不读世界内容，这里只是满足 AE2 接口；用锚点世界兜底可以避免机器
     * 未加载时解码直接失败。</p>
     *
     * @return 可用的世界
     */
    Level decodeLevel() {
        ServerLevel level = this.machineLevel();
        if (level != null && level.isLoaded(primaryMachine().block().pos())) {
            return level;
        }
        return this.anchorLevel;
    }

    /** 待投递队列（只读用途：界面与快照读单数）；写入口在 {@link NodeDispatchQueue}。 */
    public NodeDispatchQueue pendingQueue() {
        return this.node.provider().pending();
    }

    /** 工厂共享的端口缓存：机器侧存储一律从这里取。 */
    PortExecutor ports() {
        return this.ports;
    }

    /** 选面。 */
    FacePick facePick() {
        return this.facePick;
    }

    /** 容量查询。 */
    CapacityPick capacityPick() {
        return this.capacityPick;
    }

    /**
     * 智能倍率的诊断文本；只读，不写缓存。
     *
     * @return 多行文本
     */
    public String capacityReport() {
        return this.craft.capacityReport();
    }

    /**
     * 锚点网络的库存。
     *
     * @return ME 库存；节点还没接进网络时为 {@code null}
     */
    @Nullable
    MEStorage networkStorage() {
        IGrid grid = this.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    /** 标记存档数据已变；下一次节点 tick 统一落盘。 */
    void markDataChanged() {
        this.dataDirty = true;
    }

    /**
     * 发一条玩家可见的节点事件。
     *
     * @param message 短句
     */
    void report(String message) {
        this.notice.accept(new LiveNodeLibrary.Notice(this.factoryId, this.node.id(), message));
    }

    /** 产物白名单：所有样板产物的 key 并集。 */
    Set<AEKey> products() {
        return this.craft.products();
    }


    /** 派单时用的动作源，标记成机器自己发起。 */
    IActionSource actionSource() {
        return this.actionSource;
    }

    /** 按最短间隔打一条节点告警。 */
    void logThrottled(long now, String message) {
        if (now < this.nextWarnTick) {
            return;
        }
        this.nextWarnTick = now + WARN_INTERVAL;
        IdleFactory.LOGGER.warn("Idle Factory 节点 [{}]：{}",
                this.node.id().toString().substring(0, 8), message);
    }

    /**
     * 这次对账描述的是不是本节点。
     *
     * @param spec 对账输入
     * @return 工厂、节点 id、锚点与结构版本都没变时为 {@code true}
     */
    boolean matches(LiveNodeLibrary.Spec spec) {
        return this.factoryId.equals(spec.factoryId())
                && this.node.id().equals(spec.node().id())
                && this.node.topologyRevision() == spec.node().topologyRevision()
                && this.anchor == spec.anchor();
    }

    @Override
    @Nullable
    public IGrid getGrid() {
        IGridNode node = this.managed.getNode();
        if (node == null) {
            return null;
        }
        try {
            return node.getGrid();
        } catch (RuntimeException e) {
            // 节点刚被销毁 / 集群正在拆分。
            return null;
        }
    }

    @Override
    public InternalInventory getTerminalPatternInventory() {
        return this.patternInventory;
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        return this.terminal.group();
    }

    @Override
    public long getTerminalSortOrder() {
        return this.terminal.sortOrder();
    }

    @Override
    @Nullable
    public IGridNode getActionableNode() {
        return this.managed.getNode();
    }

    @Override
    public void onSaveChanges(LiveNode owner, IGridNode node) {
        // 节点自己不带 NBT（重启按机器记录重建），这里没有要落的盘；存档走 dataChanged 汇总。
    }
}
