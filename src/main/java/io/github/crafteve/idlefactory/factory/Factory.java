package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;
import io.github.crafteve.idlefactory.factory.target.MachineLibrary;

/**
 * 一个工厂实例。
 *
 * <p>它是服务端对象，拥有自己的仓库插头、状态和（后续）产线图。它不知道终端的存在：
 * 终端打开与否、玩家在不在线，都不影响它的生命周期。</p>
 *
 * <p>工厂<b>拥有</b>一个仓库，但<b>不实现</b>仓库。物品永远现读现写
 * {@link StorageLink#storage()}，工厂自己不缓存一个物品。</p>
 */
public final class Factory {
    /** 工厂唯一 id。 */
    private final UUID id;
    /** 拥有者玩家 uuid；第一版一人一个工厂，鉴权只看这个字段。 */
    private final UUID owner;
    /** 仓库插头：永远对外给出非 null 的仓库。 */
    private final StorageLink storageLink = new StorageLink();
    /** 机器库：这台工厂接管的 IO 目标。 */
    private final MachineLibrary machines = new MachineLibrary();
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
    public StorageLink storageLink() {
        return this.storageLink;
    }

    /** 机器库。 */
    public MachineLibrary machines() {
        return this.machines;
    }

    /** 当前仓库，永远非 null。 */
    public Storage storage() {
        return this.storageLink.storage();
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
    public void bindAnchor(@Nullable StorageAnchor anchor) {
        this.storageLink.bind(anchor);
        updateStateFromStorage();
    }

    /**
     * 重新解析仓库地址并同步状态。服务器启动、界面刷新、定时重试时调。
     */
    public void refreshStorage() {
        this.storageLink.refresh();
        updateStateFromStorage();
    }

    private void updateStateFromStorage() {
        if (this.storageLink.anchor() == null) {
            this.state = FactoryState.CREATED;
        } else {
            this.state = this.storageLink.online() ? FactoryState.RUNNING : FactoryState.PAUSED;
        }
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
