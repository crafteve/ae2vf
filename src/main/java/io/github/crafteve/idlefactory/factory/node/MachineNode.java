package io.github.crafteve.idlefactory.factory.node;

import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.machine.Machine;

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
import net.minecraft.world.level.Level;

/**
 * 一台机器的虚拟节点：节点 owner、样板容器与派单入口。
 *
 * <p>一个逻辑机器对应一个节点，所有节点都连到工厂 ME 锚点节点上；节点不在世界里
 * （{@code setInWorldNode(false)}），所以没有面、不占频道、待机耗电为零。实现
 * {@link PatternContainer} 是为了让 AE2 样板访问终端按机器看到一个容器，实现
 * {@link IActionHost} 是为了派单时能给出「机器自己发起的操作」这个动作源。</p>
 *
 * <p>节点不写自己的 NBT：重启后由 {@link NodeManager} 按机器记录和锚点重建。机器失联时节点
 * 保留，只是不再广告样板。</p>
 */
public final class MachineNode
        implements IGridNodeListener<MachineNode>, PatternContainer, IActionHost {
    /** 同一条节点告警的最短间隔（tick）：避免一台坏机器刷屏。 */
    private static final long WARN_INTERVAL = 100L;

    private final MinecraftServer server;
    private final UUID factoryId;
    private final Machine machine;
    private final IGridNode anchor;
    private final ServerLevel anchorLevel;
    private final Consumer<NodeManager.Notice> notice;
    private final MachineCraftService craft;
    private final MachinePatternInventory patternInventory;
    private final ProviderFaceResolver faceResolver;
    private final ProviderReturnSweep returnSweep;
    private final MachineTerminalGroup terminal;
    private final IManagedGridNode managed;
    private final IActionSource actionSource;

    /** 样板是否已变、等下一次节点 tick 统一刷新解码缓存。 */
    private boolean patternsDirty;
    /** 存档数据是否已变、等下一次节点 tick 统一标脏。 */
    private boolean dataDirty;
    /** 机器方块是否在线（取观测结论，本类不自己探世界）。 */
    private boolean available;
    private long nextWarnTick;

    /**
     * @param server      服务端实例，用来取机器所在维度
     * @param factoryId   所属工厂 id
     * @param machine     机器记录
     * @param anchor      工厂 ME 锚点节点
     * @param anchorLevel 锚点所在维度，虚拟节点只借它做构造参数
     * @param available   机器方块此刻是否在线
     * @param notice      节点告警出口
     */
    MachineNode(MinecraftServer server, UUID factoryId, Machine machine, IGridNode anchor,
            ServerLevel anchorLevel, boolean available, Consumer<NodeManager.Notice> notice) {
        this.server = server;
        this.factoryId = factoryId;
        this.machine = machine;
        this.anchor = anchor;
        this.anchorLevel = anchorLevel;
        this.available = available;
        this.notice = notice;
        // 建节点时机器上可能已经有样板（读档 / 从容器模式切回 ME），第一 tick 统一解码。
        this.patternsDirty = true;

        this.craft = new MachineCraftService(this);
        this.patternInventory = new MachinePatternInventory(this);
        this.faceResolver = new ProviderFaceResolver(this);
        this.returnSweep = new ProviderReturnSweep(this);
        this.terminal = new MachineTerminalGroup(server, machine, available);

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
            this.managed.create(this.anchorLevel, this.machine.ref().pos());
            IGridNode node = this.managed.getNode();
            if (node == null) {
                return false;
            }
            GridHelper.createConnection(node, anchor);
            return true;
        } catch (RuntimeException e) {
            this.managed.destroy();
            this.logThrottled(this.server.overworld().getGameTime(), "建节点失败：" + e);
            return false;
        }
    }

    /**
     * 拆节点，尽量把余量送进机器。
     *
     * <p>对账时锚点换了、机器还在：送不进去的余量留在机器记录里，下次建节点再冲刷。
     * 机器真的被删时走 {@link #detachReturning()}。</p>
     */
    void detach() {
        try {
            ProviderOverflow.flush(this);
        } catch (RuntimeException e) {
            this.logThrottled(this.server.overworld().getGameTime(), "拆节点前冲刷余量失败：" + e);
        }
        this.managed.destroy();
    }

    /**
     * 拆节点并把余量还回去；机器记录马上要删时用。
     *
     * <p>先送回机器，送不进去的插 ME 网络，网络也收不下才掉在机器坐标——数据就要被删了，
     * 不能留在队列里。</p>
     */
    void detachReturning() {
        try {
            ProviderOverflow.drain(this);
        } catch (RuntimeException e) {
            this.logThrottled(this.server.overworld().getGameTime(), "拆节点时退回余量失败：" + e);
        }
        this.managed.destroy();
    }

    /** 停机拆节点：不碰存档数据。 */
    void shutdown() {
        this.managed.destroy();
    }

    /**
     * 每 tick 一次：冲刷余量，再把攒下的样板变化与存档变化收口。
     *
     * @return 是否改动了要落盘的数据
     */
    boolean tick() {
        boolean changed = false;
        if (this.machine.provider().pendingCount() > 0) {
            changed |= ProviderOverflow.flush(this);
        }
        if (this.patternsDirty) {
            this.patternsDirty = false;
            this.craft.rebuild();
            changed = true;
        }
        if (this.dataDirty) {
            this.dataDirty = false;
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
        this.craft.publish();
        if (next) {
            this.returnSweep.activate(this.gameTime());
        }
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
        // 新样板可能带来新产物，别让回收还停在最慢的退避档上。
        this.returnSweep.activate(this.gameTime());
    }

    /**
     * 告诉 CPU 重新读一遍这台机器的样板；节点没接进网络时静默跳过。
     */
    void requestPatternUpdate() {
        if (this.getGrid() != null) {
            ICraftingProvider.requestUpdate(this.managed);
        }
    }

    /** 节点是否已经建出来。 */
    public boolean isAttached() {
        return this.managed.getNode() != null;
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

    /** 机器记录。 */
    Machine machine() {
        return this.machine;
    }

    /** 建节点用的锚点，用来判断锚点是否换过。 */
    IGridNode anchor() {
        return this.anchor;
    }

    /** 机器所在维度；节点本身不依赖它，派单与回收用。 */
    @Nullable
    ServerLevel machineLevel() {
        return this.server.getLevel(this.machine.ref().dimension());
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
        if (level != null && level.isLoaded(this.machine.ref().pos())) {
            return level;
        }
        return this.anchorLevel;
    }

    /** 成功解码的样板数。 */
    public int decodedPatternCount() {
        return this.craft.decodedCount();
    }

    /** 产物白名单大小。 */
    public int productCount() {
        return this.craft.products().size();
    }

    /**
     * 开一次派单 / 回收用的六面快照。
     *
     * @return 快照；机器所在区块没加载时为 {@code null}
     */
    @Nullable
    ProviderFaceResolver.Faces faces() {
        return this.faceResolver.snapshot();
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
        this.notice.accept(new NodeManager.Notice(this.factoryId, this.machine.id(), message));
    }

    /** 产物白名单：所有样板产物的 key 并集。 */
    Set<AEKey> products() {
        return this.craft.products();
    }

    /** 产物回收：节奏与白名单调度都在它里面。 */
    public ProviderReturnSweep returnSweep() {
        return this.returnSweep;
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
        IdleFactory.LOGGER.warn("Idle Factory 机器节点 [{}]：{}",
                this.machine.ref().shortId(), message);
    }

    /**
     * 这次对账描述的是不是本节点。
     *
     * @param spec 对账输入
     * @return 工厂、机器与锚点都没变时为 {@code true}
     */
    boolean matches(NodeManager.Spec spec) {
        return this.factoryId.equals(spec.factoryId())
                && this.machine == spec.machine()
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
    public void onSaveChanges(MachineNode owner, IGridNode node) {
        // 节点自己不带 NBT（重启按机器记录重建），这里没有要落的盘；存档走 dataChanged 汇总。
    }
}
