package io.github.crafteve.idlefactory.factory.storage;

import org.jetbrains.annotations.Nullable;

/**
 * 仓库插头：把「地址」翻译成「仓库」，并且永远对外给出一个非 null 的 {@link Warehouse}。
 *
 * <p>工厂主体只调 {@link #warehouse()}，永远不查「网络在不在线」。仓库连不上时插头交出的就是
 * {@link EmptyWarehouse}，于是工厂表现为「没料」和「没地方放」，而不是「网络断线」。</p>
 *
 * <p>具体怎么从地址找到仓库，由启动时注入的 {@link WarehouseResolver} 决定。
 * 第一版注入的是空解析器，接 AE2 之前所有仓库都是空的。</p>
 */
public final class WarehouseLink {
    /** 全局解析器；由 {@code FactoryRuntime} 在服务器启动时替换成真实实现。 */
    private static WarehouseResolver resolver = anchor -> null;

    /** 仓库地址；未绑定时为 {@code null}。 */
    @Nullable
    private WarehouseAnchor anchor;

    /** 当前交给工厂的仓库，永远非 null。 */
    private Warehouse warehouse = EmptyWarehouse.INSTANCE;

    /** 上一次解析是否真的拿到了仓库。 */
    private boolean online;

    /**
     * 注入全局解析器。只应在服务器启动、适配层就绪后调用一次。
     *
     * @param newResolver 新解析器，传 {@code null} 会退回空解析器
     */
    public static void setResolver(@Nullable WarehouseResolver newResolver) {
        resolver = newResolver == null ? (anchor -> null) : newResolver;
    }

    /**
     * 绑定地址并立即解析一次。
     *
     * @param newAnchor 新地址；传 {@code null} 表示解绑
     */
    public void bind(@Nullable WarehouseAnchor newAnchor) {
        this.anchor = newAnchor;
        refresh();
    }

    /**
     * 重新解析当前地址。服务器每轮搬运前可以调一次，用来把「离线」恢复成「在线」。
     */
    public void refresh() {
        if (this.anchor == null) {
            this.warehouse = EmptyWarehouse.INSTANCE;
            this.online = false;
            return;
        }
        Warehouse resolved;
        try {
            resolved = resolver.resolve(this.anchor);
        } catch (RuntimeException e) {
            // 适配层炸了也不能把工厂带崩，降级成空仓库
            resolved = null;
        }
        if (resolved == null) {
            this.warehouse = EmptyWarehouse.INSTANCE;
            this.online = false;
        } else {
            this.warehouse = resolved;
            this.online = true;
        }
    }

    /** 当前仓库，永远非 null。 */
    public Warehouse warehouse() {
        return this.warehouse;
    }

    /** 上一次解析是否成功拿到仓库。只用于界面诊断，不参与搬运判断。 */
    public boolean online() {
        return this.online;
    }

    /** 当前地址；未绑定时为 {@code null}。 */
    @Nullable
    public WarehouseAnchor anchor() {
        return this.anchor;
    }
}
