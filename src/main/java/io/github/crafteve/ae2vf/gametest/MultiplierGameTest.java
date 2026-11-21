package io.github.crafteve.ae2vf.gametest;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.node.Multiplier;
import io.github.crafteve.ae2vf.node.MultiplierMode;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 倍率值对象的档位循环与倍数解析。
 *
 * <p>这些断言不需要世界：倍率是纯值对象，只验证「按钮循环顺序」与「实际倍数」两条不变量。</p>
 */
public final class MultiplierGameTest {
    /** 固定档梯子，与实现保持一致。 */
    private static final int[] LADDER = {2, 4, 8, 16, 64};

    /**
     * 正向循环：关 → 2 → 4 → 8 → 16 → 64 → 智能 → 关。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void forwardCycle(GameTestHelper helper) {
        Multiplier current = Multiplier.off();
        for (int expected : LADDER) {
            current = current.cycle(true);
            helper.assertTrue(current.mode() == MultiplierMode.FIXED,
                    "正向循环应进入 FIXED，实际 " + current.mode());
            helper.assertTrue(current.factor() == expected,
                    "正向循环期望 " + expected + "，实际 " + current.factor());
        }
        current = current.cycle(true);
        helper.assertTrue(current.mode() == MultiplierMode.SMART,
                "固定档之后应是 SMART，实际 " + current.mode());
        current = current.cycle(true);
        helper.assertTrue(current.mode() == MultiplierMode.OFF,
                "SMART 之后应回到 OFF，实际 " + current.mode());
        helper.succeed();
    }

    /**
     * 反向循环：关 → 智能 → 64 → ... → 2 → 关。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void backwardCycle(GameTestHelper helper) {
        Multiplier current = Multiplier.off().cycle(false);
        helper.assertTrue(current.mode() == MultiplierMode.SMART,
                "反向第一档应是 SMART，实际 " + current.mode());
        for (int i = LADDER.length - 1; i >= 0; i--) {
            current = current.cycle(false);
            helper.assertTrue(current.mode() == MultiplierMode.FIXED
                            && current.factor() == LADDER[i],
                    "反向循环期望 " + LADDER[i] + "，实际 " + current);
        }
        current = current.cycle(false);
        helper.assertTrue(current.mode() == MultiplierMode.OFF,
                "反向走完梯子应回到 OFF，实际 " + current.mode());
        helper.succeed();
    }

    /**
     * 实际倍数的解析：关为 1、固定取配置、智能取探测容量且不小于 1。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void resolveAndNormalize(GameTestHelper helper) {
        helper.assertTrue(Multiplier.off().resolve(99) == 1, "OFF 实际倍数应为 1");
        helper.assertTrue(new Multiplier(MultiplierMode.FIXED, 8).resolve(99) == 8,
                "FIXED 应使用配置倍数");
        helper.assertTrue(new Multiplier(MultiplierMode.SMART, 1).resolve(0) == 1,
                "SMART 容量不足应回退 1");
        helper.assertTrue(new Multiplier(MultiplierMode.SMART, 1).resolve(5) == 5,
                "SMART 应使用探测容量");
        helper.assertTrue(new Multiplier(MultiplierMode.FIXED, 0).factor() == 1,
                "0 应规范为 1");
        helper.assertTrue(new Multiplier(MultiplierMode.FIXED, -3).factor() == 1,
                "负数应规范为 1");
        helper.succeed();
    }

    /**
     * 手改档里的非梯子倍数：循环时退到不超过它的最大梯子档，再升一档。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void offLadderFactorDoesNotStick(GameTestHelper helper) {
        Multiplier next = new Multiplier(MultiplierMode.FIXED, 3).cycle(true);
        helper.assertTrue(next.mode() == MultiplierMode.FIXED && next.factor() == 4,
                "非梯子倍数 3 正向应升到 4，实际 " + next);
        helper.succeed();
    }
}
