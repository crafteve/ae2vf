/**
 * 工厂的 IO 班组：搬运器、计划编译、事务写入、账本。
 *
 * <p><b>本包暂时为空。</b>旧的 {@code world/} 实现在 M2/M3 里积累了一批已知缺陷
 * （静态跨存档状态、删节点不清缓存、ME 满时先抽后塞、GUI 与搬运耦合等），不再迁移。
 * 等工厂主体（{@code Factory} / {@code FactoryRuntime} / {@code Warehouse}）稳定后，
 * 这里按新的「先模拟、后提交」契约重写。</p>
 */
package io.github.crafteve.idlefactory.factory.io;
