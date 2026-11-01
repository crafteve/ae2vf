package io.github.crafteve.idlefactory.client.screen.terminal;

import net.minecraft.client.renderer.Rect2i;

import io.github.crafteve.idlefactory.factory.io.IoSide;

/**
 * 左面板里一个可以接收 JEI 拖拽的区域。
 *
 * @param area 屏幕绝对坐标的命中矩形
 * @param side 拖进来的是输入规则还是输出规则
 */
public record FactoryTerminalDropArea(Rect2i area, IoSide side) {
}
