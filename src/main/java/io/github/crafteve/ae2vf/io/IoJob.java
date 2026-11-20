package io.github.crafteve.ae2vf.io;

import io.github.crafteve.ae2vf.machine.Machine;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;

/**
 * 一条可直接执行的搬运任务：把某个资源按某个面上限搬一次。
 *
 * <p>Job 是「一次性」的：规划器这一 tick 产出，执行器这一 tick 执行完就丢，不跨轮保存。
 * 结果由 {@link IoResult} 返回，Job 自己不带结果——同一个 Job 概念如果既装输入又装输出，
 * 下一轮就没法复用了。</p>
 *
 * <p>面在这里定死：选面由规划器调 {@link FacePick} 完成，执行器不再选面。</p>
 *
 * @param entry  来源条目（机器 + 规则），账本与经验面靠它归位
 * @param face   这次写的面
 * @param key    资源（模糊规则已展开到具体变体）
 * @param amount 这一轮的量的上限；{@code <= 0} 不是合法 Job
 */
public record IoJob(IoForm.Entry entry, Direction face, AEKey key, long amount) {
    /** 任务所属机器。 */
    public Machine machine() {
        return this.entry.machine();
    }

    /** 任务方向。 */
    public IoSide side() {
        return this.entry.side();
    }

    /** 任务所属规则的运行时身份。 */
    public IoKey rule() {
        return this.entry.key().rule();
    }
}
