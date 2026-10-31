package io.github.crafteve.idlefactory.factory.line;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryMessages;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.MachineActions;
import io.github.crafteve.idlefactory.factory.io.IoSide;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
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
        List<PassiveLine> lines = factory.config().lines().all();
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
                    .append(FactoryMessages.text(" · " + line.machineCount() + " 台", FactoryMessages.INFO));
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
        return send(player, LineActions.create(factory, name));
    }

    /**
     * 只改产线名，id 和机器/规则都不动。
     *
     * @param player  发起请求的玩家
     * @param lineRef 短 id 或名字
     * @param name    新名字
     * @return 命令结果
     */
    public static int rename(ServerPlayer player, String lineRef, String name) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        return send(player, LineActions.rename(factory, line.id(), name));
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
        return send(player, LineActions.delete(factory, line.id()));
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
        return send(player, LineActions.setRunning(factory, line.id(), running));
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
                .append(FactoryMessages.text(" · " + line.machineCount() + " 台", FactoryMessages.INFO));
        FactoryMessages.send(player, head);

        for (UUID machineId : line.machineIds()) {
            Optional<Machine> target = factory.config().machines().byId(machineId);
            if (target.isEmpty()) {
                FactoryMessages.send(player, FactoryMessages.err(
                        "  机器 " + machineId + " 已不在机器库"));
                continue;
            }
            Machine machineEntry = target.get();
            MachineRef machine = machineEntry.ref();
            String where = machine.dimension().location() + " " + machine.pos().toShortString();
            MutableComponent row = Component.empty()
                    .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                    .append(FactoryMessages.hover(machine.shortId(), where))
                    .append(FactoryMessages.text(" " + where, FactoryMessages.INFO));
            FactoryMessages.send(player, row);
            FactoryMessages.send(player, FactoryMessages.text(
                    "    输入: " + describeSpecs(machineEntry.io().inputs()), FactoryMessages.PLAIN));
            FactoryMessages.send(player, FactoryMessages.text(
                    "    输出: " + describeSpecs(machineEntry.io().outputs()), FactoryMessages.PLAIN));
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
        return send(player, LineActions.addMachine(factory, line.id(), machine.id()));
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
        return send(player, LineActions.removeMachine(factory, line.id(), machine.id()));
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
        MachineIoGroup io = requireIo(player, factory, line, machine);
        if (io == null) {
            return 0;
        }
        String where = machine.dimension().location() + " " + machine.pos().toShortString();
        FactoryMessages.send(player, FactoryMessages.info(
                "产线 " + line.name() + " / 机器 [" + machine.shortId() + "] " + where));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输入: " + describeSpecs(io.inputs()), FactoryMessages.PLAIN));
        FactoryMessages.send(player, FactoryMessages.text(
                "  输出: " + describeSpecs(io.outputs()), FactoryMessages.PLAIN));
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
        return manageAdd(player, lineRef, machineRef, ResourceKey.item(item), face, IoSide.INPUT);
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
        return manageAdd(player, lineRef, machineRef, parseFluid(fluidId), face, IoSide.INPUT);
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
        return manageAdd(player, lineRef, machineRef, ResourceKey.ENERGY, face, IoSide.INPUT);
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
        return manageRemove(player, lineRef, machineRef, ResourceKey.item(item), IoSide.INPUT);
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
        return manageRemove(player, lineRef, machineRef, parseFluid(fluidId), IoSide.INPUT);
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
        return manageRemove(player, lineRef, machineRef, ResourceKey.ENERGY, IoSide.INPUT);
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
        return manageAdd(player, lineRef, machineRef, ResourceKey.item(item), face, IoSide.OUTPUT);
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
        return manageAdd(player, lineRef, machineRef, parseFluid(fluidId), face, IoSide.OUTPUT);
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
        return manageAdd(player, lineRef, machineRef, ResourceKey.ENERGY, face, IoSide.OUTPUT);
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
        return manageRemove(player, lineRef, machineRef, ResourceKey.item(item), IoSide.OUTPUT);
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
        return manageRemove(player, lineRef, machineRef, parseFluid(fluidId), IoSide.OUTPUT);
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
        return manageRemove(player, lineRef, machineRef, ResourceKey.ENERGY, IoSide.OUTPUT);
    }

    private static int manageAdd(ServerPlayer player, String lineRef, String machineRef,
            ResourceKey key, @Nullable String face, IoSide side) throws CommandSyntaxException {
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
        MachineIoGroup io = requireIo(player, factory, line, machine);
        if (io == null) {
            return 0;
        }
        // mode 传 null = 沿用旧命令默认（无组件物品模糊），界面会显式传精确。
        return send(player, MachineActions.addRule(factory, line.id(), machine.id(), side,
                key, parseFace(face), null));
    }

    private static int manageRemove(ServerPlayer player, String lineRef, String machineRef,
            ResourceKey key, IoSide side) {
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
        MachineIoGroup io = requireIo(player, factory, line, machine);
        if (io == null) {
            return 0;
        }
        return send(player, MachineActions.removeRule(factory, line.id(), machine.id(), side, key));
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
                    "现有 " + factory.config().lines().size() + " 条，用 /idf line 查看（短 id 或名字都能填）"));
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
    private static MachineIoGroup requireIo(ServerPlayer player, Factory factory, PassiveLine line,
            MachineRef machine) {
        if (!line.containsMachine(machine.id())) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "机器 " + machine.shortId() + " 不在产线 " + line.name() + " 里"));
            FactoryMessages.send(player, FactoryMessages.info(
                    "先用 /idf line " + line.shortId() + " add " + machine.shortId()));
            return null;
        }
        Optional<Machine> entry = factory.config().machines().byId(machine.id());
        if (entry.isEmpty()) {
            FactoryMessages.send(player, FactoryMessages.err(
                    "机器 " + machine.shortId() + " 不在机器库"));
            return null;
        }
        return entry.get().io();
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

    /**
     * 把动作层的结果转成聊天栏消息。
     *
     * @param player 接收者
     * @param result 动作结果
     * @return 命令返回值：成功 {@code 1}，失败 {@code 0}
     */
    private static int send(ServerPlayer player, LineActions.Result result) {
        FactoryMessages.send(player, result.ok()
                ? FactoryMessages.ok(result.message())
                : FactoryMessages.err(result.message()));
        return result.ok() ? 1 : 0;
    }
}
