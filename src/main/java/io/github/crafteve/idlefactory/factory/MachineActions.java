package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.machine.MatchMode;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 机器 IO 规则的写操作层：指令和界面共用。
 *
 * <p>规则跟着机器走，所以这里只按机器 id 找 {@link Machine}，不要求机器编进任何分组。规则写入
 * 后清掉这台机器的面路由并唤醒它，让下一轮按新规则重新探面。</p>
 *
 * <p>P1 删除产线后不再有「整组一起改」的语义：产线不再承载堆叠组，一次写操作只作用在一台机器上。
 * 原来「产线」参数只是为了回执里带 id，这里一并删掉。</p>
 *
 * <p>它只做「校验 + 改配置 + 标记存档 + 唤醒执行器」，不直接发聊天栏消息，也不依赖菜单/网络。
 * 返回值里的 {@link Result#message()} 是给玩家看的短句，命令侧用 {@code FactoryMessages} 包一层，
 * 界面侧放进反馈包。</p>
 */
public final class MachineActions {
    /** 每侧规则上限；和网络包、AGENTS 的规模约束一致。 */
    public static final int MAX_RULES_PER_SIDE = 16;

    /**
     * 一次写操作的结果。
     *
     * @param ok      是否成功
     * @param message 给玩家看的一句话
     */
    public record Result(boolean ok, String message) {
        /**
         * 成功结果。
         *
         * @param message 文案
         * @return 结果
         */
        public static Result ok(String message) {
            return new Result(true, message);
        }

        /**
         * 失败结果。
         *
         * @param message 文案
         * @return 结果
         */
        public static Result err(String message) {
            return new Result(false, message);
        }
    }

    private MachineActions() {
        // 纯静态动作层
    }

    /**
     * 加一条输入/输出规则。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       资源
     * @param face      面；{@code null} 表示不限
     * @param mode      匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static Result addRule(Factory factory, UUID machineId, IoSide side, AEKey key,
            @Nullable Direction face, MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        String resourceError = validateResource(key);
        if (resourceError != null) {
            return Result.err(resourceError);
        }
        MachineIoGroup io = ioFound.get();
        List<ResourceSpec> rules = rules(io, side);
        if (rules.size() >= MAX_RULES_PER_SIDE) {
            return Result.err(sideName(side) + "已达上限 " + MAX_RULES_PER_SIDE + " 条");
        }
        if (find(rules, key) != null) {
            return Result.err(sideName(side) + "里已经有 " + key.getDisplayName().getString());
        }
        ResourceSpec spec = new ResourceSpec(key, face, 0L, mode);
        boolean added = side == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
        if (!added) {
            return Result.err(sideName(side) + "没有写入（已存在或已满）");
        }
        afterRuleChange(machineId);
        return Result.ok("已加" + sideName(side) + "：" + spec.describe());
    }

    /**
     * 原地更新一条规则的面 / 匹配模式。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @param face      新面；{@code null} 表示不限
     * @param mode      新匹配模式；{@code null} 表示保留原值
     * @return 结果
     */
    public static Result updateRule(Factory factory, UUID machineId, IoSide side, AEKey key,
            @Nullable Direction face, @Nullable MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, side), key);
        if (current == null) {
            return Result.err(sideName(side) + "里没有 " + key.getDisplayName().getString());
        }
        ResourceSpec replacement = new ResourceSpec(key, face, current.amount(),
                mode == null ? current.mode() : mode);
        boolean replaced = side == IoSide.INPUT
                ? io.replaceInput(key, replacement)
                : io.replaceOutput(key, replacement);
        if (!replaced) {
            return Result.err(sideName(side) + "里没有 " + key.getDisplayName().getString());
        }
        afterRuleChange(machineId);
        return Result.ok("已更新" + sideName(side) + "：" + replacement.describe());
    }

    /**
     * 删除一条规则。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @return 结果
     */
    public static Result removeRule(Factory factory, UUID machineId, IoSide side, AEKey key) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        boolean ok = side == IoSide.INPUT ? io.removeInput(key) : io.removeOutput(key);
        if (!ok) {
            return Result.err(sideName(side) + "里没有 " + key.getDisplayName().getString());
        }
        afterRuleChange(machineId);
        return Result.ok("已移除" + sideName(side) + "：" + key.getDisplayName().getString());
    }

    private static Optional<MachineIoGroup> requireIo(Factory factory, UUID machineId) {
        return factory.config().machines().byId(machineId).map(Machine::io);
    }

    private static List<ResourceSpec> rules(MachineIoGroup io, IoSide side) {
        return side == IoSide.INPUT ? io.inputs() : io.outputs();
    }

    @Nullable
    private static ResourceSpec find(List<ResourceSpec> rules, AEKey key) {
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
    private static String validateResource(@Nullable AEKey key) {
        if (key == null) {
            return "资源为空";
        }
        return switch (AeKeySupport.kind(key)) {
            case ITEM -> AeKeySupport.toStack(key).isEmpty() ? "未知物品：" + key.getId() : null;
            case FLUID -> BuiltInRegistries.FLUID.get(key.getId()) == Fluids.EMPTY
                    ? "未知流体：" + key.getId() : null;
            case ENERGY -> AeKeySupport.energy().equals(key) ? null : "能量规则只能使用 idlefactory:fe";
        };
    }

    private static void afterRuleChange(UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        runtime.markDirty();
        runtime.machineRunner().forgetRoutes(machineId);
        runtime.machineRunner().wake(machineId);
    }
}
