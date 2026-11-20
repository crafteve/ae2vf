package io.github.crafteve.ae2vf.io;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.key.BlockKey;
import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.observation.ObservationChange;
import io.github.crafteve.ae2vf.observation.ObservationChangeListener;
import io.github.crafteve.ae2vf.observation.BlockFacts;

import appeng.api.AECapabilities;
import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 执行侧的只读端口缓存：{@code (机器, 面) → MEStorage}，跨 tick 保留。
 *
 * <p>它不做任何世界检查，取用时直接拿缓存；失效只有两个入口：动作失败时由 Runner 调
 * {@link #invalidate(Machine, Direction)}（{@code NO_FACE} 时 {@link #invalidateMachine}），
 * 机器 / 节点删除时 {@link #drop(UUID)}。下一次取用发现条目没了就重建。</p>
 *
 * <p>机器侧统一走 AE2 的 {@link MEStorage}：AE2 已经把物品、流体、FE 与化学品抹平成同一套
 * {@code insert / extract / getAvailableStacks}，执行层不再需要自己维护三套适配器。</p>
 *
 * <p>调用者只有 {@code FacePick} / {@code CapacityPick} / {@code IoExecutor}。Runner 只持有
 * 并转交，不直接调用。</p>
 */
public final class PortExecutor implements ObservationChangeListener {
    /** 端口缓存条数栅栏：正常玩法摸不到，只防病态规模把内存撑爆。 */
    public static final int PORT_CACHE_FENCE = 16384;
    /** 外部存储包装用的空回调。 */
    private static final Runnable NOOP = () -> {
    };
    /** 固定顺序的六面，避免 {@code Direction.values()} 每次复制数组。 */
    private static final Direction[] FACES = Direction.values();

    private final MinecraftServer server;
    /** 方块观测事实；根装配在启动后注入，未就绪时不做观测保险。 */
    @Nullable
    private BlockFacts.Source facts;
    /** 建缓存时看到的方块实体类型，用于发现「方块被换掉」。 */
    private final Map<UUID, String> beTypes = new LinkedHashMap<>();
    /** 方块 → 机器 id：观测变化按方块失效缓存时要反查。 */
    private final Map<BlockKey, UUID> machineByBlock = new LinkedHashMap<>();
    /**
     * 端口缓存：机器 id → 六面存储（下标即 {@code get3DDataValue()}）。
     *
     * <p>按访问顺序的 LinkedHashMap 兼做 LRU：超过 {@link #PORT_CACHE_FENCE} 台机器时
     * 淘汰最久没被取用的那台。</p>
     */
    private final Map<UUID, MEStorage[]> ports = new LinkedHashMap<>(256, 0.75F, true);

    /**
     * @param server 服务端实例，用来按机器的维度取世界
     */
    public PortExecutor(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 注入观测事实来源；根装配在观测就绪后调一次。
     *
     * @param facts 方块观测事实
     */
    public void bindFacts(BlockFacts.Source facts) {
        this.facts = facts;
    }

    /**
     * 取一个面的存储；没有缓存就当场建一次。
     *
     * @param machine 机器
     * @param face    面
     * @return 存储；方块未加载或这个面没有存储能力时为 {@code null}
     */
    @Nullable
    public MEStorage storage(Machine machine, Direction face) {
        return this.storage(machine, face, false);
    }

    /**
     * 取一个面的存储。
     *
     * @param machine 机器
     * @param face    面
     * @param rebuild 是否强制重建这一面（探面时用）
     * @return 存储；方块未加载或这个面没有存储能力时为 {@code null}
     */
    @Nullable
    public MEStorage storage(Machine machine, Direction face, boolean rebuild) {
        if (!this.valid(machine)) {
            // 方块不见了 / 没加载 / 类型换了：缓存整台作废，交给调用方按「没有端口」处理。
            this.invalidateMachine(machine);
            return null;
        }
        int index = face.get3DDataValue();
        if (rebuild) {
            // 探面时强制重来：先把这一面踢掉，下面按「没有缓存」重建一次。
            this.invalidate(machine, face);
        } else {
            MEStorage[] cached = this.ports.get(machine.id());
            if (cached != null && cached[index] != null) {
                return cached[index];
            }
        }
        MEStorage built = this.build(machine, face);
        if (built == null) {
            return null;
        }
        this.store(machine.id(), index, built);
        return built;
    }

    /**
     * 只失效一个面（动作报 {@code REJECTED} / {@code ERROR} 时）。
     *
     * @param machine 机器
     * @param face    面
     */
    public void invalidate(Machine machine, Direction face) {
        MEStorage[] cached = this.ports.get(machine.id());
        if (cached != null) {
            cached[face.get3DDataValue()] = null;
        }
    }

    /**
     * 失效整台机器的六个面（连面都没选出来时）。
     *
     * @param machine 机器
     */
    public void invalidateMachine(Machine machine) {
        this.ports.remove(machine.id());
        this.beTypes.remove(machine.id());
        this.machineByBlock.remove(machine.block());
    }

    /**
     * 丢掉一台机器的全部端口（机器 / 节点删除时）。
     *
     * @param machineId 机器 id
     */
    public void drop(UUID machineId) {
        this.ports.remove(machineId);
        this.beTypes.remove(machineId);
        this.machineByBlock.values().removeIf(id -> id.equals(machineId));
    }

    /**
     * 这台机器的方块此刻是否加载。
     *
     * @param machine 机器
     * @return 维度存在且区块已加载时为 {@code true}
     */
    public boolean loaded(Machine machine) {
        ServerLevel level = this.server.getLevel(machine.block().dimension());
        return level != null && level.isLoaded(machine.block().pos());
    }

    /**
     * 按配置机器集合保留：只有离开配置的机器才丢缓存。
     *
     * @param keep 当前配置引用的机器 id（含 UNLOADED）
     */
    public void retainMachines(Set<UUID> keep) {
        this.ports.keySet().removeIf(id -> !keep.contains(id));
        this.beTypes.keySet().removeIf(id -> !keep.contains(id));
        this.machineByBlock.values().removeIf(id -> !keep.contains(id));
    }

    /** 清空全部端口（服务器停止时）。 */
    public void clear() {
        this.ports.clear();
        this.beTypes.clear();
        this.machineByBlock.clear();
    }

    /**
     * 观测变化：方块离开 PRESENT 时立刻丢掉它的端口缓存，不等下次取用。
     *
     * @param change 一批方块变化
     */
    @Override
    public void onObservationChanged(ObservationChange change) {
        for (ObservationChange.BlockChange blockChange : change.changes()) {
            if (blockChange.to() == io.github.crafteve.ae2vf.observation.Health.PRESENT) {
                continue;
            }
            UUID machineId = this.machineByBlock.remove(blockChange.key());
            if (machineId != null) {
                this.ports.remove(machineId);
                this.beTypes.remove(machineId);
            }
        }
    }

    /**
     * 取用前用观测事实校验：方块必须在、已探测、类型没被换掉。
     *
     * @param machine 机器
     * @return 缓存仍然可用时为 {@code true}
     */
    private boolean valid(Machine machine) {
        if (this.facts == null) {
            return true;
        }
        BlockFacts observation = this.facts.at(machine.block());
        if (!observation.probed() || !observation.present() || observation.blockId() == null) {
            return false;
        }
        String cached = this.beTypes.get(machine.id());
        return cached == null || observation.beTypeId() == null
                || cached.equals(observation.beTypeId().toString());
    }

    private void store(UUID machineId, int faceIndex, MEStorage storage) {
        MEStorage[] cached = this.ports.computeIfAbsent(machineId, id -> new MEStorage[FACES.length]);
        cached[faceIndex] = storage;
        this.evictIfNeeded();
    }

    private void evictIfNeeded() {
        while (this.ports.size() > PORT_CACHE_FENCE) {
            Iterator<Map.Entry<UUID, MEStorage[]>> iterator = this.ports.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
            // 只记一次线索，不逐条刷屏：出现这种量级说明配置或机器规模已经病态。
            IdleFactory.LOGGER.debug("Idle Factory: 端口缓存超过 {} 台，已淘汰最久未用的一台",
                    PORT_CACHE_FENCE);
        }
    }

    /**
     * 按面建一次存储：先查 AE2 原生 ME 存储，再用 AE2 的外部存储策略包装 NeoForge 能力。
     *
     * @param machine 机器
     * @param face    面
     * @return 存储；方块未加载或这个面没有能力时为 {@code null}
     */
    @Nullable
    private MEStorage build(Machine machine, Direction face) {
        ServerLevel level = this.server.getLevel(machine.block().dimension());
        if (level == null) {
            return null;
        }
        BlockPos pos = machine.block().pos();
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            // 必须存注册名：BlockEntityType 没有重写 toString，直接 toString 会得到
            // "BlockEntityType@1a2b"，和观测里的 beTypeId 永远不相等，于是每次取端口都被判成
            // 「方块被换掉」，缓存反复失效，容量探测与智能倍率就会拿到 null / 0。
            var typeId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(be.getType());
            if (typeId != null) {
                this.beTypes.put(machine.id(), typeId.toString());
            }
            this.machineByBlock.put(machine.block(), machine.id());
        }
        MEStorage nativeStorage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, pos, be.getBlockState(), be, face)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, face);
        if (nativeStorage != null) {
            return nativeStorage;
        }
        Map<AEKeyType, ExternalStorageStrategy> strategies =
                StackWorldBehaviors.createExternalStorageStrategies(level, pos, face);
        IdentityHashMap<AEKeyType, MEStorage> wrappers = new IdentityHashMap<>(2);
        for (Map.Entry<AEKeyType, ExternalStorageStrategy> entry : strategies.entrySet()) {
            MEStorage wrapper = entry.getValue().createWrapper(false, NOOP);
            if (wrapper != null) {
                wrappers.put(entry.getKey(), wrapper);
            }
        }
        return wrappers.isEmpty() ? null : new LiveStorage(new CompositeStorage(wrappers));
    }

    /**
     * 外部存储的读穿包装：库存快照每次现扫，不做跨 tick 缓存。
     *
     * <p>AE2 的 {@link CompositeStorage} 自带一份库存缓存，只在 {@code onTick()} 或内部
     * 写入成功时刷新；它本来是给存储总线那种「注册进 AE2 调度、每 tick 被 tick」的用法准备的。
     * 本类把实例跨 tick 缓存下来后，没有任何人 tick 它，{@code getAvailableStacks} 会永远返回
     * 第一次访问时的快照——机器后来产出的东西看不见，回收与模糊输出就会一直判定「没料」。
     * 这里在读库存前强制刷新一次，写路径（{@code insert} / {@code extract}）本来就是直达
     * 方块能力、不经过缓存，原样转调。</p>
     */
    private static final class LiveStorage implements MEStorage {
        private final CompositeStorage delegate;

        LiveStorage(CompositeStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            return this.delegate.insert(what, amount, mode, source);
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            return this.delegate.extract(what, amount, mode, source);
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            // onTick() 会清掉「需要重建」标记并重扫所有子存储；随后 getAvailableStacks 拷出的就是新快照。
            this.delegate.onTick();
            this.delegate.getAvailableStacks(out);
        }

        @Override
        public Component getDescription() {
            return this.delegate.getDescription();
        }
    }
}
