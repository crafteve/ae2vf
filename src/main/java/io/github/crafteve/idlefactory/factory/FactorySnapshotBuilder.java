package io.github.crafteve.idlefactory.factory;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageLink;

/**
 * 把工厂当前状态整理成「前端能直接用的东西」。
 *
 * <p>它不依赖菜单、不依赖会话，谁要摘要都找它。第一版只产出一行可读文本（给指令和日志用），
 * 等 S2C 快照包稳定后再换成 DTO。</p>
 */
public final class FactorySnapshotBuilder {
    private FactorySnapshotBuilder() {
        // 纯静态工具类
    }

    /**
     * 生成一行人类可读的工厂摘要。
     *
     * @param factory 工厂
     * @return 摘要文本
     */
    public static String describe(Factory factory) {
        StorageLink link = factory.storageLink();
        StorageAnchor anchor = link.anchor();
        String anchorText = anchor == null ? "unbound" : anchor.describe();
        return "工厂 " + factory.id()
                + " | owner=" + factory.owner()
                + " | state=" + factory.state()
                + " | storage=" + (link.online() ? "online" : "offline")
                + " | anchor=" + anchorText;
    }

    /**
     * 工厂摘要的结构化版本，留给以后的 S2C 包。
     *
     * @param id           工厂 id
     * @param owner        拥有者
     * @param state        状态
     * @param storageOnline 仓库是否在线
     * @param anchor       仓库地址
     */
    public record Summary(java.util.UUID id, java.util.UUID owner, FactoryState state,
            boolean storageOnline, @Nullable StorageAnchor anchor) {
    }

    /**
     * 生成结构化摘要。
     *
     * @param factory 工厂
     * @return 摘要
     */
    public static Summary build(Factory factory) {
        return new Summary(factory.id(), factory.owner(), factory.state(),
                factory.storageLink().online(), factory.storageLink().anchor());
    }
}
