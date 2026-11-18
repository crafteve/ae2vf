package io.github.crafteve.idlefactory.command;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.claim.BlockOccupancyTable;
import io.github.crafteve.idlefactory.config.IdleFactoryConfig;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.io.BlockingMode;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.node.Multiplier;
import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeIoGroup;
import io.github.crafteve.idlefactory.node.NodeLibrary;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;

import appeng.api.stacks.AEKey;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;

/**
 * 写命令的执行器：只改内存配置，不校验会话、不发包、不落盘。
 *
 * <p>门禁已在 {@link CommandGate} 做过一次，这里不再重复判空；每个分支只回答「改成了没有、
 * 原因是什么」。改完之后统一由 {@link CommandController} 落盘并推快照。</p>
 *
 * <p>规则动作的原子性在这里：换方向先加后删、删失败回滚；改名一次写完；倍率变化顺手作废
 * 样板缓存。执行器不持有跨命令状态。</p>
 */
public final class CommandApply {
    private CommandApply() {
        // 纯静态执行器
    }

    /**
     * 执行一条命令。
     *
     * @param context 上下文
     * @param command 命令
     * @return 回执
     */
    public static CommandResult apply(CommandContext context, FactoryCommand command) {
        Factory factory = context.factory();
        return switch (command.kind()) {
            case RULE_ADD -> addRule(factory, command.nodeId(), command.body());
            case RULE_UPDATE -> updateRule(factory, command.nodeId(), command.body());
            case RULE_MOVE -> moveRule(factory, command.nodeId(), command.body());
            case RULE_REMOVE -> removeRule(factory, command.nodeId(), command.body());
            case PROVIDER_BLOCKING -> setBlocking(factory, command.nodeId(), command.body());
            case PROVIDER_MULTIPLIER -> setMultiplier(factory, command.nodeId(), command.body());
            case PROVIDER_DISPATCH -> setDispatch(factory, command.nodeId(), command.body());
            case NODE_RENAME -> rename(factory, command.nodeId(), command.body());
            case NODE_DELETE -> deleteNode(context, command.nodeId());
            case SET_ANCHOR -> toggleAnchor(context, command);
            default -> CommandResult.of(CommandReason.INTERNAL);
        };
    }

