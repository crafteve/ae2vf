package io.github.crafteve.idlefactory.client.screen.search;

import io.github.crafteve.idlefactory.client.screen.UiResources;

import io.github.crafteve.idlefactory.client.screen.FactoryStatus;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;

import io.github.crafteve.idlefactory.client.screen.FactoryGuiTheme;

import io.github.crafteve.idlefactory.client.screen.search.SearchQuery;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个节点在终端搜索里的字段：节点管理终端与被动 IO 终端共用同一份构造。
 *
 * <p>状态文字只取服务端下发的 {@link ViewNode}，两个终端对"失联""健康过期""运行"等词必须
 * 命中同一批节点。坐标取第一台成员（节点终端与被动 IO 都取第一台），其余是节点级计数。</p>
 */
public final class SearchFields {
    private SearchFields() {
        // 纯静态字段构造
    }

    /**
     * 构造一个节点的搜索字段（视图表入口）。
     *
     * <p>计数与代表成员都从节点行自己取：{@code machines} 的顺序就是加入顺序，与旧快照的
     * "第一台"语义一致，所以两个终端对同一节点给出的坐标相同。</p>
     *
     * @param view         节点行
     * @param theme        当前主题，状态文字取色用
     * @param patternCount 样板份数；传 -1 表示省略该段（被动 IO 终端不看样板）
     * @return 搜索字段
     */
    public static SearchQuery.Fields of(ViewNode view, FactoryGuiTheme theme,
            int patternCount) {
        ViewMachine first = view.machines().isEmpty() ? null : view.machines().get(0);
        return build(view.core().name() != null
                        ? view.core().name() : FactoryLabels.blockName(view.core().typeBlockId()),
                view.core().typeBlockId(), FactoryStatus.of(view.core()).text(),
                first == null ? null : first.block().dimension().location().toString(),
                first == null ? null : first.block().pos(),
                view.machineCount(), view.ruleCount(), patternCount);
    }

    /**
     * 拼装搜索字段的唯一实现：两种入参只在这里汇合。
     *
     * @param name         显示名
     * @param typeBlockId  节点类型方块 id；未知时可为 {@code null}
     * @param statusText   状态文字
     * @param dimensionId  代表成员维度 id；没有成员时为 {@code null}
     * @param pos          代表成员坐标；没有成员时为 {@code null}
     * @param machineCount 机器台数
     * @param ruleCount    规则条数
     * @param patternCount 样板份数；-1 表示省略该段
     * @return 搜索字段
     */
    private static SearchQuery.Fields build(String name,
            @Nullable ResourceLocation typeBlockId, String statusText, @Nullable String dimensionId,
            @Nullable BlockPos pos, int machineCount, int ruleCount, int patternCount) {
        String id = typeBlockId == null ? "" : typeBlockId.toString();
        String namespace = typeBlockId == null ? "" : typeBlockId.getNamespace();
        StringBuilder tooltip = new StringBuilder(statusText);
        if (dimensionId != null && pos != null) {
            tooltip.append('\n').append(dimensionId).append(' ').append(pos.toShortString());
        }
        tooltip.append("\n机器 ").append(machineCount)
                .append(" 台 · 规则 ").append(ruleCount).append(" 条");
        if (patternCount >= 0) {
            tooltip.append(" · 样板 ").append(patternCount).append(" 份");
        }
        return new SearchQuery.Fields(name, id, namespace,
                UiResources.modName(namespace), tooltip.toString(), List.of());
    }
}
