package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.line.LineActions;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;

/**
 * 机器 IO 规则的写操作层：指令和界面共用。
 *
 * <p>规则跟着机器走，所以这里只按机器 id 找 {@link Machine}，不要求机器已经编进产线。产线只用于
 * 回执定位与唤醒；规则本身不存产线。</p>
 */
public final class MachineActions {
    /** 每侧规则上限；和网络包、AGENTS 的规模约束一致。 */
    public static final int MAX_RULES_PER_SIDE = 16;

    private MachineActions() {
        // 纯静态动作层
    }

    /**
     * 加一条输入/输出规则；机器属于某个堆叠组时，整组一起加。
     *
     * <p>组由产线自己存（{@code stackId}），这里只负责按锚点找到同组成员逐个写。已经有的
     * 成员跳过并计数，回执写实际写入的台数，不假装全成功。</p>
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id；组场景里是锚点
     * @param side      输入 / 输出
     * @param key       资源
     * @param face      面；{@code null} 表示不限
     * @param mode      匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static LineActions.Result addRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key, @Nullable Direction face,
            MatchMode mode) {
        List<UUID> targets = stackTargets(factory, machineId);
        if (targets.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        String resourceError = validateResource(key);
        if (resourceError != null) {
            return LineActions.Result.err(resourceError);
        }
        ResourceSpec spec = new ResourceSpec(key, face, 0L, mode);
        int written = 0;
        int skipped = 0;
        for (UUID target : targets) {
            Optional<MachineIoGroup> ioFound = requireIo(factory, target);
            if (ioFound.isEmpty()) {
                skipped++;
                continue;
            }
            MachineIoGroup io = ioFound.get();
            List<ResourceSpec> rules = rules(io, side);
            if (rules.size() >= MAX_RULES_PER_SIDE || find(rules, key) != null) {
                skipped++;
                continue;
            }
            boolean added = side == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
            if (!added) {
                skipped++;
                continue;
            }
            afterRuleChange(factory, target);
            written++;
        }
        if (written == 0) {
            return LineActions.Result.err(sideName(side) + "没有写入任何一台（已存在或已满）");
        }
        String suffix = skipped > 0 ? "（跳过 " + skipped + " 台）" : "";
        return LineActions.Result.ok("已加" + sideName(side) + "到 " + written + " 台："
                + spec.describe() + suffix, lineId);
    }

    /**
     * 原地更新一条规则的面 / 匹配模式，整组一起改。
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id；组场景里是锚点
     * @param side      输入 / 输出
     * @param key       规则 key
     * @param face      新面；{@code null} 表示不限
     * @param mode      新匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static LineActions.Result updateRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key, @Nullable Direction face,
            MatchMode mode) {
        List<UUID> targets = stackTargets(factory, machineId);
        if (targets.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        int written = 0;
        int skipped = 0;
        ResourceSpec replacement = null;
        for (UUID target : targets) {
            Optional<MachineIoGroup> ioFound = requireIo(factory, target);
            if (ioFound.isEmpty()) {
                skipped++;
                continue;
            }
            MachineIoGroup io = ioFound.get();
            ResourceSpec current = find(rules(io, side), key);
            if (current == null) {
                skipped++;
                continue;
            }
            replacement = new ResourceSpec(key, face, current.amount(),
                    mode == null ? current.mode() : mode);
            boolean replaced = side == IoSide.INPUT
                    ? io.replaceInput(key, replacement)
                    : io.replaceOutput(key, replacement);
            if (!replaced) {
                skipped++;
                continue;
            }
            afterRuleChange(factory, target);
            written++;
        }
        if (written == 0 || replacement == null) {
            return LineActions.Result.err(sideName(side) + "里没有 " + key.describe());
        }
        String suffix = skipped > 0 ? "（跳过 " + skipped + " 台）" : "";
        return LineActions.Result.ok("已更新" + sideName(side) + "到 " + written + " 台："
                + replacement.describe() + suffix, lineId);
    }

    /**
     * 删除一条规则，整组一起删。
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id；组场景里是锚点
     * @param side      输入 / 输出
     * @param key       规则 key
     * @return 结果
     */
    public static LineActions.Result removeRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key) {
        List<UUID> targets = stackTargets(factory, machineId);
        if (targets.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        int removed = 0;
        int skipped = 0;
        for (UUID target : targets) {
            Optional<MachineIoGroup> ioFound = requireIo(factory, target);
            if (ioFound.isEmpty()) {
                skipped++;
                continue;
            }
            MachineIoGroup io = ioFound.get();
            boolean ok = side == IoSide.INPUT ? io.removeInput(key) : io.removeOutput(key);
            if (!ok) {
                skipped++;
                continue;
            }
            afterRuleChange(factory, target);
            removed++;
        }
        if (removed == 0) {
            return LineActions.Result.err(sideName(side) + "里没有 " + key.describe());
        }
        String suffix = skipped > 0 ? "（跳过 " + skipped + " 台）" : "";
        return LineActions.Result.ok("已移除" + sideName(side) + " " + removed + " 台："
                + key.describe() + suffix, lineId);
    }

    /**
     * 一条规则要写到哪些机器：编了线且在某个堆叠组里就是整组，否则只写它自己。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 目标机器 id；找不到机器时为空
     */
    private static List<UUID> stackTargets(Factory factory, UUID machineId) {
        if (factory.config().machines().byId(machineId).isEmpty()) {
            return List.of();
        }
        Optional<PassiveLine> lineFound = FactoryRefs.lineOf(factory, machineId);
        if (lineFound.isEmpty()) {
            return List.of(machineId);
        }
        PassiveLine line = lineFound.get();
        Optional<UUID> stackFound = line.stackOf(machineId);
        if (stackFound.isEmpty()) {
            return List.of(machineId);
        }
        for (PassiveLine.StackEntry stack : line.stacks()) {
            if (stack.stackId().equals(stackFound.get())) {
                return stack.machineIds();
            }
        }
        return List.of(machineId);
    }

    private static Optional<MachineIoGroup> requireIo(Factory factory, UUID machineId) {
        return factory.config().machines().byId(machineId).map(Machine::io);
    }

    private static List<ResourceSpec> rules(MachineIoGroup io, IoSide side) {
        return side == IoSide.INPUT ? io.inputs() : io.outputs();
    }

    @Nullable
    private static ResourceSpec find(List<ResourceSpec> rules, ResourceKey key) {
        for (ResourceSpec spec : rules) {
            if (spec.key().equals(key)) {
                return spec;
            }
        }
        return null;
    }

    private static String sideName(IoSide side) {
        return side == IoSide.INPUT ? "输入" : "输出";
    }

    @Nullable
    private static String validateResource(@Nullable ResourceKey key) {
        if (key == null) {
            return "资源为空";
        }
        return switch (key.kind()) {
            case ITEM -> key.toStack().isEmpty() ? "未知物品：" + key.id() : null;
            case FLUID -> BuiltInRegistries.FLUID.get(key.id()) == Fluids.EMPTY
                    ? "未知流体：" + key.id() : null;
            case ENERGY -> ResourceKey.ENERGY.equals(key) ? null : "能量规则只能使用 idlefactory:fe";
            case CHEMICAL -> "化学品规则暂不支持";
        };
    }

    private static void afterRuleChange(Factory factory, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        FactoryRefs.lineOf(factory, machineId).ifPresent(line -> {
            if (runtime != null) {
                runtime.machineRunner().forgetRoutes(machineId);
                runtime.machineRunner().wake(machineId);
            }
        });
    }
}
