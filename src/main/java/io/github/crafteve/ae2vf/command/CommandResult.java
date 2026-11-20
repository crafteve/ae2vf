package io.github.crafteve.ae2vf.command;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.key.BlockKey;

/**
 * 一次写命令的回执。
 *
 * <p>约定（与客户端一致）：{@code block == null} 表示命令级结果（整条命令成功或失败）；
 * {@code block != null} 表示该方块的逐目标结果；{@code nodeId} 只在节点级命令里出现。
 * 服务端不携带任何文本，客户端按 {@link CommandReason} 映射文案。</p>
 *
 * @param reason 结果原因
 * @param nodeId 节点级命令涉及的节点 id；其它命令为 {@code null}
 * @param block  逐目标结果涉及的方块；命令级结果为 {@code null}
 * @param blocks 逐目标结果的方块列表；命令级结果为空列表
 */
public record CommandResult(CommandReason reason, @Nullable UUID nodeId, @Nullable BlockKey block,
        List<BlockKey> blocks) {

    public CommandResult {
        blocks = List.copyOf(blocks);
    }

    /**
     * 命令级结果。
     *
     * @param reason 原因
     * @return 回执
     */
    public static CommandResult of(CommandReason reason) {
        return new CommandResult(reason, null, null, List.of());
    }

    /**
     * 节点级结果。
     *
     * @param reason 原因
     * @param nodeId 节点 id
     * @return 回执
     */
    public static CommandResult node(CommandReason reason, UUID nodeId) {
        return new CommandResult(reason, nodeId, null, List.of());
    }

    /**
     * 逐方块结果。
     *
     * @param reason 原因
     * @param block  目标方块
     * @param blocks 全量结果方块
     * @return 回执
     */
    public static CommandResult block(CommandReason reason, BlockKey block, List<BlockKey> blocks) {
        return new CommandResult(reason, null, block, blocks);
    }

    /** 是否成功。 */
    public boolean ok() {
        return this.reason.ok();
    }
}
