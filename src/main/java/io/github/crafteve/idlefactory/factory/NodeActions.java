package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.io.IoSide;

import io.github.crafteve.idlefactory.machine.MatchMode;

import io.github.crafteve.idlefactory.machine.ResourceSpec;

import io.github.crafteve.idlefactory.io.BlockingMode;

import io.github.crafteve.idlefactory.io.DispatchMode;

import io.github.crafteve.idlefactory.node.Multiplier;

import io.github.crafteve.idlefactory.node.MultiplierMode;

import io.github.crafteve.idlefactory.node.Node;

import io.github.crafteve.idlefactory.node.NodeIoGroup;

import io.github.crafteve.idlefactory.node.NodeLibrary;

import io.github.crafteve.idlefactory.storage.AeKeySupport;

import appeng.api.stacks.AEKey;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;

/**
 * 节点级写操作层：指令与界面共用。
 *
 * <p>规则、阻挡、倍率、派单都归节点，所以这里只按节点 id 找 {@link Node}。规则写入后清掉该节点
 * 全部机器的面路由并唤醒它们，让下一轮按新规则重新探面。</p>
 *
 * <p>它只做「校验 + 改配置 + 标记存档 + 唤醒执行器」，不直接发聊天栏消息，也不依赖菜单/网络。
 * 返回值里的 {@link Result#message()} 是给玩家看的短句，命令侧用 {@code FactoryMessages} 包一层，
 * 界面侧放进反馈包。</p>
 */
public final class NodeActions {
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

    private NodeActions() {
        // 纯静态动作层
    }

    /**
     * 按机器 id 找所属节点；界面与指令的机器入口用它换算到节点。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 节点；机器不属于任何节点时为空
     */
    public static Optional<Node> nodeOfMachine(Factory factory, UUID machineId) {
        return factory.config().nodes().nodeOfMachine(machineId);
    }

