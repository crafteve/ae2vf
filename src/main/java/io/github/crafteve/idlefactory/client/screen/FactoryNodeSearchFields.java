package io.github.crafteve.idlefactory.client.screen;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.NodeStatus;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个节点在终端搜索里的字段：节点管理终端与被动 IO 终端共用同一份构造。
 *
 * <p>状态文字只取服务端下发的 {@link NodeStatus}，两个终端对"失联""健康过期""运行"等词
 * 必须命中同一批节点。坐标取代表成员（节点终端与被动 IO 都取第一台），其余是节点级计数。</p>
 */
public final class FactoryNodeSearchFields {
    private FactoryNodeSearchFields() {
        // 纯静态字段构造
    }

    /**
     * 构造一个节点的搜索字段。
     *
     * @param name         显示名（节点自定义名或类型方块名）
     * @param typeBlockId  节点类型方块 id；未知时可为 {@code null}
     * @param status       节点状态
     * @param dimensionId  代表成员所在维度 id；没有成员时为 {@code null}
     * @param pos          代表成员坐标；没有成员时为 {@code null}
     * @param machineCount 节点机器台数
     * @param ruleCount    规则条数
     * @param patternCount 样板份数；机器表没有这个字段时传 -1，省略该段
     * @param theme        当前主题，状态文字取色用
     * @return 搜索字段
     */
    public static FactorySearchQuery.Fields of(String name, @Nullable ResourceLocation typeBlockId,
            NodeStatus status, @Nullable String dimensionId, @Nullable BlockPos pos,
            int machineCount, int ruleCount, int patternCount, FactoryGuiTheme theme) {
        String id = typeBlockId == null ? "" : typeBlockId.toString();
        String namespace = typeBlockId == null ? "" : typeBlockId.getNamespace();
        StringBuilder tooltip = new StringBuilder(FactoryStatus.of(status, theme).text());
        if (dimensionId != null && pos != null) {
            tooltip.append('\n').append(dimensionId).append(' ').append(pos.toShortString());
        }
        tooltip.append("\n机器 ").append(machineCount)
                .append(" 台 · 规则 ").append(ruleCount).append(" 条");
        if (patternCount >= 0) {
            tooltip.append(" · 样板 ").append(patternCount).append(" 份");
        }
        return new FactorySearchQuery.Fields(name, id, namespace,
                FactoryUiText.modName(namespace), tooltip.toString(), List.of());
    }
}