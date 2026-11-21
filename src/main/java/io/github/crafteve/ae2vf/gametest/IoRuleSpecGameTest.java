package io.github.crafteve.ae2vf.gametest;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.machine.IoRuleSpec;
import io.github.crafteve.ae2vf.machine.MatchMode;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * IO 规则的匹配模式与存档往返。
 *
 * <p>重点锁住三条：新规则默认精确、非物品强制精确、旧档缺 {@code Mode} 时按模糊读入。</p>
 */
public final class IoRuleSpecGameTest {
    /**
     * 新规则的默认模式是精确。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void defaultModeIsExact(GameTestHelper helper) {
        IoRuleSpec spec = new IoRuleSpec(
                AEItemKey.of(new ItemStack(Items.IRON_INGOT)), null, 64L);
        helper.assertTrue(spec.mode() == MatchMode.EXACT,
                "新规则默认应为精确，实际 " + spec.mode());
        helper.assertTrue(!spec.fuzzy(), "新规则不应是模糊");
        helper.assertTrue(spec.amount() == 64L, "数量应保留");
        helper.succeed();
    }

    /**
     * 显式指定的模糊模式应保留。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void explicitFuzzy(GameTestHelper helper) {
        IoRuleSpec spec = new IoRuleSpec(
                AEItemKey.of(new ItemStack(Items.IRON_INGOT)), null, 64L,
                MatchMode.IGNORE_COMPONENTS);
        helper.assertTrue(spec.fuzzy(), "显式模糊应生效");
        helper.succeed();
    }

    /**
     * 非物品没有组件语义，构造器强制精确。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void nonItemForcesExact(GameTestHelper helper) {
        IoRuleSpec spec = new IoRuleSpec(
                AEFluidKey.of(new FluidStack(Fluids.WATER, 1)), null, 1000L,
                MatchMode.IGNORE_COMPONENTS);
        helper.assertTrue(spec.mode() == MatchMode.EXACT,
                "非物品规则应强制精确，实际 " + spec.mode());
        helper.succeed();
    }

    /**
     * 规则存档往返：资源、面、数量、模式都应一致。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void saveLoadRoundTrip(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        IoRuleSpec spec = new IoRuleSpec(
                AEItemKey.of(new ItemStack(Items.IRON_INGOT)), Direction.NORTH, 64L,
                MatchMode.IGNORE_COMPONENTS);

        IoRuleSpec back = IoRuleSpec.load(spec.save(registries), registries);

        helper.assertTrue(back != null, "规则应能读回");
        helper.assertTrue(back.key().equals(spec.key()), "资源应一致");
        helper.assertTrue(back.face() == Direction.NORTH, "面应一致");
        helper.assertTrue(back.amount() == 64L, "数量应一致");
        helper.assertTrue(back.mode() == MatchMode.IGNORE_COMPONENTS, "模式应一致");
        helper.succeed();
    }

    /**
     * 旧档没有 {@code Mode} 字段：物品规则按模糊读入。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void missingModeReadsFuzzy(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        IoRuleSpec spec = new IoRuleSpec(
                AEItemKey.of(new ItemStack(Items.IRON_INGOT)), null, 64L);
        CompoundTag tag = spec.save(registries);
        tag.remove("Mode");

        IoRuleSpec back = IoRuleSpec.load(tag, registries);
        helper.assertTrue(back != null && back.mode() == MatchMode.IGNORE_COMPONENTS,
                "旧档缺 Mode 应按模糊读入");
        helper.succeed();
    }
}
