package io.github.crafteve.idlefactory.factory.io;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/**
 * 分析器：把执行结果同时写进账本与历史结论。
 *
 * <p>它是唯一维护这两份数据的地方：执行器只报结果，规划器与界面只读结论。账本是明细
 * （会滚掉），结论是摘要（不滚）；两者都由这里增量更新，不回放历史。</p>
 */
public final class LedgerAnalyzer {
    /**
     * 记一次执行结果。
     *
     * @param unit   执行单元
     * @param reason 原因
     * @param now    当前服务端 tick
     * @param amount 本次搬动的数量
     * @param face   用的面
     */
    public void record(IoUnit unit, Reason reason, long now, long amount,
            @Nullable Direction face) {
        unit.ledger().append(reason, now, amount, face);
        unit.digest().record(reason, now, amount, face);
    }
}
