/**
 * 世界观测层（L1）。
 *
 * <p><b>负责</b>：把「世界现在是什么样」变成一份可读、可缓存、可失效的快照——这个位置还有没有
 * 方块、是哪个方块、提供哪些能力、哪个面能干什么。</p>
 *
 * <p><b>不负责</b>：配置（{@code machine} / {@code storage} / {@code line} 的事）、搬运
 * （{@code io} 的事）、展示（{@code diag} 与根包的事）。本包不写存档。</p>
 *
 * <p><b>依赖方向</b>：本包只依赖 Minecraft 与 {@code resource}，不许 import 其它 factory 子包，
 * 也不许 import 根包。做到这一点靠 {@link io.github.crafteve.idlefactory.factory.observation.Located}：
 * 观测的键是「位置」，不是「角色」——机器和容器都是同一个原子观测的消费者。</p>
 *
 * <p><b>唯一读世界的地方</b>是 {@link io.github.crafteve.idlefactory.factory.observation.BlockProbe}。
 * 执行层（{@code GroupRunner} / {@code ContainerStorage}）仍可直接取 handler 来搬运，但不得据此
 * 改写观测状态。</p>
 */
package io.github.crafteve.idlefactory.factory.observation;
