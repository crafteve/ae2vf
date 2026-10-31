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
 * <p>状态被拆成「种类」和「样式」两层：{@link Kind} 只描述是什么状态，和主题无关，组聚合按它
 * 统计；{@link #of(Kind, FactorySnapshotBuilder.MachineView, FactoryGuiTheme)} 才把种类映射成
 * 颜色和贴图。这样统计不需要主题，渲染也不会各自重算一遍文案。</p>
 *
 * @param text  状态文字
 * @param color 状态文字颜色
 * @param strip 1px 状态条贴图
 * @param lamp  状态灯贴图
 */
record FactoryStatus(String text, int color, ResourceLocation strip, ResourceLocation lamp) {

    /**
     * 状态种类，和主题无关。
     *
     * <p>顺序是有意的：组聚合平票时按枚举顺序取第一个，保证同一份快照每次算出同一个结果，
     * 界面不会因为哈希顺序抖来抖去。</p>
     */
    enum Kind {
        /** 失联：方块没了、维度没了。 */
        OFFLINE,
        /** 输入堵塞：机器满了或拒收。 */
        INPUT_BLOCKED,
        /** 未载：区块没加载，或者还没探测。 */
        UNLOADED,
        /** 运行中。 */
        RUNNING,
        /** 可输入但没在干活。 */
        INPUT_OK,
        /** 仓库没料。不算报错，只是等待。 */
        WAITING_MATERIAL,
        /** 空闲。 */
        IDLE,
        /** 没编线或者没规则。 */
        UNCONFIGURED,
        /** 未知。 */
        UNKNOWN
    }

    /**
     * 取一台机器的状态种类。
     *
     * <p>失联优先于健康度：方块被拆掉时健康度可能还是旧的 PRESENT，不能显示成正常。
     * 未探测也归到未载，因为对玩家来说两者都是「现在看不到它」。</p>
     *
     * @param machine 机器视图
     * @return 状态种类
     */
    static Kind kindOf(FactorySnapshotBuilder.MachineView machine) {
        return switch (machine.totalState()) {
            case OFFLINE -> {
                if (machine.machineMissing()) {
                    yield Kind.OFFLINE;
                }
                yield switch (machine.health()) {
                    case MISSING, NO_DIMENSION -> Kind.OFFLINE;
                    default -> Kind.UNLOADED;
                };
            }
            case UNCONFIGURED -> Kind.UNCONFIGURED;
            case RUNNING -> Kind.RUNNING;
            case IDLE -> Kind.IDLE;
            case WAITING_MATERIAL -> Kind.WAITING_MATERIAL;
            case INPUT_OK -> Kind.INPUT_OK;
            case INPUT_BLOCKED -> Kind.INPUT_BLOCKED;
            case UNKNOWN -> Kind.UNKNOWN;
        };
    }

    /**
     * 把状态种类映射成文字、颜色和贴图。
     *
     * @param kind    状态种类
     * @param machine 机器视图；{@link Kind#UNCONFIGURED} 用它区分「没接入」和「无规则」
     * @param theme   当前主题
     * @return 状态展示
     */
    static FactoryStatus of(Kind kind, FactorySnapshotBuilder.MachineView machine,
            FactoryGuiTheme theme) {
        return switch (kind) {
            case OFFLINE -> new FactoryStatus("失联", theme.error(),
                    FactoryGuiTextures.STATUS_STRIP_ERROR, FactoryGuiTextures.STATUS_LAMP_ERROR);
            case UNLOADED -> new FactoryStatus("未载", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case UNCONFIGURED -> new FactoryStatus(
                    "无规则",
                    theme.statusIdle(), FactoryGuiTextures.STATUS_STRIP_IDLE,
                    FactoryGuiTextures.STATUS_LAMP_IDLE);
            case RUNNING -> new FactoryStatus("运行", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case INPUT_OK -> new FactoryStatus("可输入", theme.statusRun(),
                    FactoryGuiTextures.STATUS_STRIP_RUN, FactoryGuiTextures.STATUS_LAMP_RUN);
            case WAITING_MATERIAL -> new FactoryStatus("仓库缺料", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case INPUT_BLOCKED -> new FactoryStatus("输入堵塞", theme.statusWarn(),
                    FactoryGuiTextures.STATUS_STRIP_WARN, FactoryGuiTextures.STATUS_LAMP_WARN);
            case IDLE -> new FactoryStatus("空闲", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
            case UNKNOWN -> new FactoryStatus("未知", theme.statusIdle(),
                    FactoryGuiTextures.STATUS_STRIP_IDLE, FactoryGuiTextures.STATUS_LAMP_IDLE);
        };
    }

    /**
     * 把机器的总状态映射成文字、颜色和贴图。
     *
     * @param machine 机器视图
     * @param theme   当前主题
     * @return 状态展示
     */
    static FactoryStatus of(FactorySnapshotBuilder.MachineView machine, FactoryGuiTheme theme) {
        return of(kindOf(machine), machine, theme);
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