    /**
     * 设置节点的阻挡开关。
     *
     * <p>阻挡只影响虚拟样板供应器的派单判定，不改 IO 规则，所以既不清路由也不唤醒执行器。</p>
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param mode    新开关；{@code null} 按不阻挡处理
     * @return 结果
     */
    public static Result setBlocking(Factory factory, UUID nodeId, @Nullable BlockingMode mode) {
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return Result.err("找不到节点");
        }
        BlockingMode next = mode == null ? BlockingMode.OFF : mode;
        found.get().provider().setBlocking(next);
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        return Result.ok("阻挡已" + (next.isOn() ? "开启" : "关闭") + "：["
                + shortId(nodeId) + "]");
    }

    /**
     * 设置节点的倍率。
     *
     * <p>倍率只改变 AE2 看到的样板份数，不改 IO 规则。改完必须作废样板缓存：CPU 读的是缓存里
     * 那批按旧倍率放大好的样板，只重发广告不会重算。</p>
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param mode    倍率模式
     * @param factor  固定倍数；非固定模式忽略
     * @return 结果
     */
    public static Result setMultiplier(Factory factory, UUID nodeId, MultiplierMode mode, int factor) {
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return Result.err("找不到节点");
        }
        Multiplier next = new Multiplier(mode, factor);
        found.get().provider().setMultiplier(next);
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
            runtime.nodeManager().markPatternsDirty(nodeId);
        }
        return Result.ok("倍率已改为 " + describeMultiplier(next));
    }

    /**
     * 设置节点的派单模式。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param mode    新模式
     * @return 结果
     */
    public static Result setDispatch(Factory factory, UUID nodeId, DispatchMode mode) {
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return Result.err("找不到节点");
        }
        found.get().provider().setDispatch(mode);
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        return Result.ok("派单已改为" + (mode == DispatchMode.ROUND_ROBIN ? "轮询" : "顺序"));
    }

    /**
     * 改节点名字；空串清除，恢复方块名。
     *
     * <p>名字只影响展示与搜索，不影响 IO 与派单，所以不唤醒执行器。校验只在这里做一次：
     * 去首尾空白、长度不超过配置上限、拒绝控制字符。</p>
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param raw     新名字；{@code null} 或空串表示清除
     * @return 结果
     */
    public static Result rename(Factory factory, UUID nodeId, @Nullable String raw) {
        Optional<Node> nodeFound = factory.config().nodes().byId(nodeId);
        if (nodeFound.isEmpty()) {
            return Result.err("找不到节点");
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return Result.err("Idle Factory 尚未启动（服务端未就绪）");
        }
        int maxLength = IdleFactoryConfig.maxNameLength();
        String next = raw == null ? "" : raw.trim();
        if (next.length() > maxLength) {
            return Result.err("名字最多 " + maxLength + " 个字符");
        }
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return Result.err("名字不能包含控制字符");
            }
        }
        Node node = nodeFound.get();
        NodeLibrary.Result result = factory.config().library()
                .replace(node.withName(next.isEmpty() ? null : next));
        if (result != NodeLibrary.Result.REPLACED) {
            return Result.err("改名失败：" + result);
        }
        runtime.markDirty();
        runtime.nodeManager().refreshName(nodeId);
        return Result.ok(next.isEmpty()
                ? "已恢复方块名：[" + shortId(nodeId) + "]"
                : "已改名为「" + next + "」");
    }

    /**
     * 加一条输入/输出规则。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param side    输入 / 输出
     * @param key     资源
     * @param face    面；{@code null} 表示不限
     * @param mode    匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static Result addRule(Factory factory, UUID nodeId, IoSide side, AEKey key,
            @Nullable Direction face, MatchMode mode) {
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到节点");
        }
        String resourceError = validateResource(key);
        if (resourceError != null) {
            return Result.err(resourceError);
        }
        NodeIoGroup io = ioFound.get();
        List<ResourceSpec> rules = rules(io, side);
        int maxRules = IdleFactoryConfig.maxRulesPerSide();
        if (rules.size() >= maxRules) {
            return Result.err(sideName(side) + "已达上限 " + maxRules + " 条");
        }
        if (find(rules, key) != null) {
            return Result.err(sideName(side) + "里已经有 " + key.getDisplayName().getString());
        }
        ResourceSpec spec = new ResourceSpec(key, face, 0L, mode);
        boolean added = side == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
        if (!added) {
            return Result.err(sideName(side) + "没有写入（已存在或已满）");
        }
        afterRuleChange(factory, nodeId);
        return Result.ok("已加" + sideName(side) + "：" + spec.describe());
    }

    /**
     * 原地更新一条规则的面 / 匹配模式。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param side    输入 / 输出
     * @param key     规则 key
     * @param face    新面；{@code null} 表示不限
     * @param mode    新匹配模式；{@code null} 表示保留原值
     * @return 结果
     */
    public static Result updateRule(Factory factory, UUID nodeId, IoSide side, AEKey key,
            @Nullable Direction face, @Nullable MatchMode mode) {
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到节点");
        }
        NodeIoGroup io = ioFound.get();
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
        afterRuleChange(factory, nodeId);
        return Result.ok("已更新" + sideName(side) + "：" + replacement.describe());
    }

    /**
     * 把一条规则从一个方向搬到另一个方向。
     *
     * <p>界面的方向按钮用它：客户端不能自己发「先删后加」——两个包之间没有原子性。
     * 这里先查目标侧能不能放，再先加后删；删失败时把刚加的那条回滚掉。</p>
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param from    当前方向
     * @param key     规则 key
     * @param to      目标方向
     * @return 结果
     */
    public static Result moveRule(Factory factory, UUID nodeId, IoSide from, AEKey key,
            IoSide to) {
        if (from == to) {
            return Result.err("方向没有变化");
        }
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到节点");
        }
        NodeIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, from), key);
        if (current == null) {
            return Result.err(sideName(from) + "里没有 " + key.getDisplayName().getString());
        }
        List<ResourceSpec> target = rules(io, to);
        int maxRules = IdleFactoryConfig.maxRulesPerSide();
        if (target.size() >= maxRules) {
            return Result.err(sideName(to) + "已达上限 " + maxRules + " 条");
        }
        if (find(target, key) != null) {
            return Result.err(sideName(to) + "里已经有 " + key.getDisplayName().getString());
        }
        boolean added = to == IoSide.INPUT ? io.addInput(current) : io.addOutput(current);
        if (!added) {
            return Result.err(sideName(to) + "没有写入（已存在或已满）");
        }
        boolean removed = from == IoSide.INPUT ? io.removeInput(key) : io.removeOutput(key);
        if (!removed) {
            // 回滚：源侧没删掉就把目标侧刚加的那条撤掉，避免两侧各留一份。
            if (to == IoSide.INPUT) {
                io.removeInput(key);
            } else {
                io.removeOutput(key);
            }
            return Result.err("换方向失败，规则已留在" + sideName(from));
        }
        afterRuleChange(factory, nodeId);
        return Result.ok("已改为" + sideName(to) + "：" + current.describe());
    }

    /**
     * 删除一条规则。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @param side    输入 / 输出
     * @param key     规则 key
     * @return 结果
     */
    public static Result removeRule(Factory factory, UUID nodeId, IoSide side, AEKey key) {
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到节点");
        }
        NodeIoGroup io = ioFound.get();
        boolean ok = side == IoSide.INPUT ? io.removeInput(key) : io.removeOutput(key);
        if (!ok) {
            return Result.err(sideName(side) + "里没有 " + key.getDisplayName().getString());
        }
        afterRuleChange(factory, nodeId);
        return Result.ok("已移除" + sideName(side) + "：" + key.getDisplayName().getString());
    }

    private static Optional<NodeIoGroup> requireIo(Factory factory, UUID nodeId) {
        return factory.config().nodes().byId(nodeId).map(Node::io);
    }

    private static List<ResourceSpec> rules(NodeIoGroup io, IoSide side) {
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

    /**
     * 倍率的中文描述，供回执复用。
     *
     * @param multiplier 倍率
     * @return 如「关」「×4」「智能」
     */
    public static String describeMultiplier(Multiplier multiplier) {
        return switch (multiplier.mode()) {
            case OFF -> "关";
            case FIXED -> "×" + multiplier.factor();
            case SMART -> "智能";
        };
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

    /**
     * 规则变化后清路由、唤醒执行器；节点下所有机器都要重探。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     */
    private static void afterRuleChange(Factory factory, UUID nodeId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        runtime.markDirty();
        Optional<Node> node = factory.config().nodes().byId(nodeId);
        if (node.isEmpty()) {
            return;
        }
        for (var machine : node.get().machines()) {
            runtime.nodeIoRunner().wake(machine.id());
        }
    }

    /**
     * 取 uuid 的前 8 位，供回执使用。
     *
     * @param id 节点 id
     * @return 短 id
     */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
