package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/**
 * 一个执行单元的账本：环形有界的「变化序列」。
 *
 * <p>它记的是变化不是时间线：连续同原因、同面的结果合并成一行（次数与累计数量累加），
 * 原因或面一变就开新行。当前结论就是最新那一行，不另外存一份。</p>
 *
 * <p>容量固定 {@link #CAPACITY} 行，满了覆盖最旧的；单元离开表单时整本账随单元一起释放。</p>
 */
public final class IoLedger {
    /** 每个单元保留的账本行数。 */
    public static final int CAPACITY = 8;

    /**
     * 一行账。
     *
     * @param reason    结果原因
     * @param firstTick 这一串第一次发生的时刻
     * @param lastTick  这一串最近一次发生的时刻
     * @param count     这一串发生了几次
     * @param amount    这一串累计搬动的数量
     * @param face      用的面；{@code null} 表示不限面或没定面
     */
    public record Row(Reason reason, long firstTick, long lastTick, int count, long amount,
            @Nullable Direction face) {
    }

    private final Row[] rows = new Row[CAPACITY];
    /** 下一行的写入位置。 */
    private int head;
    private int size;

    /**
     * 追加一次结果；与最新一行同原因同面时合并。
     *
     * @param reason 原因
     * @param tick   发生时刻
     * @param amount 本次搬动的数量
     * @param face   用的面
     */
    public void append(Reason reason, long tick, long amount, @Nullable Direction face) {
        Row newest = this.current();
        if (newest != null && newest.reason() == reason && Objects.equals(newest.face(), face)) {
            this.rows[this.newestIndex()] = new Row(reason, newest.firstTick(), tick,
                    newest.count() + 1, newest.amount() + amount, face);
            return;
        }
        this.rows[this.head] = new Row(reason, tick, tick, 1, amount, face);
        this.head = (this.head + 1) % CAPACITY;
        if (this.size < CAPACITY) {
            this.size++;
        }
    }

    /**
     * 最新一行（当前结论）。
     *
     * @return 最新一行；还没有账时为 {@code null}
     */
    @Nullable
    public Row current() {
        return this.size == 0 ? null : this.rows[this.newestIndex()];
    }

    /**
     * 从新到旧的全部行。
     *
     * @return 行列表；空账时为空列表
     */
    public List<Row> rows() {
        List<Row> list = new ArrayList<>(this.size);
        for (int index = 0; index < this.size; index++) {
            list.add(this.rows[this.indexFromNewest(index)]);
        }
        return list;
    }

    private int newestIndex() {
        return (this.head - 1 + CAPACITY) % CAPACITY;
    }

    private int indexFromNewest(int offset) {
        return (this.newestIndex() - offset + CAPACITY * 2) % CAPACITY;
    }
}
