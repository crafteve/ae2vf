package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.node.NodeLibrary;
import io.github.crafteve.idlefactory.node.NodeLibraryView;

/**
 * 工厂的配置部分：需要写进存档、重启后必须原样恢复的内容。
 *
 * <p>只描述「玩家配置了什么」：拥有者、仓库地址、节点库。仓库解析结果、在线标记与诊断计数
 * 都在 {@link FactoryLive}，不写档。</p>
 *
 * <p>节点库就是工厂的全部节点：节点 → 机器 → 方块三层，IO 与 provider 都按节点跑。</p>
 *
 * <p>节点库本身可变，所以本类不做 record + 复制；它的边界是「不含运行时」。</p>
 */
public final class FactoryConfig {
    /** 拥有者玩家 uuid；鉴权只看这个字段。 */
    private final UUID owner;
    /** 仓库锚点方块；未绑定为 {@code null}。 */
    @Nullable
    private BlockKey storageAnchor;
    /** 节点库：工厂接管的全部节点与机器。 */
    private final NodeLibrary nodes = new NodeLibrary();

    /**
     * @param owner         拥有者玩家 uuid
     * @param storageAnchor 仓库锚点方块；未绑定为 {@code null}
     */
    public FactoryConfig(UUID owner, @Nullable BlockKey storageAnchor) {
        this.owner = owner;
        this.storageAnchor = storageAnchor;
    }

    /** 拥有者玩家 uuid。 */
    public UUID owner() {
        return this.owner;
    }

    /** 仓库锚点方块；未绑定为 {@code null}。 */
    @Nullable
    public BlockKey storageAnchor() {
        return this.storageAnchor;
    }

    /**
     * 设置仓库锚点；只由 {@link Factory#bindStorage} 调用。
     *
     * @param anchor 新锚点；{@code null} 表示解绑
     */
    void setStorageAnchor(@Nullable BlockKey anchor) {
        this.storageAnchor = anchor;
    }

    /**
     * 节点库的只读视图。
     *
     * <p>对外只暴露读方法：写入口是 {@code Factory#addNode / removeNode / replaceNode}（运行时）与
     * {@code Factory#restoreNode}（读档），调用方拿不到可变句柄。</p>
     *
     * @return 节点库视图
     */
    public NodeLibraryView nodes() {
        return this.nodes;
    }

    /**
     * 节点库的可变句柄；只允许工厂包内的写入口与读档路径使用。
     *
     * @return 节点库
     */
    NodeLibrary library() {
        return this.nodes;
    }
}
