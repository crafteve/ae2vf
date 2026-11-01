package io.github.crafteve.idlefactory.client.screen.terminal;

/**
 * 主动样板面板：目标列表、样板槽视图与主动 / 被动切换。
 *
 * <p>当前阶段只画标题与空状态；目标枚举、分页与拖入拖出在后续步骤填进来。和被动面板
 * 分开，避免两个视图共享一个巨大的条件分支。</p>
 */
public final class FactoryPatternPanel extends FactoryTerminalPanel {
    @Override
    protected String title() {
        return "主动样板";
    }

    @Override
    protected String subtitle() {
        return "选择目标后管理样板与模式";
    }
}
