package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.view.ViewHeader;
import io.github.crafteve.idlefactory.view.ViewIoRule;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewNodeCore;
import io.github.crafteve.idlefactory.view.ViewNodeDiff;
import io.github.crafteve.idlefactory.view.ViewTable;
import io.github.crafteve.idlefactory.view.ViewTableDiff;

/**
 * 视图表的客户端投递口：屏幕实现它，网络层只把报文交给当前屏幕。
 *
 * <p>屏幕不直接持有表结构，而是转给 {@link Buffer}：它负责"先收齐同一版本的页再应用差量"。
 * 第 5 步把这个缓冲提升为终端数据源 {@code TerminalSource}，屏幕与网络层都不受影响。</p>
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

    /**
     * 分页拼装与差量应用。
     *
     * <p>规则固定：收齐同一 {@code revision} 的全部页之前不交付；差量的 {@code baseRevision}
     * 与已应用版本不一致时拒绝应用，由屏幕重新请求全量——服务端不补历史。</p>
     */
    final class Buffer {
        /** 规则排序：输入在前、输出在后，同侧按配置顺序。 */
        private static final Comparator<ViewIoRule> RULE_ORDER = Comparator
                .comparing((ViewIoRule rule) -> rule.side() == IoSide.OUTPUT ? 1 : 0)
                .thenComparingInt(ViewIoRule::order);

        private Map<Integer, ViewTable> pages = new HashMap<>();
        private long assemblingRevision = -1L;
        private int assemblingPages;

        private ViewTable table;
        private long revision;

        /**
         * 收一页。
         *
         * @param revision  版本
         * @param pageIndex 页序号
         * @param pageCount 总页数
         * @param page      本页
         * @return 拼装完成的整表；还没收齐时为 {@code null}
         */
        @Nullable
        public ViewTable acceptPage(long revision, int pageIndex, int pageCount, ViewTable page) {
            if (revision != this.assemblingRevision || pageCount != this.assemblingPages) {
                // 版本或页数变了：之前那一版作废，从这一页重新开始。
                this.pages = new HashMap<>(pageCount);
                this.assemblingRevision = revision;
                this.assemblingPages = pageCount;
            }
            this.pages.put(pageIndex, page);
            if (this.pages.size() < pageCount) {
                return null;
            }
            List<ViewNode> nodes = new ArrayList<>();
            for (int index = 0; index < pageCount; index++) {
                nodes.addAll(this.pages.get(index).nodes());
            }
            ViewTable merged = new ViewTable(this.pages.get(0).header(), nodes);
            this.pages = new HashMap<>();
            this.assemblingRevision = -1L;
            this.table = merged;
            this.revision = revision;
            return merged;
        }

        /**
         * 应用一份差量。
         *
         * @param diff 差量
         * @return 应用后的整表；版本对不上时为 {@code null}，调用方应重新请求全量
         */
        @Nullable
        public ViewTable acceptDiff(ViewTableDiff diff) {
            if (this.table == null || diff.baseRevision() != this.revision) {
                return null;
            }
            this.table = apply(this.table, diff);
            this.revision = diff.revision();
            return this.table;
        }

        /** 已应用的表；还没有时为 {@code null}。 */
        @Nullable
        public ViewTable table() {
            return this.table;
        }

        /** 已应用版本；还没有表时为 {@code 0}。 */
        public long revision() {
            return this.revision;
        }

        /** 丢弃全部状态（关闭终端或收到"无工厂"空表时）。 */
        public void reset() {
            this.pages = new HashMap<>();
            this.assemblingRevision = -1L;
            this.table = null;
            this.revision = 0L;
        }

        /**
         * 把差量套到旧表上。
         *
         * <p>删除整个节点直接丢；其余按"节点字段、规则、机器"三部分各自合并，最后按节点
         * {@code order} 与规则同侧顺序排好，界面不需要再排一次。</p>
         */
        private static ViewTable apply(ViewTable before, ViewTableDiff diff) {
            Map<UUID, ViewNode> nodes = new LinkedHashMap<>();
            for (ViewNode node : before.nodes()) {
                nodes.put(node.nodeId(), node);
            }
            for (ViewNodeDiff nodeDiff : diff.nodes()) {
                if (nodeDiff.remove()) {
                    nodes.remove(nodeDiff.nodeId());
                    continue;
                }
                ViewNode old = nodes.get(nodeDiff.nodeId());
                ViewNodeCore core = nodeDiff.core() != null ? nodeDiff.core()
                        : old == null ? null : old.core();
                if (core == null) {
                    // 既没有旧节点也没有节点字段：报文不完整，跳过这一个节点。
                    continue;
                }
                nodes.put(core.nodeId(), new ViewNode(core, rules(old, nodeDiff),
                        machines(old, nodeDiff)));
            }
            List<ViewNode> ordered = new ArrayList<>(nodes.values());
            ordered.sort(Comparator.comparingInt(node -> node.core().order()));
            ViewHeader header = diff.header() != null ? diff.header() : before.header();
            return new ViewTable(header, ordered);
        }

        private static List<ViewIoRule> rules(@Nullable ViewNode old, ViewNodeDiff diff) {
            Map<ViewIoRule.Key, ViewIoRule> rules = new LinkedHashMap<>();
            if (old != null) {
                for (ViewIoRule rule : old.rules()) {
                    rules.put(rule.identity(), rule);
                }
            }
            for (ViewIoRule.Key key : diff.ruleRemoves()) {
                rules.remove(key);
            }
            for (ViewIoRule rule : diff.ruleUpserts()) {
                rules.put(rule.identity(), rule);
            }
            List<ViewIoRule> merged = new ArrayList<>(rules.values());
            merged.sort(RULE_ORDER);
            return merged;
        }

        private static List<ViewMachine> machines(@Nullable ViewNode old, ViewNodeDiff diff) {
            Map<UUID, ViewMachine> machines = new LinkedHashMap<>();
            if (old != null) {
                for (ViewMachine machine : old.machines()) {
                    machines.put(machine.machineId(), machine);
                }
            }
            for (UUID machineId : diff.machineRemoves()) {
                machines.remove(machineId);
            }
            for (ViewMachine machine : diff.machineUpserts()) {
                machines.put(machine.machineId(), machine);
            }
            List<ViewMachine> merged = new ArrayList<>(machines.values());
            merged.sort(Comparator.comparingInt(ViewMachine::order));
            return merged;
        }
    }
}
