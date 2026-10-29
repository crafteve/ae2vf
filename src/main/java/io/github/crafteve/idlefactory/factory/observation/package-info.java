/**
 * 世界观测层（L1）。
 *
 * <p><b>负责</b>：把「世界现在是什么样」变成一份可读、可缓存、可失效的快照——这个位置还有没有
 * 方块、健康结论是什么、下次什么时候复探。面表缓存住在 {@code capability} 包，这里只留
 * 健康、时钟和代数转发。</p>
 *
 * <p><b>不负责</b>：配置（{@code machine} / {@code storage} / {@code line} 的事）、搬运
 * （{@code io} 的事）、展示（{@code diag} 与根包的事）、占用归属（{@code links} 的事）。
 * 本包不写存档。</p>
 *
 * <p><b>依赖方向</b>：本包读 {@code location}（位置键）、{@code probe}（探测结果类型）、
 * {@code capability}（面表缓存）、{@code links}（事件过滤查表），绝不反向——那三个包
 * 谁也不许 import 本包的东西。观测的键是「位置」，不是「角色」——机器和容器都是同一个
 * 原子观测的消费者。</p>
 *
 * <p><b>唯一读世界的地方</b>是 {@link io.github.crafteve.idlefactory.factory.probe.BlockProbe}。
 * 执行层（{@code GroupRunner} / {@code ContainerStorage}）仍可直接取 handler 来搬运，但不得据此
 * 改写观测状态。</p>
 */
package io.github.crafteve.idlefactory.factory.observation;
