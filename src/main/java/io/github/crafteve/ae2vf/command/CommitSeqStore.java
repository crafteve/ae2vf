package io.github.crafteve.ae2vf.command;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 绑定杖草稿的提交序号窗口：防重放。
 *
 * <p>客户端每次改草稿都会递增 {@code requestSeq}；服务端只接受严格大于上次成功序号的提交，
 * 断线重发、旧包重放与并发提交都会被 {@code CommandWandGate} 拒掉。序号只在成功提交后前进，
 * 玩家下线时清空，避免换号后旧序号挡住新提交。</p>
 *
 * <p>它属于命令层而不是工厂生命周期：工厂启停、读档与 tick 编排都不关心这个状态。</p>
 */
public final class CommitSeqStore {
    /** 每个玩家已经接受过的最大序号；只前进、不后退。 */
    private final Map<UUID, Long> lastAccepted = new HashMap<>();

    /**
     * 玩家已经接受过的最大序号。
     *
     * @param playerId 玩家 id
     * @return 最大序号；从未成功提交过时为 {@code 0}
     */
    public long lastAccepted(UUID playerId) {
        return this.lastAccepted.getOrDefault(playerId, 0L);
    }

    /**
     * 记录一次成功提交。
     *
     * @param playerId 玩家 id
     * @param seq      本次草稿的版本号
     */
    public void accept(UUID playerId, long seq) {
        this.lastAccepted.merge(playerId, seq, Math::max);
    }

    /**
     * 清掉一个玩家的序号；下线时调。
     *
     * @param playerId 玩家 id
     */
    public void forget(UUID playerId) {
        this.lastAccepted.remove(playerId);
    }

    /** 清空全部序号（停服）。 */
    public void clear() {
        this.lastAccepted.clear();
    }
}
