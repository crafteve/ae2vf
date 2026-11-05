package io.github.crafteve.idlefactory.factory.node;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 节点的倍率值对象：模式 + 固定倍数。
 *
 * <p>倍率只影响 AE2 看到的样板份数，输入与输出同倍，不改变配比。固定倍率的倍数保存在
 * 这里；智能倍率的实际倍数在派单时按目标机器容量探测，失败就回退 ×1。倍率是硬上限：
 * 超过 {@link #HARD_MAX_FACTOR} 一律夹回，避免样板数量溢出。</p>
 *
 * @param mode   倍率模式
 * @param factor 固定倍率时的倍数；其它模式忽略
 */
public record Multiplier(MultiplierMode mode, int factor) {
    /** 最小倍数；倍率不允许是 0 或负数。 */
    public static final int MIN_FACTOR = 1;
    /**
     * 倍率硬上限，不可由 config 关掉。
     *
     * <p>它同时是网络解码与存档解码的上限：再大的倍数会让样板数量乘法溢出，
     * 也让一份货超出任何机器容量，只会把余量队列撑爆。</p>
     */
    public static final int HARD_MAX_FACTOR = 64;

    private static final String TAG_MODE = "MultiplierMode";
    private static final String TAG_FACTOR = "MultiplierFactor";

    /**
     * 规范化倍数：夹在 1..{@link #HARD_MAX_FACTOR}。
     *
     * @param factor 原始倍数
     */
    public Multiplier {
        factor = Math.clamp(factor, MIN_FACTOR, HARD_MAX_FACTOR);
    }

    /** 关：按样板原始数量派单。 */
    public static Multiplier off() {
        return new Multiplier(MultiplierMode.OFF, MIN_FACTOR);
    }

    /**
     * 固定倍率。
     *
     * @param factor 倍数；越界会被夹到 1..{@link #HARD_MAX_FACTOR}
     * @return 倍率值对象
     */
    public static Multiplier fixed(int factor) {
        return new Multiplier(MultiplierMode.FIXED, factor);
    }

    /** 智能倍率；实际倍数在派单时探测。 */
    public static Multiplier smart() {
        return new Multiplier(MultiplierMode.SMART, MIN_FACTOR);
    }

    /**
     * 这次派单实际使用的倍数。
     *
     * <p>关返回 1；固定返回配置倍数；智能使用调用方探测到的容量，探测失败（容量 &lt; 1）
     * 时回退 1。返回值保证落在 1..{@link #HARD_MAX_FACTOR}，调用方不需要再夹一次。</p>
     *
     * @param smartCapacity 智能模式下目标机器能装下的份数；其它模式忽略
     * @return 实际倍数
     */
    public int resolve(int smartCapacity) {
        return switch (this.mode) {
            case OFF -> MIN_FACTOR;
            case FIXED -> this.factor;
            case SMART -> Math.clamp(smartCapacity, MIN_FACTOR, HARD_MAX_FACTOR);
        };
    }

    /**
     * 换模式；固定倍数保留，切回固定时沿用旧值。
     *
     * @param next 新模式
     * @return 新值对象
     */
    public Multiplier withMode(MultiplierMode next) {
        return new Multiplier(next, this.factor);
    }

    /**
     * 换固定倍数。
     *
     * @param next 新倍数
     * @return 新值对象
     */
    public Multiplier withFactor(int next) {
        return new Multiplier(this.mode, next);
    }

    /**
     * 序列化成 NBT。
     *
     * @return 新标签
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_MODE, this.mode.name());
        tag.putInt(TAG_FACTOR, this.factor);
        return tag;
    }

    /**
     * 从 NBT 读回来；字段缺失时按不倍率处理。
     *
     * @param tag 存档标签
     * @return 倍率
     */
    public static Multiplier load(@Nullable CompoundTag tag) {
        if (tag == null) {
            return off();
        }
        return new Multiplier(MultiplierMode.read(tag.getString(TAG_MODE)),
                tag.contains(TAG_FACTOR) ? tag.getInt(TAG_FACTOR) : MIN_FACTOR);
    }
}
