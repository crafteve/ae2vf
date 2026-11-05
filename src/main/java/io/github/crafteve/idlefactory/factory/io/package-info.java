/**
 * 工厂的 IO 执行层：遍历、取面、搬运事务与瞬态。
 *
 * <p>{@code MachineRunner} 按机器库遍历机器，{@code GroupRunner} 按 {@code NodeIoGroup} 跑一轮，
 * {@code RuleResolver} 负责取面与 handler，{@code IoTransfer} 是唯一写路径。{@code RunnerState}
 * 存机器的睡眠与面路由瞬态，不写配置、不写存档。</p>
 */
package io.github.crafteve.idlefactory.factory.io;
