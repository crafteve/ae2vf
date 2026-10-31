package io.github.crafteve.idlefactory.factory.capability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.location.BlockKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;

/**
 * 每个维度一份的能力句柄缓存：{@code (pos, 种类, 面) → handler}。
 *
 * <p>这就是「路由表」的原料层。命中一次 hash 查表，不再走 {@code getCapability}——后者对某些模组
 * 意味着一堆包装器分配和面配置判断。</p>
 *
 * <p><b>失效靠 NeoForge 的通知，不靠轮询。</b>NeoForge 会在区块加载/卸载、方块实体加载/卸载、
 * 方块实体放置/销毁时失效能力监听器；模组改配置时应当自己调
 * {@code level.invalidateCapabilities(pos)}。一个位置只注册一个监听器，收到通知就清掉该位置的
 * 全部条目，并回调 {@code onInvalidate} 让观测层下一 tick 重探。</p>
 *
 * <p><b>必须强引用监听器</b>：NeoForge 的 {@code CapabilityListenerHolder} 存的是
 * {@link java.lang.ref.WeakReference}，不自己拿住就会被 GC 掉、永远收不到失效通知。
 * 参考 SFM 的 {@code SFMBlockCapabilityResult#addInvalidationListener}。</p>
 *
 * <p>负结果也缓存（{@code handler == null}）：一个「没有能力的面」在 CPU 里占一条记录，但省掉了
 * 每 tick 一次 {@code getCapability}。监听器触发时整片清掉，所以不会陈旧。</p>
 */
public final class LevelCapabilityCache {
    /** 缓存键：位置 + 能力种类 + 面（{@code null} 表示无向）。 */
    private record Key(BlockPos pos, CapabilityKind kind, @Nullable Direction face) {
    }

    private final ServerLevel level;
    private final Consumer<BlockKey> onInvalidate;
    /** 键 → handler；值为 {@code null} 表示「查过了，没有」。 */
    private final Map<Key, Object> handlers = new HashMap<>();
    /** 位置 → 监听器强引用（NeoForge 只持弱引用）。 */
    private final Map<BlockPos, ICapabilityInvalidationListener> listeners = new HashMap<>();
    /** 已经注册过监听器的位置。 */
    private final Set<BlockPos> tracked = new HashSet<>();

    /**
     * @param level        所在维度
     * @param onInvalidate 某个位置的能力可能变了时回调（只做标脏，不许查世界）
     */
    public LevelCapabilityCache(ServerLevel level, Consumer<BlockKey> onInvalidate) {
        this.level = level;
        this.onInvalidate = onInvalidate;
    }

    /** 所在维度。 */
    public ServerLevel level() {
        return this.level;
    }

    /**
     * 物品能力。
     *
     * @param pos  方块坐标
     * @param face 面；{@code null} 表示无向
     * @return 处理器；没有时为 {@code null}
     */
    @Nullable
    public IItemHandler item(BlockPos pos, @Nullable Direction face) {
        return (IItemHandler) get(pos, CapabilityKind.ITEM, face);
    }

    /**
     * 流体能力。
     *
     * @param pos  方块坐标
     * @param face 面；{@code null} 表示无向
     * @return 处理器；没有时为 {@code null}
     */
    @Nullable
    public IFluidHandler fluid(BlockPos pos, @Nullable Direction face) {
        return (IFluidHandler) get(pos, CapabilityKind.FLUID, face);
    }

    /**
     * 能量能力。
     *
     * @param pos  方块坐标
     * @param face 面；{@code null} 表示无向
     * @return 处理器；没有时为 {@code null}
     */
    @Nullable
    public IEnergyStorage energy(BlockPos pos, @Nullable Direction face) {
        return (IEnergyStorage) get(pos, CapabilityKind.ENERGY, face);
    }

    /**
     * 丢掉一个位置的全部缓存（不再被任何工厂引用时调）。
     *
     * <p>只清理，不回调——观测层正在做 mark-sweep。</p>
     *
     * @param pos 方块坐标
     */
    public void forget(BlockPos pos) {
        this.tracked.remove(pos);
        this.listeners.remove(pos);
        this.handlers.keySet().removeIf(key -> key.pos().equals(pos));
    }

    /**
     * 当前缓存条目数（诊断用）。
     *
     * @return 条目数
     */
    public int size() {
        return this.handlers.size();
    }

    @Nullable
    private Object get(BlockPos pos, CapabilityKind kind, @Nullable Direction face) {
        Key key = new Key(pos.immutable(), kind, face);
        if (this.handlers.containsKey(key)) {
            return this.handlers.get(key);
        }
        this.track(key.pos());
        Object handler = switch (kind) {
            case ITEM -> this.level.getCapability(Capabilities.ItemHandler.BLOCK, key.pos(), face);
            case FLUID -> this.level.getCapability(Capabilities.FluidHandler.BLOCK, key.pos(), face);
            case ENERGY -> this.level.getCapability(Capabilities.EnergyStorage.BLOCK, key.pos(), face);
        };
        this.handlers.put(key, handler);
        return handler;
    }

    private void track(BlockPos pos) {
        if (!this.tracked.add(pos)) {
            return;
        }
        // 一次性监听器：收到通知即失效，下次 get 会重新注册
        ICapabilityInvalidationListener listener = () -> {
            this.invalidate(pos);
            return false;
        };
        this.listeners.put(pos, listener);
        this.level.registerCapabilityListener(pos, listener);
    }

    private void invalidate(BlockPos pos) {
        this.tracked.remove(pos);
        this.listeners.remove(pos);
        this.handlers.keySet().removeIf(key -> key.pos().equals(pos));
        // 只标脏，不查世界：NeoForge 明确禁止在监听器里查询能力
        this.onInvalidate.accept(new BlockKey(this.level.dimension(), pos));
    }
}
