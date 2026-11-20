package io.github.crafteve.ae2vf.client.screen.terminal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.io.IoSide;
import io.github.crafteve.ae2vf.network.payload.ViewTableRequestC2S;
import io.github.crafteve.ae2vf.view.ViewHeader;
import io.github.crafteve.ae2vf.view.ViewIoRule;
import io.github.crafteve.ae2vf.view.ViewMachine;
import io.github.crafteve.ae2vf.view.ViewNode;
import io.github.crafteve.ae2vf.view.ViewNodeCore;
import io.github.crafteve.ae2vf.view.ViewNodeDiff;
import io.github.crafteve.ae2vf.view.ViewTable;
import io.github.crafteve.ae2vf.view.ViewTableDiff;

import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 终端的数据源：分页拼装、差量应用与同步请求都收在这里。
 *
 * <p>规则固定：收齐同一 {@code revision} 的全部页之前不交付；差量的 {@code baseRevision} 与
 * 已应用版本不一致时丢弃并重新请求全量——服务端不补历史。两个终端各持一份，屏幕只负责把
 * 拼好的表交给模型。</p>
 */
public final class TerminalSource {
    /** 规则排序：输入在前、输出在后，同侧按配置顺序。 */
    private static final Comparator<ViewIoRule> RULE_ORDER = Comparator
            .comparing((ViewIoRule rule) -> rule.side() == IoSide.OUTPUT ? 1 : 0)
            .thenComparingInt(ViewIoRule::order);

    private final int containerId;
    private Map<Integer, ViewTable> pages = new HashMap<>();
    private long assemblingRevision = -1L;
    private int assemblingPages;
    private ViewTable table;
    private long revision;

    /**
     * @param containerId 菜单容器 id，请求报文要带
     */
    public TerminalSource(int containerId) {
        this.containerId = containerId;
    }

    /** 主动请求一次同步：带上已知版本，服务端据此决定回全量还是差量。 */
    public void request() {
        PacketDistributor.sendToServer(new ViewTableRequestC2S(this.containerId, this.revision));
    }

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
    public ViewTable acceptFullPage(long revision, int pageIndex, int pageCount, ViewTable page) {
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

    /** 丢弃全部状态（重开终端或收到「无工厂」空表时）。 */
    public void reset() {
        this.pages = new HashMap<>();
        this.assemblingRevision = -1L;
        this.table = null;
        this.revision = 0L;
    }

    /**
     * 把差量套到旧表上。
     *
     * <p>删除整个节点直接丢；其余按「节点字段、规则、机器」三部分各自合并，最后按节点
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
