package io.github.crafteve.ae2vf.command;

import java.util.List;

import io.github.crafteve.ae2vf.key.BlockKey;

/**
 * 绑定杖连锁查询的结果：可接入的方块列表 + 原因。
 *
 * <p>连锁不是写命令，套 {@link CommandResult} 会把「一串方块」硬塞进单方块字段，这里单独
 * 建模。列表为空时原因说明为什么一个都没找到。</p>
 *
 * @param reason 原因
 * @param blocks 可接入的方块
 */
public record WandChainResult(CommandReason reason, List<BlockKey> blocks) {
    public WandChainResult {
        blocks = List.copyOf(blocks);
    }
}
