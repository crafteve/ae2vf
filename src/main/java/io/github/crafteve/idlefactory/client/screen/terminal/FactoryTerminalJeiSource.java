package io.github.crafteve.idlefactory.client.screen.terminal;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.client.screen.FactoryScreen;

/**
 * 终端屏幕向 JEI 暴露的最小接口：R/U 查询与拖入目标。
 *
 * <p>两个屏幕类继承的 AE2 父类不同，没法共享一个基类；把 JEI 需要的两项能力抽成接口，
 * JEI 插件只认这个接口，不认具体屏幕。</p>
 */
public interface FactoryTerminalJeiSource {
    /**
     * 鼠标下的 JEI 查询目标。
     *
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 目标；没有时为 {@code null}
     */
    @Nullable
    FactoryScreen.JeiTarget jeiTargetAt(double mouseX, double mouseY);

    /**
     * 接收一个从 JEI 拖进来的资源。
     *
     * @param key    资源
     * @param mouseX 屏幕 x
     * @param mouseY 屏幕 y
     * @return 是否找到可接收的区域
     */
    boolean dropJeiKey(AEKey key, double mouseX, double mouseY);

    /**
     * 当前可接收 JEI 拖拽的区域。
     *
     * @return 区域列表；没选机器时为空
     */
    List<FactoryTerminalDropArea> jeiDropAreas();
}
