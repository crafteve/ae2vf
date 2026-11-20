package io.github.crafteve.ae2vf.client.screen;

import io.github.crafteve.ae2vf.view.ViewTable;
import io.github.crafteve.ae2vf.view.ViewTableDiff;

/**
 * 视图表的客户端投递口：屏幕实现它，网络层只把报文交给当前屏幕。
 *
 * <p>屏幕不直接持有表结构，而是转给 {@code terminal.TerminalSource}：分页拼装与差量应用在
 * 数据源里完成，本接口只负责把报文投给当前屏幕。</p>
 */
public interface ViewTableSink {
    /**
     * 当前屏幕的菜单容器 id；报文里的 id 对不上就丢弃。
     *
     * @return 容器 id
     */
    int containerId();

    /**
     * 收到一页全量。
     *
     * @param containerId 报文里的容器 id
     * @param revision    这一页属于哪一版
     * @param pageIndex   页序号，从 0 开始
     * @param pageCount   总页数
     * @param page        本页节点
     */
    void acceptFullPage(int containerId, long revision, int pageIndex, int pageCount,
            ViewTable page);

    /**
     * 收到一份差量。
     *
     * @param containerId 报文里的容器 id
     * @param diff        差量
     */
    void acceptDiff(int containerId, ViewTableDiff diff);
}
