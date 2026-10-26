package io.github.crafteve.idlefactory.factory.line;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryBin;
import io.github.crafteve.idlefactory.factory.FactoryMessages;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

/**
 * 被动产线的指令逻辑。
 *
 * <p>只做「管理」：产线、IO 组、输入输出规则。{@code start} / {@code end} 只改开关，搬运由
 * {@code factory.io} 的执行器负责。</p>
 *
 * <p>引用解析统一走 {@link FactoryRefs}，消息统一走 {@link FactoryMessages}。</p>
 */
public final class LineService {
    private LineService() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // 产线
    // ------------------------------------------------------------------

    /**
     * 列出玩家自己的所有产线。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int list(ServerPlayer player) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        List<PassiveLine> lines = factory.lines().all();
        if (lines.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.info("还没有产线。先执行 /idf line create <名字>"));
            return 1;
        }
        FactoryMessages.send(player, FactoryMessages.info("产线 " + lines.size() + " 条："));
        for (PassiveLine line : lines) {
            MutableComponent row = Component.empty()
                    .append(FactoryMessages.lineLink(line.shortId()))
                    .append(FactoryMessages.text(" " + line.name() + " · ", FactoryMessages.INFO))
                    .append(FactoryMessages.text(line.running() ? "运行" : "停止",
                            line.running() ? FactoryMessages.OK : FactoryMessages.INFO))
                    .append(FactoryMessages.text(" · " + line.groups().size() + " 台", FactoryMessages.INFO));
            FactoryMessages.send(player, row);
        }
        return 1;
    }

    /**
     * 新建一条被动产线。
     *
     * @param player 发起请求的玩家
     * @param name   产线名
     * @return 命令结果
     */
    public static int create(ServerPlayer player, String name) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("产线名不能为空"));
            return 0;
        }
        if (FactoryRefs.isReservedName(trimmed)) {
            FactoryMessages.send(player, FactoryMessages.err("「" + trimmed + "」是保留字，换个名字"));
            FactoryMessages.send(player, FactoryMessages.info("保留字：" + FactoryRefs.describeReservedNames()));
            return 0;
        }
        if (factory.lines().byName(trimmed).isPresent()) {
            FactoryMessages.send(player, FactoryMessages.err("已有同名产线：" + trimmed));
            return 0;
        }
        PassiveLine line = new PassiveLine(UUID.randomUUID(), trimmed);
        factory.lines().add(line);
        markDirty();
        FactoryMessages.send(player, FactoryMessages.ok("已创建产线 [" + line.shortId() + "] " + trimmed));
        return 1;
    }

    /**
     * 删除一条产线。
     *
     * @param player  发起请求的玩家
     * @param lineRef 短 id 或名字
     * @return 命令结果
     */
    public static int delete(ServerPlayer player, String lineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        List<UUID> machineIds = new ArrayList<>(line.groups().size());
        for (IoGroup group : line.groups()) {
            machineIds.add(group.machineId());
        }
        factory.lines().remove(line.id());
        forgetLine(line.id());
        int purged = 0;
        for (UUID machineId : machineIds) {
            if (FactoryBin.purgeIfOrphaned(factory, observations(), machineId)) {
                purged++;
            }
        }
        markDirty();
        FactoryMessages.send(player, FactoryMessages.ok("已删除产线 [" + line.shortId() + "] " + line.name()
                + (purged > 0 ? " · 顺带清理了 " + purged + " 台回收站里的机器" : "")));
        return 1;
    }

    /**
     * 启动一条产线（只改开关）。
     *
     * @param player  发起请求的玩家
     * @param lineRef 短 id 或名字
     * @return 命令结果
     */
    public static int start(ServerPlayer player, String lineRef) {
        return setRunning(player, lineRef, true);
    }

    /**
     * 结束一条产线（只改开关）。
     *
     * @param player  发起请求的玩家
     * @param lineRef 短 id 或名字
     * @return 命令结果
     */
    public static int end(ServerPlayer player, String lineRef) {
        return setRunning(player, lineRef, false);
    }

    private static int setRunning(ServerPlayer player, String lineRef, boolean running) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        line.setRunning(running);
        wakeLine(line.id());
        markDirty();
        FactoryMessages.send(player, FactoryMessages.ok("产线 [" + line.shortId() + "] " + line.name()
                + " → " + (running ? "运行" : "停止")));
        return 1;
    }

    /**
     * 打印产线情况。
     *
     * @param player  发起请求的玩家
     * @param lineRef 短 id 或名字
     * @return 命令结果
     */
    public static int info(ServerPlayer player, String lineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MutableComponent head = Component.empty()
                .append(FactoryMessages.lineLink(line.shortId()))
                .append(FactoryMessages.text(" " + line.name() + " · ", FactoryMessages.INFO))
                .append(FactoryMessages.text(line.running() ? "运行" : "停止",
                        line.running() ? FactoryMessages.OK : FactoryMessages.INFO))
                .append(FactoryMessages.text(" · " + line.groups().size() + " 台", FactoryMessages.INFO));
        FactoryMessages.send(player, head);

        for (IoGroup group : line.groups()) {
            Optional<MachineRef> target = factory.machines().byId(group.machineId());
            if (target.isEmpty()) {
                FactoryMessages.send(player, FactoryMessages.err(
                        "  机器 " + group.machineId() + " 已不在机器库"));
                continue;
            }
            MachineRef machine = target.get();
            String where = machine.dimension().location() + " " + machine.pos().toShortString();
            MutableComponent row = Component.empty()
                    .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                    .append(FactoryMessages.hover(machine.shortId(), where))
                    .append(FactoryMessages.text(" " + where, FactoryMessages.INFO));
            FactoryMessages.send(player, row);
            FactoryMessages.send(player, FactoryMessages.text(
                    "    输入: " + describeSpecs(group.inputs()), FactoryMessages.PLAIN));
            FactoryMessages.send(player, FactoryMessages.text(
                    "    输出: " + describeSpecs(group.outputs()), FactoryMessages.PLAIN));
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // IO 组（机器）
    // ------------------------------------------------------------------

    /**
     * 把机器库里的机器加进产线。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器库里的机器短 id
     * @return 命令结果
     */
    public static int add(ServerPlayer player, String lineRef, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineRef machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        if (line.groupByMachine(machine.id()).isPresent()) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "这台机器已经在产线 " + line.name() + " 里了"));
            return 0;
        }
        for (PassiveLine other : factory.lines().all()) {
            if (!other.id().equals(line.id()) && other.groupByMachine(machine.id()).isPresent()) {
                FactoryMessages.send(player, FactoryMessages.err("这台机器已经属于产线 " + other.name()));
                FactoryMessages.send(player, FactoryMessages.info("先执行 /idf line " + other.shortId()
                        + " remove " + machine.shortId() + "，再加到别的线"));
                return 0;
            }
        }
        line.addGroup(new IoGroup(UUID.randomUUID(), machine.id()));
        wakeLine(line.id());
        markDirty();
        FactoryMessages.send(player, FactoryMessages.ok(
                "已把机器 [" + machine.shortId() + "] 加入产线 " + line.name()));
        return 1;
    }

    /**
     * 把机器从产线移除。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器库里的机器短 id
     * @return 命令结果
     */
    public static int remove(ServerPlayer player, String lineRef, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineRef machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        if (!line.removeGroupByMachine(machine.id())) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "这台机器不在产线 " + line.name() + " 里"));
            return 0;
        }
        wakeLine(line.id());
        boolean purged = FactoryBin.purgeIfOrphaned(factory, observations(), machine.id());
        markDirty();
        FactoryMessages.send(player, FactoryMessages.ok(
                "已把机器 [" + machine.shortId() + "] 移出产线 " + line.name()
                        + (purged ? " · 它在回收站且已无引用，已从机器库删除" : "")));
        return 1;
    }

    // ------------------------------------------------------------------
    // manage：输入输出
    // ------------------------------------------------------------------

    /**
     * 打印一台机器在产线里的 IO 配置。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int manageInfo(ServerPlayer player, String lineRef, String machineRef) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineRef machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        String where = machine.dimension().location() + " " + machine.pos().toShortString();
        FactoryMessages.send(player, FactoryMessages.info(
                "产线 " + line.name() + " / 机器 [" + machine.shortId() + "] " + where));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输入: " + describeSpecs(group.inputs()), FactoryMessages.PLAIN));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输出: " + describeSpecs(group.outputs()), FactoryMessages.PLAIN));
        return 1;
    }

    /**
     * 加一条输入规则（物品）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @param face       面名；{@code null} 或 {@code any} 表示不限
     * @return 命令结果
     */
    public static int manageInputAdd(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item, @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(ResourceKey.item(item), parseFace(face), 0L), true);
    }

    /**
     * 加一条输入规则（流体）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @param face       面名
     * @return 命令结果
     */
    public static int manageInputAddFluid(ServerPlayer player, String lineRef, String machineRef,
            String fluidId, @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(parseFluid(fluidId), parseFace(face), 0L), true);
    }

    /**
     * 加一条输入规则（能量）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param face       面名
     * @return 命令结果
     */
    public static int manageInputAddEnergy(ServerPlayer player, String lineRef, String machineRef,
            @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(ResourceKey.ENERGY, parseFace(face), 0L), true);
    }

    /**
     * 移除一条输入规则（物品）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int manageInputRemove(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item) {
        return manageRemove(player, lineRef, machineRef, ResourceKey.item(item), true);
    }

    /**
     * 移除一条输入规则（流体）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @return 命令结果
     */
    public static int manageInputRemoveFluid(ServerPlayer player, String lineRef, String machineRef,
            String fluidId) throws CommandSyntaxException {
        return manageRemove(player, lineRef, machineRef, parseFluid(fluidId), true);
    }

    /**
     * 移除一条输入规则（能量）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int manageInputRemoveEnergy(ServerPlayer player, String lineRef, String machineRef) {
        return manageRemove(player, lineRef, machineRef, ResourceKey.ENERGY, true);
    }

    /**
     * 加一条输出规则（物品）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @param face       面名
     * @return 命令结果
     */
    public static int manageOutputAdd(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item, @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(ResourceKey.item(item), parseFace(face), 0L), false);
    }

    /**
     * 加一条输出规则（流体）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @param face       面名
     * @return 命令结果
     */
    public static int manageOutputAddFluid(ServerPlayer player, String lineRef, String machineRef,
            String fluidId, @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(parseFluid(fluidId), parseFace(face), 0L), false);
    }

    /**
     * 加一条输出规则（能量）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param face       面名
     * @return 命令结果
     */
    public static int manageOutputAddEnergy(ServerPlayer player, String lineRef, String machineRef,
            @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef,
                new ResourceSpec(ResourceKey.ENERGY, parseFace(face), 0L), false);
    }

    /**
     * 移除一条输出规则（物品）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int manageOutputRemove(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item) {
        return manageRemove(player, lineRef, machineRef, ResourceKey.item(item), false);
    }

    /**
     * 移除一条输出规则（流体）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param fluidId    流体注册名
     * @return 命令结果
     */
    public static int manageOutputRemoveFluid(ServerPlayer player, String lineRef, String machineRef,
            String fluidId) throws CommandSyntaxException {
        return manageRemove(player, lineRef, machineRef, parseFluid(fluidId), false);
    }

    /**
     * 移除一条输出规则（能量）。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @return 命令结果
     */
    public static int manageOutputRemoveEnergy(ServerPlayer player, String lineRef, String machineRef) {
        return manageRemove(player, lineRef, machineRef, ResourceKey.ENERGY, false);
    }

    private static int manageAdd(ServerPlayer player, String lineRef, String machineRef,
            ResourceSpec spec, boolean input) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineRef machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        boolean added = input ? group.addInput(spec) : group.addOutput(spec);
        if (!added) {
            FactoryMessages.send(player, FactoryMessages.err(
                    (input ? "输入" : "输出") + "里已经有 " + spec.key().describe() + " 了"));
            return 0;
        }
        markDirty();
        forgetRoutes(line.id(), group.id());
        wakeGroup(line.id(), group.id());
        FactoryMessages.send(player, FactoryMessages.ok(
                "已加" + (input ? "输入" : "输出") + "：" + spec.describe()));
        return 1;
    }

    private static int manageRemove(ServerPlayer player, String lineRef, String machineRef,
            ResourceKey key, boolean input) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineRef machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        boolean removed = input ? group.removeInput(key) : group.removeOutput(key);
        if (!removed) {
            FactoryMessages.send(player, FactoryMessages.err(
                    (input ? "输入" : "输出") + "里没有 " + key.describe()));
            return 0;
        }
        markDirty();
        forgetRoutes(line.id(), group.id());
        wakeGroup(line.id(), group.id());
        FactoryMessages.send(player, FactoryMessages.ok(
                "已移除" + (input ? "输入" : "输出") + "：" + key.describe()));
        return 1;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    @Nullable
    private static Factory requireFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            FactoryMessages.send(player, FactoryMessages.err("Idle Factory 尚未启动（服务端未就绪）"));
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("你还没有工厂"));
            FactoryMessages.send(player, FactoryMessages.info("先执行 /idf create"));
            return null;
        }
        return factory.get();
    }

    @Nullable
    private static PassiveLine requireLine(ServerPlayer player, Factory factory, String ref) {
        Optional<PassiveLine> line = FactoryRefs.line(factory, ref);
        if (line.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("找不到产线 " + ref));
            FactoryMessages.send(player, FactoryMessages.info(
                    "现有 " + factory.lines().size() + " 条，用 /idf line 查看（短 id 或名字都能填）"));
            return null;
        }
        return line.get();
    }

    @Nullable
    private static MachineRef requireMachine(ServerPlayer player, Factory factory, String ref) {
        Optional<MachineRef> target = FactoryRefs.machine(factory, ref);
        if (target.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err("找不到机器 " + ref));
            FactoryMessages.send(player, FactoryMessages.info("用 /idf factory info 查看机器库（短 id 8 位）"));
            return null;
        }
        return target.get();
    }

    @Nullable
    private static IoGroup requireGroup(ServerPlayer player, PassiveLine line, MachineRef machine) {
        Optional<IoGroup> group = line.groupByMachine(machine.id());
        if (group.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "机器 " + machine.shortId() + " 不在产线 " + line.name() + " 里"));
            FactoryMessages.send(player, FactoryMessages.info(
                    "先用 /idf line " + line.shortId() + " add " + machine.shortId()));
            return null;
        }
        return group.get();
    }

    @Nullable
    private static Direction parseFace(@Nullable String face) throws CommandSyntaxException {
        if (face == null || face.isEmpty() || ResourceSpec.ANY.equalsIgnoreCase(face)) {
            return null;
        }
        Direction direction = Direction.byName(face.toLowerCase(Locale.ROOT));
        if (direction == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "未知的面：" + face + "（可用 any/north/south/east/west/up/down）")).create();
        }
        return direction;
    }

    private static ResourceKey parseFluid(String fluidId) throws CommandSyntaxException {
        ResourceLocation id = ResourceLocation.tryParse(fluidId);
        Fluid fluid = id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.get(id);
        if (fluid == Fluids.EMPTY) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "未知流体：" + fluidId)).create();
        }
        return ResourceKey.fluid(fluid);
    }

    private static String describeSpecs(List<ResourceSpec> specs) {
        if (specs.isEmpty()) {
            return "(空)";
        }
        StringBuilder builder = new StringBuilder();
        for (ResourceSpec spec : specs) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(spec.describe());
        }
        return builder.toString();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static void markDirty() {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
    }

    /**
     * 取观测服务；服务端未就绪时为 {@code null}（此时清理逻辑保守不动）。
     *
     * @return 观测服务
     */
    @Nullable
    private static ObservationService observations() {
        FactoryRuntime runtime = FactoryRuntime.get();
        return runtime == null ? null : runtime.observations();
    }

    private static void wakeLine(UUID lineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().wake(lineId);
        }
    }

    private static void wakeGroup(UUID lineId, UUID groupId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().wakeGroup(lineId, groupId);
        }
    }

    /**
     * 丢掉一个组的面路由（规则变了，面可能跟着变）。
     *
     * @param lineId  产线 id
     * @param groupId 组 id
     */
    private static void forgetRoutes(UUID lineId, UUID groupId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().forgetRoutes(lineId, groupId);
        }
    }

    private static void forgetLine(UUID lineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().forget(lineId);
        }
    }
}
