package io.github.crafteve.idlefactory.factory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.DiagStats;
import io.github.crafteve.idlefactory.factory.storage.EmptyStorage;
import appeng.api.storage.MEStorage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

/**
 * 工厂的运行时部分：可以随时从 {@link FactoryConfig} 重建，不写存档。
 *
 * <p>只放「当前这一刻」的数据：解析出来的仓库、在线标记、解析错误、诊断计数、机器失联集合。
 * 方块、Level、capability 这类活对象一律不进来。</p>
 *
 * <p>仓库离线时 {@link #storage()} 仍是 {@link EmptyStorage#INSTANCE}，工厂表现为没料、没处放，
 * 而不是抛异常。</p>
 */
public final class FactoryLive {
    /** 当前仓库；离线时是空仓库，永远非 null。 */
    private MEStorage storage = EmptyStorage.INSTANCE;
    /** 上一次解析是否成功拿到仓库。 */
    private boolean storageOnline;
    /** 上一次解析的异常摘要；正常时为 {@code null}。 */
    @Nullable
    private String storageError;
    /** 诊断数据：事件环 + 搬运计数。 */
    private final DiagStats stats = new DiagStats();
    /** 方块已消失的机器 id；观测事实，不写档。 */
    private final Set<UUID> machineMissing = new HashSet<>();

    /** 由 {@link Factory} 创建；初始为空仓库、离线。 */
    FactoryLive() {
        // 初始状态：空仓库、离线、无错误
    }

    /** 当前仓库，永远非 null。 */
    public MEStorage storage() {
        return this.storage;
    }

    /** 仓库是否在线（上一次解析是否成功）。只用于界面与诊断，不参与搬运门禁。 */
    public boolean storageOnline() {
        return this.storageOnline;
    }

    /** 上一次解析的异常摘要；正常时为 {@code null}。 */
    @Nullable
    public String storageError() {
        return this.storageError;
    }

    /** 诊断数据。 */
    public DiagStats stats() {
        return this.stats;
    }

    /** 方块已消失的机器 id（只读）。 */
    public Set<UUID> machineMissing() {
        return Set.copyOf(this.machineMissing);
    }

    /**
     * 这台机器的方块是不是已经没了。
     *
     * @param machineId 机器 id
     * @return 失联时为 {@code true}
     */
    public boolean isMachineMissing(UUID machineId) {
        return this.machineMissing.contains(machineId);
    }

    /**
     * 标记机器失联；只由观测迁移调用。
     *
     * @param machineId 机器 id
     * @return 这次调用是否改变了状态
     */
    boolean markMachineMissing(UUID machineId) {
        return this.machineMissing.add(machineId);
    }

    /**
     * 清除失联标记；只由观测迁移调用。
     *
     * @param machineId 机器 id
     * @return 这次调用是否改变了状态
     */
    boolean clearMachineMissing(UUID machineId) {
        return this.machineMissing.remove(machineId);
    }

    /**
     * 重新解析仓库地址；只由 {@link Factory} 调用。解析失败降级成空仓库，不抛异常。
     *
     * @param anchor   仓库地址；{@code null} 表示未绑定
     * @param resolver 后端解析器；{@code null} 表示运行时尚未就绪
     * @return 在线状态是否发生变化
     */
    boolean refresh(@Nullable StorageAnchor anchor, @Nullable StorageResolver resolver) {
        boolean wasOnline = this.storageOnline;
        if (anchor == null) {
            this.storage = EmptyStorage.INSTANCE;
            this.storageOnline = false;
            this.storageError = null;
            return wasOnline;
        }
        if (resolver == null) {
            this.storage = EmptyStorage.INSTANCE;
            this.storageOnline = false;
            this.storageError = "仓库解析器未就绪";
            return wasOnline;
        }
        MEStorage resolved;
        try {
            resolved = resolver.resolve(anchor);
            this.storageError = null;
        } catch (RuntimeException e) {
            // 适配层炸了也不能把工厂带崩，降级成空仓库；异常留给诊断面板
            resolved = null;
            this.storageError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        if (resolved == null) {
            this.storage = EmptyStorage.INSTANCE;
            this.storageOnline = false;
        } else {
            this.storage = resolved;
            this.storageOnline = true;
        }
        return wasOnline != this.storageOnline;
    }
}
