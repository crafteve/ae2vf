package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.Warehouse;
import io.github.crafteve.idlefactory.factory.storage.WarehouseAnchor;
import io.github.crafteve.idlefactory.factory.storage.WarehouseLink;

/**
 * 一个工厂实例。
 *
 * <p>它是服务端对象，拥有自己的仓库插头、状态和（后续）产线图。它不知道终端的存在：
 * 终端打开与否、玩家在不在线，都不影响它的生命周期。</p>
 *
 * <p>工厂<b>拥有</b>一个仓库，但<b>不实现</b>仓库。物品永远现读现写
 * {@link WarehouseLink#warehouse()}，工厂自己不缓存一个物品。</p>
 */
public final class Factory {
    /** 工厂唯一 id。 */
    private final UUID id;
    /** 拥有者玩家 uuid；第一版一人一个工厂，鉴权只看这个字段。 */
    private final UUID owner;
    /** 仓库插头：永远对外给出非 null 的仓库。 */
    private final WarehouseLink warehouse = new WarehouseLink();
    /** 运行状态，只由工厂主体和仓库解析结果改变。 */
    private FactoryState state = FactoryState.CREATED;

    /**
     * @param id    工厂 id
     * @param owner 拥有者玩家 uuid
     */
    public Factory(UUID id, UUID owner) {
        this.id = id;
        this.owner = owner;
    }

    /** 工厂 id。 */
    public UUID id() {
        return this.id;
    }

    /** 拥有者玩家 uuid。 */
    public UUID owner() {
        return this.owner;
    }

    /** 仓库插头。 */
    public WarehouseLink warehouse() {
        return this.warehouse;
    }

    /** 当前状态。 */
    public FactoryState state() {
        return this.state;
    }

    /** 改状态；只允许工厂主体调用。 */
    public void setState(FactoryState newState) {
        this.state = newState;
    }

    /**
     * 绑定仓库地址并立即解析一次。
     *
     * @param anchor 新地址；{@code null} 表示解绑
     */
    public void bindAnchor(@Nullable WarehouseAnchor anchor) {
        this.warehouse.bind(anchor);
        this.state = anchor == null ? FactoryState.CREATED
                : (this.warehouse.online() ? FactoryState.RUNNING : FactoryState.PAUSED);
    }

    /**
     * 是否由这个玩家拥有。
     *
     * @param playerId 玩家 uuid
     * @return 是拥有者时为 {@code true}
     */
    public boolean isOwnedBy(UUID playerId) {
        return this.owner.equals(playerId);
    }
}
