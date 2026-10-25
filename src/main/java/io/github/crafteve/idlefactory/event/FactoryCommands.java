package io.github.crafteve.idlefactory.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.FactoryService;
import io.github.crafteve.idlefactory.factory.diag.DiagService;
import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.LineService;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.resource.ResourceKind;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.target.MachineTarget;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;

/**
 * 工厂指令入口。第一版不做 UI，所有调试都走这里。
 *
 * <p>命令树以产线为主语：{@code /idf line <line> add <machine>}，而不是先动词后对象。</p>
 *
 * <p>Tab 补全按上下文过滤：{@code add} 只补还没进任何产线的机器，{@code remove} / {@code manage}
 * 只补这条线里的机器，{@code remove} 的物品只补已经配过的。补全面时会顺带探一下这个面有没有物品
 * 能力，有能力的排在前面。</p>
 *
 * <p>会改状态的命令都套了 {@link #audit}，执行结果进诊断事件流，{@code /idf diag tail} 能看到。</p>
 */
@EventBusSubscriber(modid = IdleFactory.MODID)
public final class FactoryCommands {
    /** 产线参数补全：短 id + 名字，悬停带运行状态。 */
    private static final SuggestionProvider<CommandSourceStack> LINE_SUGGESTIONS = (context, builder) -> {
        Factory factory = factoryFor(context.getSource());
        if (factory != null) {
            for (PassiveLine line : factory.lines().all()) {
                // 一条产线只建议一次：名字优先，短 id 作兜底
                String label = FactoryRefs.isReservedName(line.name()) ? line.shortId() : line.name();
                builder.suggest(label, Component.literal("[" + line.shortId() + "] " + line.name()
                        + " · " + (line.running() ? "运行" : "停止")
                        + " · " + line.groups().size() + " 台"));
            }
        }
        return builder.buildFuture();
    };

    /** 面补全：{@code any} + 六向，能探到能力的排前面。 */
    private static final SuggestionProvider<CommandSourceStack> FACE_SUGGESTIONS = (context, builder) -> {        builder.suggest(ResourceSpec.ANY, Component.literal("不限面，按顺序尝试所有面"));
        Direction[] directions = Direction.values();
        Factory factory = factoryFor(context.getSource());
        MachineTarget machine = machineFrom(context, factory);
        if (factory == null || machine == null) {
            for (Direction direction : directions) {
                builder.suggest(direction.getName());
            }
            return builder.buildFuture();
        }

        MinecraftServer server = context.getSource().getServer();
        ServerLevel level = server.getLevel(machine.dimension());
        boolean loaded = level != null && level.isLoaded(machine.pos());
        List<Direction> available = new ArrayList<>();
        List<Direction> unavailable = new ArrayList<>();
        for (Direction direction : directions) {
            if (loaded && level.getCapability(Capabilities.ItemHandler.BLOCK, machine.pos(), direction) != null) {
                available.add(direction);
            } else {
                unavailable.add(direction);
            }
        }
        for (Direction direction : available) {
            builder.suggest(direction.getName(), Component.literal("这个面有物品能力"));
        }
        for (Direction direction : unavailable) {
            builder.suggest(direction.getName(), Component.literal(
                    loaded ? "这个面没有物品能力" : "区块未加载，无法探测"));
        }
        return builder.buildFuture();
    };

    /** 流体补全：全部注册流体。 */
    private static final SuggestionProvider<CommandSourceStack> FLUID_SUGGESTIONS = (context, builder) -> {
        for (ResourceLocation id : BuiltInRegistries.FLUID.keySet()) {
            builder.suggest(id.toString());
        }
        return builder.buildFuture();
    };

