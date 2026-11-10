package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;

import net.minecraft.resources.ResourceLocation;

/**
 * 一台机器的搜索字段构造。
 *
 * <p>搜索词到字段的映射集中在这里，被动 IO 终端与节点终端共用一份，避免两个屏幕对同一个词
 * 给出不同的匹配结果。</p>
 *
 * <p>字段内容：显示名（方块名）、注册名、命名空间、模组显示名、tooltip（状态 + 坐标 + 规则数）。
 * 标签为空：机器没有物品标签这类概念。</p>
 */
public final class FactoryMachineSearchFields {
    private FactoryMachineSearchFields() {
        // 纯静态工具类
    }

    /**
     * 构造一台机器的搜索字段。
     *
     * @param machine 机器视图
     * @param theme   当前主题，状态文字取色用
     * @return 搜索字段
     */
    public static FactorySearchQuery.Fields of(FactorySnapshotBuilder.MachineView machine,
            FactoryGuiTheme theme) {
        String id = machine.blockId() == null ? "" : machine.blockId().toString();
        String namespace = machine.blockId() == null ? "" : machine.blockId().getNamespace();
        String name = FactoryLabels.blockName(machine.blockId());
        String tooltip = FactoryStatus.text(machine, theme) + "\n"
                + machine.dimension().location() + " "
                + machine.pos().toShortString() + "\n"
                + "规则 " + machine.ruleCount() + " 条";
        return new FactorySearchQuery.Fields(name, id, namespace,
                FactoryUiText.modName(namespace), tooltip, java.util.List.of());
    }

    /**
     * 方块 id 的模组名；没有方块 id 时返回空串。
     *
     * @param blockId 方块 id；可为 {@code null}
     * @return 模组显示名
     */
    static String modName(@org.jetbrains.annotations.Nullable ResourceLocation blockId) {
        return blockId == null ? "" : FactoryUiText.modName(blockId.getNamespace());
    }
}
