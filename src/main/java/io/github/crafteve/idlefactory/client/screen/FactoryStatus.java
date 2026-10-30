package io.github.crafteve.idlefactory.client.screen;

import net.minecraft.resources.ResourceLocation;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;

/**
 * 一台机器的总状态展示：文字、颜色、状态条贴图和状态灯贴图。
 *
 * <p>把状态映射从界面壳里拆出来，绘制、搜索字段和 tooltip 用同一份结果，避免同一台机器
 * 在不同位置显示不同状态。离线状态单独处理：失联、未载、未探测虽然都属于 OFFLINE，
 * 但文案和颜色不同，不能只看总状态枚举。</p>
 *
 * @param text  状态文字
 * @param color 状态文字颜色
 * @param strip 1px 状态条贴图
 * @param lamp  状态灯贴图
 */
record FactoryStatus(String text, int color, ResourceLocation strip, ResourceLocation lamp) {

    /**
     * 把机器的总状态映射成文字、颜色和贴图。
     *
     * @param machine 机器视图
     * @param theme   当前主题
     * @return 状态展示
     */
    static FactoryStatus of(FactorySnapshotBuilder.MachineView machine, FactoryGuiTheme theme) {
        return switch (machine.totalState()) {
            case OFFLINE -> offline(machine, theme);
            case UNCONFIGURED -> new FactoryStatus(
                    machine.lineNames().isEmpty() ? "未编线" : "无规则",
                    theme.statusIdle(), FactoryGuiTextures.STATUS_STRIP_IDLE,
                    FactoryGuiTextures.STATUS_LAMP_IDLE);
            case RUNNING -> new FactoryStatus("运行", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case IDLE -> new FactoryStatus("空闲", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
            case WAITING_MATERIAL -> new FactoryStatus("仓库缺料", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case INPUT_OK -> new FactoryStatus("可输入", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case INPUT_BLOCKED -> new FactoryStatus("输入堵塞", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            default -> new FactoryStatus("未知", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
        };
    }

    /**
     * 离线机器的细状态：失联 / 未载 / 未探测。
     *
     * <p>失联优先于健康度：方块被拆掉时健康度可能仍是旧的 PRESENT，不能显示成正常；
     * 未载用警告色而非错误色，因为区块卸载是临时状态。</p>
     *
     * @param machine 机器视图
     * @param theme   当前主题
     * @return 状态展示
     */
    static FactoryStatus offline(FactorySnapshotBuilder.MachineView machine,
            FactoryGuiTheme theme) {
        if (machine.machineMissing()) {
            return new FactoryStatus("失联", theme.error(),
                    FactoryGuiTextures.STATUS_STRIP_ERROR, FactoryGuiTextures.STATUS_LAMP_ERROR);
        }
        return switch (machine.health()) {
            case MISSING, NO_DIMENSION -> new FactoryStatus("失联", theme.error(),
                    FactoryGuiTextures.STATUS_STRIP_ERROR, FactoryGuiTextures.STATUS_LAMP_ERROR);
            case UNLOADED -> new FactoryStatus("未载", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            default -> new FactoryStatus("未探测", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
        };
    }

    /**
     * 机器状态文字；搜索字段与 tooltip 共用。
     *
     * @param machine 机器视图
     * @param theme   当前主题
     * @return 状态文字
     */
    static String text(FactorySnapshotBuilder.MachineView machine, FactoryGuiTheme theme) {
        return of(machine, theme).text();
    }
}
