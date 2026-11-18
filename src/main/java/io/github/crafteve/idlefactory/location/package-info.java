/**
 * 位置值层：全项目唯一的方块位置键。
 *
 * <p><b>负责</b>：把「维度 + 坐标」变成一个不可变的值，并提供由它派生的稳定 id。观测、
 * 能力缓存、面表、占用表、世界事件桥都用它当键。</p>
 *
 * <p><b>不负责</b>：读世界、存状态、判断角色。本包不 import 任何 factory 子包，也不依赖
 * Minecraft 的服务器或世界对象。</p>
 *
 * <p><b>依赖方向</b>：只依赖 Minecraft 的 {@code BlockPos}、{@code ResourceKey} 与 JDK；
 * 所有上层包（observation / capability / probe / links / io / machine / storage / 根包）
 * 向下依赖它，它不反向依赖任何一个。</p>
 */
package io.github.crafteve.idlefactory.location;
