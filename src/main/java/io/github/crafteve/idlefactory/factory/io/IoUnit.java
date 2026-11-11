package io.github.crafteve.idlefactory.factory.io;

import java.util.UUID;

import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

/**
 * 表单上的一行：一台机器的某一条规则（输入或输出）。
 *
 * <p>单元跨 tick 复用：身份不变就复用同一个对象，只刷新内联事实。这有两个原因：</p>
 * <ul>
 *   <li>执行结论（睡眠、面记忆、计数）要跟着单元活，不能每 tick 重置；</li>
 *   <li>1024 台 × 16 条规则的规模下，每 tick 全量新建对象是没必要的开销。</li>
 * </ul>
 *
 * <p><b>数量必须每 tick 刷新</b>：{@link RuleKey} 不含 {@code amount}，玩家只改数量时身份不变，
 * 不刷新就会一直用旧数量搬。</p>
 */
public final class IoUnit {
    private final IoUnitKey key;
    private final Machine machine;
    private final boolean input;
    private ResourceSpec spec;
    /** 本轮是否还在表单上；表单重建后为 {@code false} 的单元会被释放。 */
    private boolean seen = true;

    /**
     * @param key     单元身份
     * @param machine 机器
     * @param input   是否输入
     * @param spec    规则
     */
    IoUnit(IoUnitKey key, Machine machine, boolean input, ResourceSpec spec) {
        this.key = key;
        this.machine = machine;
        this.input = input;
        this.spec = spec;
    }

    /** 单元身份。 */
    public IoUnitKey key() {
        return this.key;
    }

    /** 机器 id。 */
    public UUID machineId() {
        return this.key.machineId();
    }

    /** 机器。 */
    public Machine machine() {
        return this.machine;
    }

    /** 是否输入。 */
    public boolean input() {
        return this.input;
    }

    /** 当前规则；每 tick 由表单刷新。 */
    public ResourceSpec spec() {
        return this.spec;
    }

    /**
     * 刷新内联事实。
     *
     * @param spec 当前规则
     */
    void refresh(ResourceSpec spec) {
        this.spec = spec;
        this.seen = true;
    }

    /** 表单重建开始时调用：先假设本轮不在表单上。 */
    void markUnseen() {
        this.seen = false;
    }

    /**
     * 本轮是否还在表单上。
     *
     * @return 还在为 {@code true}
     */
    boolean seen() {
        return this.seen;
    }
}
