package io.github.crafteve.idlefactory.factory;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.DiagStats;
import io.github.crafteve.idlefactory.factory.storage.EmptyStorage;
import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

/**
 * 工厂的运行时部分：可以随时从 {@link FactoryConfig} 重建，不写存档。
 *
 * <p>只放「当前这一刻」的数据：解析出来的仓库、在线标记、解析错误、诊断计数。方块、Level、
 * capability 这类活对象一律不进来。</p>
 *
 * <p>仓库离线时 {@link #storage()} 仍是 {@link EmptyStorage#INSTANCE}，工厂表现为没料、没处放，
 * 而不是抛异常。</p>
 */
public final class FactoryLive {
    /** 当前仓库；离线时是空仓库，永远非 null。 */
    private Storage storage = EmptyStorage.INSTANCE;
    /** 上一次解析是否成功拿到仓库。 */
    private boolean storageOnline;
    /** 上一次解析的异常摘要；正常时为 {@code null}。 */
    @Nullable
    private String storageError;
    /** 诊断数据：事件环 + 搬运计数。 */
    private final DiagStats stats = new DiagStats();

    /** 当前仓库，永远非 null。 */
    public Storage storage() {
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

    /**
     * 重新解析仓库地址。解析失败降级成空仓库，不抛异常。
     *
     * @param anchor   仓库地址；{@code null} 表示未绑定
     * @param resolver 后端解析器；{@code null} 表示运行时尚未就绪
     * @return 在线状态是否发生变化
     */
    public boolean refresh(@Nullable StorageAnchor anchor, @Nullable StorageResolver resolver) {
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
        Storage resolved;
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
