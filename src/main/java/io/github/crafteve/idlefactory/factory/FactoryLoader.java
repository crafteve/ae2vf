package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.claim.BlockOccupancyTable;
import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.io.IoRunner;
import io.github.crafteve.idlefactory.io.PortExecutor;
import io.github.crafteve.idlefactory.network.ObservationNoticeListener;
import io.github.crafteve.idlefactory.observation.ObservationReader;
import io.github.crafteve.idlefactory.observation.ObservationRunner;
import io.github.crafteve.idlefactory.observation.WorldEventListener;
import io.github.crafteve.idlefactory.runtime.LiveNodeApply;
import io.github.crafteve.idlefactory.runtime.LiveNodeRunner;
import io.github.crafteve.idlefactory.storage.MeStorageResolver;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 工厂装配器：服务器启动时读档、建观测通道、建运行器。
 *
 * <p>从 {@link FactoryLifecycle} 拆出来只为让生命周期只管「启停与 tick 顺序」；装配顺序本身
 * 是固定的：读档 → 建观测 → 绑端口 → 建运行器。顺序错了会出现「运行器先跑、观测还没表」，
 * 所以这里是一段直线代码，不做可配置的步骤列表。</p>
 */
public final class FactoryLoader {
    private final MinecraftServer server;
    private final FactoryLibrary library;
    private final BlockOccupancyTable occupancy;
    private final PortExecutor ports;
    private final ObservationNoticeListener notices;
    private final IoFormBuilder formBuilder;
    private final IoRunner nodeIoRunner;
    private final LiveNodeApply nodeApply;
    private final LiveNodeRunner nodeRunner;

    /**
     * @param server     服务端
     * @param library    工厂索引
     * @param occupancy  占用表
     * @param ports      机器侧端口缓存
     * @param notices    失联提示聚合器
     * @param formBuilder 表单生成器
     * @param nodeIoRunner IO 执行入口
     * @param nodeApply  活节点应用器
     * @param nodeRunner 活节点推进器
     */
    public FactoryLoader(MinecraftServer server, FactoryLibrary library,
            BlockOccupancyTable occupancy, PortExecutor ports,
            ObservationNoticeListener notices, IoFormBuilder formBuilder,
            IoRunner nodeIoRunner, LiveNodeApply nodeApply, LiveNodeRunner nodeRunner) {
        this.server = server;
        this.library = library;
        this.occupancy = occupancy;
        this.ports = ports;
        this.notices = notices;
        this.formBuilder = formBuilder;
        this.nodeIoRunner = nodeIoRunner;
        this.nodeApply = nodeApply;
        this.nodeRunner = nodeRunner;
    }

    /**
     * 一次装配的产物：生命周期随后把这些协作者挂到自己的字段上。
     *
     * @param store       存档容器
     * @param runner      每 tick 编排器
     * @param observations 观测调度器
     * @param reader      观测读取器
     * @param storage     仓库解析器
     * @param listener    世界事件标脏监听
     */
    public record Loaded(FactoryStore store, FactoryRunner runner,
                         ObservationRunner observations, ObservationReader reader,
                         MeStorageResolver storage, WorldEventListener listener) {
    }

    /**
     * 读档并装配全部协作者。
     *
     * <p>副作用：创建观测调度器与解析器、重建占用表、刷新每个工厂的仓库缓存。观测调度器
     * 只注册运行器，{@code 失联提示} 由生命周期在装配完成后注册。</p>
     *
     * @return 装配产物
     */
    public Loaded load() {
        FactoryStore store = FactoryStore.get(this.server.overworld());
        ObservationRunner observations = new ObservationRunner(this.server,
                IdleFactoryConfig::observationHealthBudgetPerTick,
                IdleFactoryConfig::observationWorkBudgetPerTick,
                IdleFactoryConfig::observationEventBudgetPerTick);
        ObservationReader reader = observations.reader();
        WorldEventListener listener = new WorldEventListener(observations, observations.table());
        NeoForge.EVENT_BUS.register(listener);
        this.ports.bindFacts(reader);
        observations.register(this.ports);
        MeStorageResolver storage = new MeStorageResolver(this.server);
        this.library.loadFrom(store.entries());
        this.occupancy.rebuild(this.occupancyEntries());
        for (Factory factory : this.library.all()) {
            factory.refreshStorage(storage);
        }
        FactoryRunner runner = new FactoryRunner(this.server, this.library, observations, reader,
                this.nodeIoRunner, this.nodeApply, this.nodeRunner, this.formBuilder, storage);
        observations.register(runner);
        IdleFactory.LOGGER.info("Idle Factory: {} factory instance(s) loaded",
                this.library.all().size());
        return new Loaded(store, runner, observations, reader, storage, listener);
    }

    /**
     * 按当前配置重建占用表。
     *
     * <p>所有改配置的入口最终都调 {@code markDirty}，占用表在这里全量重建，所以它不会和配置
     * 打架：要么一起新，要么一起旧。</p>
     */
    public void rebuildOccupancy() {
        this.occupancy.rebuild(this.occupancyEntries());
    }

    /**
     * 从当前配置拼一份全量占用快照。
     *
     * <p>机器与仓库锚点都算占用：同一位置不能同时是两家工厂的机器或仓库。</p>
     *
     * @return 占用条目
     */
    private List<BlockOccupancyTable.Entry> occupancyEntries() {
        List<BlockOccupancyTable.Entry> entries = new ArrayList<>();
        for (Factory factory : this.library.all()) {
            for (var node : factory.nodes().all()) {
                for (var machine : node.machines()) {
                    entries.add(new BlockOccupancyTable.Entry(factory.id(),
                            BlockOccupancyTable.Role.MACHINE, machine.block()));
                }
            }
            var anchor = factory.storageAnchor();
            if (anchor != null) {
                entries.add(new BlockOccupancyTable.Entry(factory.id(),
                        BlockOccupancyTable.Role.STORAGE, anchor));
            }
        }
        return entries;
    }
}
