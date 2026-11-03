package io.github.crafteve.idlefactory.menu;

/**
 * 被动 IO 内容区的两种视图。
 *
 * <p>只影响客户端怎样摆机器卡片，不改变任何服务端状态；值保存在终端的物品组件里，
 * 每个物品栈各自记住自己的视图。</p>
 */
public enum FactoryPassiveIoView {
    /** 列表式：一台机器一张卡片，规则逐行铺开。 */
    LIST,
    /** 磁贴式：机器卡片内规则按两列磁贴混排。 */
    CARD;

    /**
     * 下一个视图；视图按钮只在这两个值之间切换。
     *
     * @return 另一个视图
     */
    public FactoryPassiveIoView next() {
        return this == LIST ? CARD : LIST;
    }
}
