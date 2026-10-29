package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.IoSide;
import io.github.crafteve.idlefactory.factory.line.LineActions;
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
     * 加一条输入/输出规则。
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       资源
     * @param face      面；{@code null} 表示不限
     * @param mode      匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static LineActions.Result addRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key, @Nullable Direction face,
            MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        String resourceError = validateResource(key);
        if (resourceError != null) {
            return LineActions.Result.err(resourceError);
        }
        List<ResourceSpec> rules = rules(io, side);
        if (rules.size() >= MAX_RULES_PER_SIDE) {
            return LineActions.Result.err(sideName(side) + "规则最多 " + MAX_RULES_PER_SIDE + " 条");
        }
        if (find(rules, key) != null) {
            return LineActions.Result.err(sideName(side) + "里已经有 " + key.describe() + " 了");
        }
        ResourceSpec spec = new ResourceSpec(key, face, 0L, mode);
        boolean added = side == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
        if (!added) {
            return LineActions.Result.err(sideName(side) + "里已经有 " + key.describe() + " 了");
        }
        afterRuleChange(factory, machineId);
        return LineActions.Result.ok("已加" + sideName(side) + "：" + spec.describe(), lineId);
    }

    /**
     * 原地更新一条规则的面 / 匹配模式，规则身份（资源 key）不变。
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @param face      新面；{@code null} 表示不限
     * @param mode      新匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static LineActions.Result updateRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key, @Nullable Direction face,
            MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, side), key);
        if (current == null) {
            return LineActions.Result.err(sideName(side) + "里没有 " + key.describe());
        }
        ResourceSpec replacement = new ResourceSpec(key, face, current.amount(),
                mode == null ? current.mode() : mode);
        boolean replaced = side == IoSide.INPUT
                ? io.replaceInput(key, replacement)
                : io.replaceOutput(key, replacement);
        if (!replaced) {
            return LineActions.Result.err(sideName(side) + "里没有 " + key.describe());
        }
        afterRuleChange(factory, machineId);
        return LineActions.Result.ok("已更新" + sideName(side) + "：" + replacement.describe(),
                lineId);
    }

    /**
     * 删除一条规则。
     *
     * @param factory   工厂
     * @param lineId    回执里带上的产线 id；可为 {@code null}
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @return 结果
     */
    public static LineActions.Result removeRule(Factory factory, @Nullable UUID lineId,
            UUID machineId, IoSide side, ResourceKey key) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return LineActions.Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        boolean removed = side == IoSide.INPUT
                ? io.removeInput(key)
                : io.removeOutput(key);
        if (!removed) {
            return LineActions.Result.err(sideName(side) + "里没有 " + key.describe());
        }
        afterRuleChange(factory, machineId);
        return LineActions.Result.ok("已移除" + sideName(side) + "：" + key.describe(), lineId);
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
                runtime.lineRunner().forgetRoutes(line.id(), machineId);
                runtime.lineRunner().wakeGroup(line.id(), machineId);
            }
        });
    }
}
