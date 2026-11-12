package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import net.minecraft.world.level.Level;

/**
 * 倍率样板：把一张原始样板按倍数放大成 AE2 看到的样子。
 *
 * <p>AE2 的实际输入消耗 = 输入模板数量 × {@link IInput#getMultiplier()}，所以放大只动
 * multiplier，模板数量保持原样。两个都乘会让消耗变成倍率的平方。</p>
 *
 * <p>输出没有 multiplier，直接乘倍数；输入与输出同倍，不改变配比。所有数量与倍率的乘法都用
 * {@link Math#multiplyExact}：溢出直接判定这张样板不可用（{@code null}），绝不产生负数数量。
 * 倍率本身由调用方夹在 {@link Multiplier#HARD_MAX_FACTOR} 以内，这里只负责乘法与包装。</p>
 *
 * <p>派单时 CPU 已按本类 {@link #getInputs()} 把放大后的输入算进 inputHolder，所以
 * {@link #pushInputsToExternalInventory} 直接用接口默认实现原样推送。委托回原始样板会走它
 * 自己的「按槽位拆分」分支，那里用的是原始数量，会造成投入不放大、产出放大。</p>
 */
public final class ScaledPatternDetails implements IPatternDetails {
    private final IPatternDetails delegate;
    private final int factor;
    private final IInput[] inputs;
    private final List<GenericStack> outputs;

    private ScaledPatternDetails(IPatternDetails delegate, int factor, IInput[] inputs,
            List<GenericStack> outputs) {
        this.delegate = delegate;
        this.factor = factor;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    /**
     * 按倍数放大一张样板。
     *
     * <p>{@code factor <= 1} 时原样返回代理；任何乘法溢出都返回 {@code null}，由调用方跳过
     * 并记日志。</p>
     *
     * @param delegate 原始样板
     * @param factor   倍数；调用方保证在 1..{@link Multiplier#HARD_MAX_FACTOR}
     * @return 放大后的样板；溢出时为 {@code null}
     */
    public static ScaledPatternDetails scale(IPatternDetails delegate, int factor) {
        if (factor <= 1) {
            return new ScaledPatternDetails(delegate, 1, delegate.getInputs(),
                    delegate.getOutputs());
        }
        try {
            IPatternDetails.IInput[] originalInputs = delegate.getInputs();
            IInput[] scaledInputs = new IInput[originalInputs.length];
            for (int i = 0; i < originalInputs.length; i++) {
                scaledInputs[i] = new ScaledInput(originalInputs[i], factor);
            }
            List<GenericStack> originalOutputs = delegate.getOutputs();
            List<GenericStack> scaledOutputs = new ArrayList<>(originalOutputs.size());
            for (GenericStack output : originalOutputs) {
                scaledOutputs.add(new GenericStack(output.what(),
                        Math.multiplyExact(output.amount(), (long) factor)));
            }
            return new ScaledPatternDetails(delegate, factor, scaledInputs,
                    List.copyOf(scaledOutputs));
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** 实际使用的倍数；诊断用。 */
    public int factor() {
        return this.factor;
    }

    @Override
    public AEItemKey getDefinition() {
        return this.delegate.getDefinition();
    }

    @Override
    public IInput[] getInputs() {
        return this.inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return this.outputs;
    }

    @Override
    public boolean supportsPushInputsToExternalInventory() {
        return this.delegate.supportsPushInputsToExternalInventory();
    }

    @Override
    public void pushInputsToExternalInventory(KeyCounter[] inputHolder, PatternInputSink sink) {
        // inputHolder 已按本类的 N 倍输入算好；委托回原样板会拿原始数量再推一遍。
        IPatternDetails.super.pushInputsToExternalInventory(inputHolder, sink);
    }

    /**
     * 放大后的一个输入槽。
     *
     * <p>只放大 {@link #getMultiplier()}；{@link #getPossibleInputs()} 是候选模板，数量不参与
     * 放大。{@link #isValid} / {@link #getRemainingKey} 原样委托。</p>
     */
    private static final class ScaledInput implements IInput {
        private final IInput delegate;
        private final GenericStack[] possibleInputs;
        private final long multiplier;

        private ScaledInput(IInput delegate, int factor) {
            this.delegate = delegate;
            GenericStack[] original = delegate.getPossibleInputs();
            // 模板数量不放大，只做防御性拷贝，别把原样板的数组暴露出去。
            this.possibleInputs = Arrays.copyOf(original, original.length);
            this.multiplier = Math.multiplyExact(delegate.getMultiplier(), (long) factor);
        }

        @Override
        public GenericStack[] getPossibleInputs() {
            return this.possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return this.multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return this.delegate.isValid(input, level);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return this.delegate.getRemainingKey(template);
        }
    }
}