    public static CommandResult addRule(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Rule rule)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        CommandReason resourceError = validateResource(rule.key());
        if (resourceError != null) {
            return CommandResult.node(resourceError, nodeId);
        }
        NodeIoGroup io = ioFound.get();
        List<ResourceSpec> rules = rules(io, rule.side());
        int maxRules = IdleFactoryConfig.maxRulesPerSide();
        if (rules.size() >= maxRules) {
            return CommandResult.node(CommandReason.RULE_FULL, nodeId);
        }
        if (find(rules, rule.key()) != null) {
            return CommandResult.node(CommandReason.RULE_EXISTS, nodeId);
        }
        ResourceSpec spec = new ResourceSpec(rule.key(), rule.face(), 0L, rule.mode());
        boolean added = rule.side() == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
        if (!added) {
            return CommandResult.node(CommandReason.RULE_FULL, nodeId);
        }
        afterRuleChange(factory, nodeId);
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult updateRule(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Rule rule)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        NodeIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, rule.side()), rule.key());
        if (current == null) {
            return CommandResult.node(CommandReason.RULE_MISSING, nodeId);
        }
        MatchMode mode = rule.mode() == null ? current.mode() : rule.mode();
        ResourceSpec replacement = new ResourceSpec(rule.key(), rule.face(), current.amount(), mode);
        boolean replaced = rule.side() == IoSide.INPUT
                ? io.replaceInput(rule.key(), replacement)
                : io.replaceOutput(rule.key(), replacement);
        if (!replaced) {
            return CommandResult.node(CommandReason.RULE_MISSING, nodeId);
        }
        afterRuleChange(factory, nodeId);
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult moveRule(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Rule rule) || rule.targetSide() == null) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        IoSide from = rule.side();
        IoSide to = rule.targetSide();
        if (from == to) {
            return CommandResult.node(CommandReason.SAME_SIDE, nodeId);
        }
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        NodeIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, from), rule.key());
        if (current == null) {
            return CommandResult.node(CommandReason.RULE_MISSING, nodeId);
        }
        List<ResourceSpec> target = rules(io, to);
        int maxRules = IdleFactoryConfig.maxRulesPerSide();
        if (target.size() >= maxRules) {
            return CommandResult.node(CommandReason.RULE_FULL, nodeId);
        }
        if (find(target, rule.key()) != null) {
            return CommandResult.node(CommandReason.RULE_EXISTS, nodeId);
        }
        boolean added = to == IoSide.INPUT ? io.addInput(current) : io.addOutput(current);
        if (!added) {
            return CommandResult.node(CommandReason.RULE_FULL, nodeId);
        }
        boolean removed = from == IoSide.INPUT ? io.removeInput(rule.key()) : io.removeOutput(rule.key());
        if (!removed) {
            // 回滚：源侧没删掉就把目标侧刚加的那条撤掉，避免两侧各留一份。
            if (to == IoSide.INPUT) {
                io.removeInput(rule.key());
            } else {
                io.removeOutput(rule.key());
            }
            return CommandResult.node(CommandReason.INTERNAL, nodeId);
        }
        afterRuleChange(factory, nodeId);
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult removeRule(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Rule rule)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<NodeIoGroup> ioFound = requireIo(factory, nodeId);
        if (ioFound.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        NodeIoGroup io = ioFound.get();
        boolean ok = rule.side() == IoSide.INPUT
                ? io.removeInput(rule.key()) : io.removeOutput(rule.key());
        if (!ok) {
            return CommandResult.node(CommandReason.RULE_MISSING, nodeId);
        }
        afterRuleChange(factory, nodeId);
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult setBlocking(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Blocking blocking)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        found.get().provider().setBlocking(blocking.mode());
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult setMultiplier(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Multiplier multiplier)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        found.get().provider().setMultiplier(new Multiplier(multiplier.mode(), multiplier.factor()));
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
            runtime.nodeManager().markPatternsDirty(nodeId);
        }
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult setDispatch(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Dispatch dispatch)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        found.get().provider().setDispatch(dispatch.mode());
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    private static CommandResult rename(Factory factory, java.util.UUID nodeId,
            FactoryCommand.Body body) {
        if (!(body instanceof FactoryCommand.Rename rename)) {
            return CommandResult.of(CommandReason.INTERNAL);
        }
        Optional<Node> found = factory.config().nodes().byId(nodeId);
        if (found.isEmpty()) {
            return CommandResult.node(CommandReason.NODE_MISSING, nodeId);
        }
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return CommandResult.node(CommandReason.NOT_STARTED, nodeId);
        }
        int maxLength = IdleFactoryConfig.maxNameLength();
        String next = rename.name().trim();
        if (next.length() > maxLength) {
            return CommandResult.node(CommandReason.NAME_TOO_LONG, nodeId);
        }
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return CommandResult.node(CommandReason.NAME_CONTROL_CHAR, nodeId);
            }
        }
        NodeLibrary.Result result = factory.replaceNode(
                found.get().withName(next.isEmpty() ? null : next));
        if (result != NodeLibrary.Result.REPLACED) {
            return CommandResult.node(CommandReason.RENAME_FAILED, nodeId);
        }
        runtime.markDirty();
        runtime.nodeManager().refreshName(nodeId);
        return CommandResult.node(CommandReason.OK, nodeId);
    }

    /**
     * 仓库锚点 toggle：没绑过就绑到目标方块，已绑同一个方块就解绑。
     *
     * <p>占用先查表：同一位置不能同时是两家工厂的仓库，也不能是自己的机器。绑定成功后立即
     * 解析一次仓库，并推链接快照。</p>
     *
     * @param context 上下文
     * @param command 命令
     * @return 回执
     */
    private static CommandResult toggleAnchor(CommandContext context, FactoryCommand command) {
        Factory factory = context.factory();
        BlockKey target = command.target();
        if (target == null || context.player() == null) {
            return CommandResult.of(CommandReason.TARGET_UNLOADED);
        }
        BlockOccupancyTable.ClaimCheck claim = context.runtime().occupancy()
                .check(target, factory.id(), BlockOccupancyTable.Role.STORAGE);
        if (claim.blocked()) {
            return CommandResult.block(claim.kind() == BlockOccupancyTable.ClaimCheck.Kind.SELF_OTHER
                    ? CommandReason.OCCUPIED_SELF_ROLE : CommandReason.OCCUPIED_OTHER,
                    target, List.of());
        }
        BlockKey anchor = factory.config().storageAnchor();
        if (target.equals(anchor)) {
            factory.bindStorage(null, context.runtime().storageResolver());
            context.runtime().markDirtyLinks(factory);
            return CommandResult.block(CommandReason.OK, target, List.of());
        }
        if (anchor != null) {
            return CommandResult.block(CommandReason.OTHER_BOUND, target, List.of());
        }
        ServerLevel level = context.player().serverLevel();
        if (!level.isLoaded(target.pos())) {
            return CommandResult.block(CommandReason.TARGET_UNLOADED, target, List.of());
        }
        if (Ae2Binder.nodeAt(level, target.pos()) == null) {
            return CommandResult.block(CommandReason.NOT_ME_BLOCK, target, List.of());
        }
        factory.bindStorage(target, context.runtime().storageResolver());
        context.runtime().markDirtyLinks(factory);
        return CommandResult.block(CommandReason.OK, target, List.of());
    }

    private static CommandResult deleteNode(CommandContext context, java.util.UUID nodeId) {
        FactoryRuntime runtime = context.runtime();
        CommandTopology topology = new CommandTopology(runtime, runtime.nodeManager(),
                runtime.nodeIoRunner(), runtime.server());
        return topology.deleteNode(context.factory(), nodeId, context.player());
    }

    private static Optional<NodeIoGroup> requireIo(Factory factory, @Nullable java.util.UUID nodeId) {
        if (nodeId == null) {
            return Optional.empty();
        }
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

    @Nullable
    private static CommandReason validateResource(@Nullable AEKey key) {
        if (key == null) {
            return CommandReason.RESOURCE_EMPTY;
        }
        if (AeKeyAdapter.isEnergy(key)) {
            return AeKeyAdapter.energy().equals(key) ? null : CommandReason.RESOURCE_NOT_FE;
        }
        if (AeKeyAdapter.isFluid(key)) {
            return BuiltInRegistries.FLUID.get(key.getId()) == Fluids.EMPTY
                    ? CommandReason.RESOURCE_UNKNOWN_FLUID : null;
        }
        return AeKeyAdapter.toStack(key).isEmpty() ? CommandReason.RESOURCE_UNKNOWN_ITEM : null;
    }

    /**
     * 规则变化后标记存档并唤醒节点下全部机器，让下一轮按新规则重新探面。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     */
    private static void afterRuleChange(Factory factory, java.util.UUID nodeId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return;
        }
        runtime.markDirty();
        factory.config().nodes().byId(nodeId).ifPresent(node -> {
            for (var machine : node.machines()) {
                runtime.nodeIoRunner().wake(machine.id());
            }
        });
    }
}