    private FactoryCommands() {
        // 纯静态事件处理器
    }
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralCommandNode<CommandSourceStack> root = event.getDispatcher().register(idleFactory(event));
        // 短别名：/idf ...
        event.getDispatcher().register(Commands.literal("idf").redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> idleFactory(RegisterCommandsEvent event) {
        return Commands.literal("idlefactory")
                .then(Commands.literal("create")
                        .executes(context -> FactoryService.create(player(context))))
                .then(Commands.literal("delete")
                        .executes(audit("factory.delete",
                                context -> FactoryService.delete(player(context)))))
                .then(factory())
                .then(line(event))
                .then(storage(event))
                .then(diag());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> factory() {
        return Commands.literal("factory")
                .then(Commands.literal("connect")
                        .executes(audit("factory.connect",
                                context -> FactoryService.connectMachine(player(context)))))
                .then(Commands.literal("disconnect")
                        .executes(audit("factory.disconnect",
                                context -> FactoryService.disconnectMachine(player(context))))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(allMachines())
                                .executes(audit("factory.disconnect",
                                        context -> FactoryService.disconnectMachine(
                                                player(context), StringArgumentType.getString(context, "id"))))))
                .then(Commands.literal("info")
                        .executes(context -> FactoryService.info(player(context))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> line(RegisterCommandsEvent event) {
        return Commands.literal("line")
                // /idf line 不带参数 = 列产线
                .executes(context -> LineService.list(player(context)))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(audit("line.create",
                                        context -> LineService.create(player(context),
                                                StringArgumentType.getString(context, "name"))))))
                .then(Commands.argument("line", StringArgumentType.word())
                        .suggests(LINE_SUGGESTIONS)
                        // /idf line 3f2a 不带子命令 = 看这条线
                        .executes(context -> LineService.info(player(context), lineRef(context)))
                        .then(Commands.literal("info")
                                .executes(context -> LineService.info(player(context), lineRef(context))))
                        .then(Commands.literal("start")
                                .executes(audit("line.start",
                                        context -> LineService.start(player(context), lineRef(context)))))
                        .then(Commands.literal("end")
                                .executes(audit("line.end",
                                        context -> LineService.end(player(context), lineRef(context)))))
                        .then(Commands.literal("delete")
                                .executes(audit("line.delete",
                                        context -> LineService.delete(player(context), lineRef(context)))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .suggests(freeMachines())
                                        .executes(audit("line.add",
                                                context -> LineService.add(player(context),
                                                        lineRef(context), machineRef(context))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .suggests(lineMachines())
                                        .executes(audit("line.remove",
                                                context -> LineService.remove(player(context),
                                                        lineRef(context), machineRef(context))))))
                        .then(Commands.literal("manage")
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .suggests(lineMachines())
                                        .then(Commands.literal("info")
                                                .executes(context -> LineService.manageInfo(player(context),
                                                        lineRef(context), machineRef(context))))
                                        .then(manageSide(event, true))
                                        .then(manageSide(event, false)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> manageSide(RegisterCommandsEvent event,
            boolean input) {
        String side = input ? "input" : "output";
        return Commands.literal(side)
                .then(Commands.literal("add")
                        .then(Commands.literal("item")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .executes(audit("line." + side + ".add",
                                                context -> addSpec(context, input, null)))
                                        .then(Commands.argument("face", StringArgumentType.word())
                                                .suggests(FACE_SUGGESTIONS)
                                                .executes(audit("line." + side + ".add",
                                                        context -> addSpec(context, input,
                                                                StringArgumentType.getString(context, "face")))))))
                        .then(Commands.literal("fluid")
                                .then(Commands.argument("fluid", ResourceLocationArgument.id())
                                        .suggests(FLUID_SUGGESTIONS)
                                        .executes(audit("line." + side + ".add",
                                                context -> addFluidSpec(context, input, null)))
                                        .then(Commands.argument("face", StringArgumentType.word())
                                                .suggests(FACE_SUGGESTIONS)
                                                .executes(audit("line." + side + ".add",
                                                        context -> addFluidSpec(context, input,
                                                                StringArgumentType.getString(context, "face")))))))
                        .then(Commands.literal("energy")
                                .executes(audit("line." + side + ".add",
                                        context -> addEnergySpec(context, input, null)))
                                .then(Commands.argument("face", StringArgumentType.word())
                                        .suggests(FACE_SUGGESTIONS)
                                        .executes(audit("line." + side + ".add",
                                                context -> addEnergySpec(context, input,
                                                        StringArgumentType.getString(context, "face")))))))
                .then(Commands.literal("remove")
                        .then(Commands.literal("item")
                                .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                        .suggests(configuredItems(input, ResourceKind.ITEM))
                                        .executes(audit("line." + side + ".remove",
                                                context -> removeSpec(context, input)))))
                        .then(Commands.literal("fluid")
                                .then(Commands.argument("fluid", ResourceLocationArgument.id())
                                        .suggests(configuredItems(input, ResourceKind.FLUID))
                                        .executes(audit("line." + side + ".remove",
                                                context -> removeFluidSpec(context, input)))))
                        .then(Commands.literal("energy")
                                .executes(audit("line." + side + ".remove",
                                        context -> removeEnergySpec(context, input)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> storage(RegisterCommandsEvent event) {
        return Commands.literal("storage")
                .then(Commands.literal("connect")
                        .executes(audit("storage.connect",
                                context -> FactoryService.connectStorage(player(context)))))
                .then(Commands.literal("bind")
                        .executes(audit("storage.bind",
                                context -> FactoryService.bindContainer(player(context)))))
                .then(Commands.literal("unbind")
                        .executes(audit("storage.unbind",
                                context -> FactoryService.unbindStorage(player(context))))
                        .then(Commands.argument("container", StringArgumentType.word())
                                .suggests(containerIds())
                                .executes(audit("storage.unbind",
                                        context -> FactoryService.unbindContainer(
                                                player(context), containerRef(context))))))
                .then(Commands.literal("priority")
                        .then(Commands.argument("container", StringArgumentType.word())
                                .suggests(containerIds())
                                .then(Commands.argument("priority", IntegerArgumentType.integer(-100, 100))
                                        .executes(audit("storage.priority",
                                                context -> FactoryService.setContainerPriority(
                                                        player(context), containerRef(context),
                                                        IntegerArgumentType.getInteger(context, "priority")))))))
                .then(Commands.literal("info")
                        .executes(context -> FactoryService.storageInfo(player(context))))
                .then(Commands.literal("get")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(audit("storage.get",
                                        context -> FactoryService.storageGet(
                                                player(context), item(context), 1)))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(audit("storage.get",
                                                context -> FactoryService.storageGet(
                                                        player(context), item(context),
                                                        IntegerArgumentType.getInteger(context, "count")))))))
                .then(Commands.literal("put")
                        .then(Commands.argument("item", ItemArgument.item(event.getBuildContext()))
                                .executes(audit("storage.put",
                                        context -> FactoryService.storagePut(
                                                player(context), item(context), 1)))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(audit("storage.put",
                                                context -> FactoryService.storagePut(
                                                        player(context), item(context),
                                                        IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> diag() {
        return Commands.literal("diag")
                .executes(context -> DiagService.status(player(context)))
                .then(Commands.literal("status")
                        .executes(context -> DiagService.status(player(context))))
                .then(Commands.literal("why")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .suggests(LINE_SUGGESTIONS)
                                .executes(context -> DiagService.why(player(context), lineRef(context)))
                                .then(Commands.argument("machine", StringArgumentType.word())
                                        .suggests(lineMachines())
                                        .executes(context -> DiagService.whyMachine(player(context),
                                                lineRef(context), machineRef(context))))))
                .then(Commands.literal("tail")
                        .executes(context -> DiagService.tail(player(context), 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(context -> DiagService.tail(player(context),
                                        IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("watch")
                        .then(Commands.argument("line", StringArgumentType.word())
                                .suggests(LINE_SUGGESTIONS)
                                .executes(context -> DiagService.watch(player(context), lineRef(context), 1, false))
                                .then(Commands.literal("detail")
                                        .executes(context -> DiagService.watch(player(context), lineRef(context), 5, true))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                                .executes(context -> DiagService.watch(player(context), lineRef(context),
                                                        IntegerArgumentType.getInteger(context, "seconds"), true))))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                        .executes(context -> DiagService.watch(player(context), lineRef(context),
                                                IntegerArgumentType.getInteger(context, "seconds"), false)))))
                .then(Commands.literal("io")
                        .executes(context -> DiagService.io(player(context)))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(
                                        FactoryRuntime.MIN_IO_INTERVAL, FactoryRuntime.MAX_IO_INTERVAL))
                                .executes(context -> DiagService.setIo(player(context),
                                        IntegerArgumentType.getInteger(context, "ticks")))))
                .then(Commands.literal("export")
                        .executes(context -> DiagService.export(player(context))));
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    /** 机器库里所有机器（远程移除用）。 */
    private static SuggestionProvider<CommandSourceStack> allMachines() {
        return (context, builder) -> {
            Factory factory = factoryFor(context.getSource());
            if (factory != null) {
                for (MachineTarget target : factory.machines().all()) {
                    builder.suggest(target.shortId(), machineTooltip(target));
                }
            }
            return builder.buildFuture();
        };
    }

    /** 还没进任何产线的机器（{@code line add} 用）。 */
    private static SuggestionProvider<CommandSourceStack> freeMachines() {
        return (context, builder) -> {
            Factory factory = factoryFor(context.getSource());
            if (factory != null) {
                Set<UUID> assigned = FactoryRefs.assignedMachines(factory);
                for (MachineTarget target : factory.machines().all()) {
                    if (!assigned.contains(target.id())) {
                        builder.suggest(target.shortId(), machineTooltip(target));
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    /** 这条线里的机器（{@code remove} / {@code manage} 用）。 */
    private static SuggestionProvider<CommandSourceStack> lineMachines() {
        return (context, builder) -> {
            Factory factory = factoryFor(context.getSource());
            PassiveLine line = lineFrom(context, factory);
            if (factory != null && line != null) {
                for (IoGroup group : line.groups()) {
                    factory.machines().byId(group.machineId())
                            .ifPresent(target -> builder.suggest(target.shortId(), machineTooltip(target)));
                }
            }
            return builder.buildFuture();
        };
    }

    /** 这台机器已经配过的同类资源（{@code remove} 用）。 */
    private static SuggestionProvider<CommandSourceStack> configuredItems(boolean input, ResourceKind kind) {
        return (context, builder) -> {
            Factory factory = factoryFor(context.getSource());
            PassiveLine line = lineFrom(context, factory);
            MachineTarget machine = machineFrom(context, factory);
            if (factory != null && line != null && machine != null) {
                line.groupByMachine(machine.id()).ifPresent(group -> {
                    for (ResourceSpec spec : input ? group.inputs() : group.outputs()) {
                        if (spec.key().kind() == kind) {
                            builder.suggest(spec.key().id().toString());
                        }
                    }
                });
            }
            return builder.buildFuture();
        };
    }

    /** 仓库里的容器短 id（{@code unbind} / {@code priority} 用）。 */
    private static SuggestionProvider<CommandSourceStack> containerIds() {
        return (context, builder) -> {
            Factory factory = factoryFor(context.getSource());
            if (factory != null && factory.storageLink().anchor() instanceof ContainerAnchor anchor) {
                for (ContainerRef ref : anchor.containers()) {
                    builder.suggest(ref.shortId(), Component.literal(
                            ref.dimension().location() + " " + ref.pos().toShortString()
                                    + " · 优先级 " + ref.priority()));
                }
            }
            return builder.buildFuture();
        };
    }

    // ------------------------------------------------------------------
    // 命令审计
    // ------------------------------------------------------------------

    /**
     * 把一个会改状态的命令包一层，记录成败。
     *
     * @param action  动作名
     * @param command 原命令
     * @return 包好的命令
     */
    private static Command<CommandSourceStack> audit(String action, Command<CommandSourceStack> command) {
        return context -> {
            int result;
            try {
                result = command.run(context);
            } catch (CommandSyntaxException e) {
                DiagService.command(context.getSource(), action, false);
                throw e;
            }
            DiagService.command(context.getSource(), action, result > 0);
            return result;
        };
    }

    // ------------------------------------------------------------------
    // 参数取用
    // ------------------------------------------------------------------

    private static int addSpec(CommandContext<CommandSourceStack> context, boolean input,
            @Nullable String face) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        ItemStack item = item(context);
        return input
                ? LineService.manageInputAdd(player, lineRef(context), machineRef(context), item, face)
                : LineService.manageOutputAdd(player, lineRef(context), machineRef(context), item, face);
    }

    private static int addFluidSpec(CommandContext<CommandSourceStack> context, boolean input,
            @Nullable String face) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        String fluid = ResourceLocationArgument.getId(context, "fluid").toString();
        return input
                ? LineService.manageInputAddFluid(player, lineRef(context), machineRef(context), fluid, face)
                : LineService.manageOutputAddFluid(player, lineRef(context), machineRef(context), fluid, face);
    }

    private static int addEnergySpec(CommandContext<CommandSourceStack> context, boolean input,
            @Nullable String face) throws CommandSyntaxException {
        ServerPlayer player = player(context);
        return input
                ? LineService.manageInputAddEnergy(player, lineRef(context), machineRef(context), face)
                : LineService.manageOutputAddEnergy(player, lineRef(context), machineRef(context), face);
    }

    private static int removeSpec(CommandContext<CommandSourceStack> context, boolean input)
            throws CommandSyntaxException {
        ServerPlayer player = player(context);
        ItemStack item = item(context);
        return input
                ? LineService.manageInputRemove(player, lineRef(context), machineRef(context), item)
                : LineService.manageOutputRemove(player, lineRef(context), machineRef(context), item);
    }

    private static int removeFluidSpec(CommandContext<CommandSourceStack> context, boolean input)
            throws CommandSyntaxException {
        ServerPlayer player = player(context);
        String fluid = ResourceLocationArgument.getId(context, "fluid").toString();
        return input
                ? LineService.manageInputRemoveFluid(player, lineRef(context), machineRef(context), fluid)
                : LineService.manageOutputRemoveFluid(player, lineRef(context), machineRef(context), fluid);
    }

    private static int removeEnergySpec(CommandContext<CommandSourceStack> context, boolean input)
            throws CommandSyntaxException {
        ServerPlayer player = player(context);
        return input
                ? LineService.manageInputRemoveEnergy(player, lineRef(context), machineRef(context))
                : LineService.manageOutputRemoveEnergy(player, lineRef(context), machineRef(context));
    }

    /**
     * 取当前玩家自己的工厂；控制台或未创建时返回 {@code null}。
     *
     * <p>补全也走这里，所以不能发消息。</p>
     */
    @Nullable
    private static Factory factoryFor(CommandSourceStack source) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            return null;
        }
        try {
            ServerPlayer player = source.getPlayerOrException();
            return runtime.manager().byOwner(player.getUUID()).orElse(null);
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    /**
     * 从已解析的命令上下文里读产线参数。
     *
     * <p>补全阶段 Brigadier 传进来的上下文不一定带 {@code getArgument}，所以先按已解析节点的
     * 文本范围取，取不到再按关键字在原始输入里找。两种都失败就当没有。</p>
     */
    @Nullable
    private static PassiveLine lineFrom(CommandContext<CommandSourceStack> context,
            @Nullable Factory factory) {
        if (factory == null) {
            return null;
        }
        String ref = argText(context, "line");
        return ref == null ? null : FactoryRefs.line(factory, ref).orElse(null);
    }

    /** 从已解析的命令上下文里读机器参数；读不到返回 {@code null}。 */
    @Nullable
    private static MachineTarget machineFrom(CommandContext<CommandSourceStack> context,
            @Nullable Factory factory) {
        if (factory == null) {
            return null;
        }
        String ref = argText(context, "machine");
        return ref == null ? null : FactoryRefs.machine(factory, ref).orElse(null);
    }

    /**
     * 取一个字符串参数的值。
     *
     * @param context 命令上下文
     * @param name    参数名
     * @return 参数值；还没解析到或取不到时为 {@code null}
     */
    @Nullable
    private static String argText(CommandContext<CommandSourceStack> context, String name) {
        String input = context.getInput();
        try {
            for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
                if (parsed.getNode() instanceof ArgumentCommandNode
                        && parsed.getNode().getName().equals(name)) {
                    StringRange range = parsed.getRange();
                    if (range.getEnd() <= input.length()) {
                        return input.substring(range.getStart(), range.getEnd());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 落到下面的词法兜底
        }
        if ("line".equals(name)) {
            String after = tokenAfter(input, "line");
            return after != null ? after : tokenAfter(input, "why");
        }
        if ("machine".equals(name)) {
            String after = tokenAfter(input, "manage");
            if (after == null) {
                after = tokenAfter(input, "add");
            }
            if (after == null) {
                after = tokenAfter(input, "remove");
            }
            return after;
        }
        return null;
    }

    /**
     * 原始输入里某个关键字后面的第一个 token。
     *
     * @param input   完整命令输入
     * @param keyword 关键字
     * @return 后面的 token；没有时为 {@code null}
     */
    @Nullable
    private static String tokenAfter(String input, String keyword) {
        String[] parts = input.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(keyword)) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private static Component machineTooltip(MachineTarget target) {
        return Component.literal(target.dimension().location() + " " + target.pos().toShortString());
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        return context.getSource().getPlayerOrException();
    }

    private static String lineRef(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "line");
    }

    private static String machineRef(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "machine");
    }

    private static String containerRef(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "container");
    }

    private static ItemStack item(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ItemInput input = ItemArgument.getItem(context, "item");
        return input.createItemStack(1, false);
    }
}
