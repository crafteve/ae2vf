package io.github.crafteve.idlefactory.client.screen.terminal;

/**
 * 被动 IO 面板：机器列表、选中机器的规则编辑，以及搜索与排序。
 *
 * <p>当前阶段只画标题与空状态；机器表、规则行与 JEI 拖放在后续步骤填进来。单独成类
 * 是为了让合成视图的屏幕不必关心面板内部结构。</p>
 */
public final class FactoryIoPanel extends FactoryTerminalPanel {
    @Override
    protected String title() {
        return "被动 IO";
    }

    @Override
    protected String subtitle() {
        return "选择机器后编辑输入 / 输出规则";
    }
}
