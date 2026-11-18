package io.github.crafteve.idlefactory.factory;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.IoAnalyzer;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeLibrary;
import io.github.crafteve.idlefactory.storage.MeStorageResolver;

import appeng.api.storage.MEStorage;

/**
 * 一个工厂实例：id + 配置 + 运行时。
 *
 * <p>它是服务端对象，不感知终端是否存在。配置在 {@link FactoryConfig}，写档；运行时在
 * {@link FactoryLive}，可随时从配置重建。</p>
 */
public final class Factory {
    /** 工厂唯一 id。 */
    private final UUID id;
    /** 写档部分：拥有者、仓库锚点、节点库。 */
    private final FactoryConfig config;
    /** 当前仓库；离线时为 {@code null}。 */
    @Nullable
    private MEStorage storage;
    /** 上一次解析是否成功拿到仓库。 */
    private boolean storageOnline;
    /** 上一次解析的异常摘要；正常时为 {@code null}。 */
    @Nullable
    private String storageError;
    /** 执行账房：账本、结论与搬运速率（每工厂一份）。 */
    private final IoAnalyzer analyzer = new IoAnalyzer();

    /**
     * @param id     工厂 id
     * @param config 写档配置
     */
    public Factory(UUID id, FactoryConfig config) {
        this.id = id;
        this.config = config;
    }

    /** 工厂 id。 */
    public UUID id() {
        return this.id;
    }

    /** 写档配置。 */
    public FactoryConfig config() {
        return this.config;
    }

    /** 当前仓库；离线时为 {@code null}。 */
    @Nullable
    public MEStorage storage() {
        return this.storage;
    }

    /** 仓库是否在线（上一次解析是否成功）。 */
    public boolean storageOnline() {
        return this.storageOnline;
    }

    /** 上一次解析的异常摘要；正常时为 {@code null}。 */
    @Nullable
    public String storageError() {
        return this.storageError;
    }

    /** 执行账房。 */
    public IoAnalyzer analyzer() {
        return this.analyzer;
    }

    /**
     * 绑定仓库锚点并立即解析一次。
     *
     * @param anchor   新锚点；{@code null} 表示解绑
     * @param resolver 仓库解析器；{@code null} 表示运行时未就绪
     */
    public void bindStorage(@Nullable BlockKey anchor, @Nullable MeStorageResolver resolver) {
        this.config.setStorageAnchor(anchor);
        this.refresh(anchor, resolver);
    }

    /**
     * 读档用：把已经带规则的节点灌进节点库，不触发落盘。
     *
     * @param node 节点
     * @return 加入结果；id 重复或库满时不是 {@code ADDED}
     */
    NodeLibrary.Result restoreNode(Node node) {
        return this.config.library().add(node);
    }

    /**
     * 向节点库加一个节点。
     *
     * @param node 节点
     * @return 节点库的写入结果
     */
    public NodeLibrary.Result addNode(Node node) {
        return this.config.library().add(node);
    }

    /**
     * 从节点库删除一个节点。
     *
     * @param nodeId 节点 id
     * @return 节点库的写入结果
     */
    public NodeLibrary.Result removeNode(java.util.UUID nodeId) {
        return this.config.library().remove(nodeId);
    }

    /**
     * 替换节点库里的一个节点（改名等原地改写）。
     *
     * @param node 新节点
     * @return 节点库的写入结果
     */
    public NodeLibrary.Result replaceNode(Node node) {
        return this.config.library().replace(node);
    }

    /**
     * 重新解析仓库锚点。定时刷新与手动刷新都走这里。
     *
     * @param resolver 仓库解析器
     * @return 在线状态是否变化
     */
    public boolean refreshStorage(@Nullable MeStorageResolver resolver) {
        return this.refresh(this.config.storageAnchor(), resolver);
    }

    /**
     * 重新解析仓库锚点。解析失败降级成离线，不抛异常。
     *
     * @param anchor   仓库锚点；{@code null} 表示未绑定
     * @param resolver 仓库解析器；{@code null} 表示运行时尚未就绪
     * @return 在线状态是否发生变化
     */
    boolean refresh(@Nullable BlockKey anchor, @Nullable MeStorageResolver resolver) {
        boolean wasOnline = this.storageOnline;
        if (anchor == null) {
            this.storage = null;
            this.storageOnline = false;
            this.storageError = null;
            return wasOnline;
        }
        if (resolver == null) {
            this.storage = null;
            this.storageOnline = false;
            this.storageError = "仓库解析器未就绪";
            return wasOnline;
        }
        MEStorage resolved;
        try {
            resolved = resolver.resolve(anchor);
            this.storageError = null;
        } catch (RuntimeException e) {
            // 适配层炸了也不能把工厂带崩，降级成离线；异常留给诊断面板
            resolved = null;
            this.storageError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        this.storage = resolved;
        this.storageOnline = resolved != null;
        return wasOnline != this.storageOnline;
    }
}
