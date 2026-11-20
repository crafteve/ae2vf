package io.github.crafteve.idlefactory.view;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * 一个节点的差异：节点字段、规则增删改、机器增删改。
 *
 * <p>删除整个节点用 {@code remove = true} 表达，此时其余字段都为空；单条规则的增删只影响
 * 规则行，单台机器的增删只影响机器行，不会带动另一侧（草稿 23：差量按节点分组）。</p>
 *
 * @param nodeId          节点 id
 * @param remove          是否删除整个节点
 * @param core            节点自身字段；没变时为 {@code null}
 * @param ruleUpserts     新增或修改的规则
 * @param ruleRemoves     删除的规则身份
 * @param machineUpserts  新增或修改的机器
 * @param machineRemoves  删除的机器 id
 */
public record ViewNodeDiff(UUID nodeId, boolean remove, @Nullable ViewNodeCore core,
        List<ViewIoRule> ruleUpserts, List<ViewIoRule.Key> ruleRemoves,
        List<ViewMachine> machineUpserts, List<UUID> machineRemoves) {

    public ViewNodeDiff {
        ruleUpserts = List.copyOf(ruleUpserts);
        ruleRemoves = List.copyOf(ruleRemoves);
        machineUpserts = List.copyOf(machineUpserts);
        machineRemoves = List.copyOf(machineRemoves);
    }
}
