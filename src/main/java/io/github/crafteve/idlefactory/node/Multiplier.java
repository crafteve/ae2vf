package io.github.crafteve.idlefactory.node;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

/**
 * 节点的倍率值对象：模式 + 固定倍数。
 *
 * <p>倍率只影响 AE2 看到的样板份数，输入与输出同倍，不改变配比。固定倍率的倍数保存在这里；
 * 智能倍率的实际倍数在派单时按节点在线机器的容量算，失败就回退 ×1。</p>
 *
 * <p>这里不再夹一个「业务硬上限」：倍数只受 {@link #MIN_FACTOR} 与解码校验约束，真正的
 * 溢出保护在 {@link LiveNodePatternDetails#scale} 里按样板数量现算。给倍数设一个固定的业务上限
 * 会把「很大的样板」整张丢掉，那不是保护，是功能缺失。</p>
 *
 * @param mode   倍率模式
 * @param factor 固定倍率时的倍数；其它模式忽略
 */
public record Multiplier(MultiplierMode mode, int factor) {
    /** 最小倍数；倍率不允许是 0 或负数。 */
    public static final int MIN_FACTOR = 1;
    /** 固定倍率的档位梯子：循环时按这个顺序升，降序反过来。 */
    private static final int[] FIXED_LADDER = {2, 4, 8, 16, 64};

    private static final String TAG_MODE = "MultiplierMode";
    private static final String TAG_FACTOR = "MultiplierFactor";

    /**
     * 规范化倍数：至少 {@link #MIN_FACTOR}。
     *
     * <p>解码侧同样走这条规则：旧档 / 手改档里的 0 与负数按 1 读入，不让它们进乘法。</p>
     *
     * @param factor 原始倍数
     */
    public Multiplier {
        factor = Math.max(factor, MIN_FACTOR);
    }

    /**
     * 在「不倍增 → ×2 → ×4 → ×8 → ×16 → ×64 → 智能」之间循环。
     *
     * <p>按钮左键正向、右键反向；档位梯子只在这里维护一份，界面不再自己拼阶。</p>
     *
     * @param forward 正向为 {@code true}
     * @return 下一档
     */
    public Multiplier cycle(boolean forward) {
        int step = position();
        int total = FIXED_LADDER.length + 2;
        int next = Math.floorMod(forward ? step + 1 : step - 1, total);
        if (next == 0) {
            return off();
        }
        if (next == total - 1) {
            return new Multiplier(MultiplierMode.SMART, MIN_FACTOR);
        }
        return new Multiplier(MultiplierMode.FIXED, FIXED_LADDER[next - 1]);
    }

    /**
     * 当前档位在循环梯子里的下标：0 是不倍增，末尾是智能。
     *
     * @return 下标
     */
    private int position() {
        if (this.mode == MultiplierMode.OFF) {
            return 0;
        }
        if (this.mode == MultiplierMode.SMART) {
            return FIXED_LADDER.length + 1;
        }
        for (int i = 0; i < FIXED_LADDER.length; i++) {
            if (FIXED_LADDER[i] == this.factor) {
                return i + 1;
            }
        }
        // 手改档里的非梯子倍数：退到不超过它的最大梯子档，循环不会卡住。
        for (int i = FIXED_LADDER.length - 1; i >= 0; i--) {
            if (FIXED_LADDER[i] <= this.factor) {
                return i + 1;
            }
        }
        return 1;
    }

    /** 关：按样板原始数量派单。 */
    public static Multiplier off() {
        return new Multiplier(MultiplierMode.OFF, MIN_FACTOR);
    }

    /**
     * 这次派单实际使用的倍数。
     *
     * <p>关返回 1；固定返回配置倍数；智能使用调用方探测到的容量，探测失败（容量 &lt; 1）
     * 时回退 1。返回值保证不小于 {@link #MIN_FACTOR}。</p>
     *
     * @param smartCapacity 智能模式下节点在线机器的可装份数；其它模式忽略
     * @return 实际倍数
     */
    public int resolve(int smartCapacity) {
        return switch (this.mode) {
            case OFF -> MIN_FACTOR;
            case FIXED -> this.factor;
            case SMART -> Math.max(MIN_FACTOR, smartCapacity);
        };
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
