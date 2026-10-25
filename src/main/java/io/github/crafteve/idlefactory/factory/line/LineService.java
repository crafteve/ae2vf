package io.github.crafteve.idlefactory.factory.line;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.target.MachineTarget;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

/**
 * 被动产线的指令逻辑。
 *
 * <p>第一版只做「管理」：产线、IO 组、输入输出规则。不执行搬运，{@code start} / {@code end}
 * 只改开关，真正的 IO 血肉下一版再填。</p>
 */
public final class LineService {
    private LineService() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // 产线
    // ------------------------------------------------------------------

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
            msg(player, "产线名不能为空");
            return 0;
        }
        if (factory.lines().byName(trimmed).isPresent()) {
            msg(player, "已有同名产线：" + trimmed);
            return 0;
        }
        PassiveLine line = new PassiveLine(UUID.randomUUID(), trimmed);
        factory.lines().add(line);
        markDirty();
        msg(player, "已创建产线 [" + line.shortId() + "] " + trimmed);
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
        factory.lines().remove(line.id());
        markDirty();
        msg(player, "已删除产线 [" + line.shortId() + "] " + line.name());
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
        markDirty();
        msg(player, "产线 [" + line.shortId() + "] " + line.name() + " → " + (running ? "running" : "stopped"));
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
        msg(player, "产线 [" + line.shortId() + "] " + line.name()
                + " | " + (line.running() ? "running" : "stopped")
                + " | 机器 " + line.groups().size() + " 台");
        for (IoGroup group : line.groups()) {
            String machineText = factory.machines().byId(group.machineId())
                    .map(target -> "[" + target.shortId() + "] " + target.dimension().location()
                            + " " + target.pos().toShortString())
                    .orElse("[缺失] " + group.machineId());
            msg(player, "  " + machineText);
            msg(player, "    输入: " + describeSpecs(group.inputs()));
            msg(player, "    输出: " + describeSpecs(group.outputs()));
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
        MachineTarget machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        if (line.groupByMachine(machine.id()).isPresent()) {
            msg(player, "这台机器已经在产线 " + line.name() + " 里了");
            return 0;
        }
        for (PassiveLine other : factory.lines().all()) {
            if (!other.id().equals(line.id()) && other.groupByMachine(machine.id()).isPresent()) {
                msg(player, "这台机器已经属于产线 " + other.name());
                return 0;
            }
        }
        line.addGroup(new IoGroup(UUID.randomUUID(), machine.id()));
        markDirty();
        msg(player, "已把机器 [" + machine.shortId() + "] 加入产线 " + line.name());
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
        MachineTarget machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        if (!line.removeGroupByMachine(machine.id())) {
            msg(player, "这台机器不在产线 " + line.name() + " 里");
            return 0;
        }
        markDirty();
        msg(player, "已把机器 [" + machine.shortId() + "] 移出产线 " + line.name());
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
        MachineTarget machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        msg(player, "产线 " + line.name() + " / 机器 [" + machine.shortId() + "] "
                + machine.dimension().location() + " " + machine.pos().toShortString());
        msg(player, "  输入: " + describeSpecs(group.inputs()));
        msg(player, "  输出: " + describeSpecs(group.outputs()));
        return 1;
    }

    /**
     * 加一条输入规则。
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
        return manageAdd(player, lineRef, machineRef, item, parseFace(face), true);
    }

    /**
     * 移除一条输入规则。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int manageInputRemove(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item) {
        return manageRemove(player, lineRef, machineRef, item, true);
    }

    /**
     * 加一条输出规则。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @param face       面名；{@code null} 或 {@code any} 表示不限
     * @return 命令结果
     */
    public static int manageOutputAdd(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item, @Nullable String face) throws CommandSyntaxException {
        return manageAdd(player, lineRef, machineRef, item, parseFace(face), false);
    }

    /**
     * 移除一条输出规则。
     *
     * @param player     发起请求的玩家
     * @param lineRef    产线短 id 或名字
     * @param machineRef 机器短 id
     * @param item       物品样本
     * @return 命令结果
     */
    public static int manageOutputRemove(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item) {
        return manageRemove(player, lineRef, machineRef, item, false);
    }

    private static int manageAdd(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item, @Nullable Direction face, boolean input) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineTarget machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        String itemId = itemId(item);
        ItemSpec spec = new ItemSpec(itemId, face);
        boolean added = input ? group.addInput(spec) : group.addOutput(spec);
        if (!added) {
            msg(player, (input ? "输入" : "输出") + "里已经有 " + itemId + " 了");
            return 0;
        }
        markDirty();
        msg(player, "已加" + (input ? "输入" : "输出") + "：" + itemId
                + " face=" + spec.faceName());
        return 1;
    }

    private static int manageRemove(ServerPlayer player, String lineRef, String machineRef,
            ItemStack item, boolean input) {
        Factory factory = requireFactory(player);
        if (factory == null) {
            return 0;
        }
        PassiveLine line = requireLine(player, factory, lineRef);
        if (line == null) {
            return 0;
        }
        MachineTarget machine = requireMachine(player, factory, machineRef);
        if (machine == null) {
            return 0;
        }
        IoGroup group = requireGroup(player, line, machine);
        if (group == null) {
            return 0;
        }
        String itemId = itemId(item);
        boolean removed = input ? group.removeInput(itemId) : group.removeOutput(itemId);
        if (!removed) {
            msg(player, (input ? "输入" : "输出") + "里没有 " + itemId);
            return 0;
        }
        markDirty();
        msg(player, "已移除" + (input ? "输入" : "输出") + "：" + itemId);
        return 1;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    @Nullable
    private static Factory requireFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            msg(player, "Idle Factory 尚未启动（服务端未就绪）");
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            msg(player, "你还没有工厂，先执行 /idf create");
            return null;
        }
        return factory.get();
    }

    @Nullable
    private static PassiveLine requireLine(ServerPlayer player, Factory factory, String ref) {
        Optional<PassiveLine> line = factory.lines().byShortId(ref);
        if (line.isEmpty()) {
            line = factory.lines().byName(ref);
        }
        if (line.isEmpty()) {
            msg(player, "找不到产线 " + ref + "（用短 id 或名字）");
            return null;
        }
        return line.get();
    }

    @Nullable
    private static MachineTarget requireMachine(ServerPlayer player, Factory factory, String ref) {
        Optional<MachineTarget> target = factory.machines().byShortId(ref);
        if (target.isEmpty()) {
            msg(player, "找不到机器 " + ref + "（先用 /idf factory connect 接入）");
            return null;
        }
        return target.get();
    }

    @Nullable
    private static IoGroup requireGroup(ServerPlayer player, PassiveLine line, MachineTarget machine) {
        Optional<IoGroup> group = line.groupByMachine(machine.id());
        if (group.isEmpty()) {
            msg(player, "这台机器不在产线 " + line.name() + " 里（先用 /idf line add）");
            return null;
        }
        return group.get();
    }

    @Nullable
    private static Direction parseFace(@Nullable String face) throws CommandSyntaxException {
        if (face == null || face.isEmpty() || ItemSpec.ANY.equalsIgnoreCase(face)) {
            return null;
        }
        Direction direction = Direction.byName(face.toLowerCase(Locale.ROOT));
        if (direction == null) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "未知的面：" + face + "（可用 any/north/south/east/west/up/down）")).create();
        }
        return direction;
    }

    private static String describeSpecs(List<ItemSpec> specs) {
        if (specs.isEmpty()) {
            return "(空)";
        }
        StringBuilder builder = new StringBuilder();
        for (ItemSpec spec : specs) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(spec.itemId()).append('[').append(spec.faceName()).append(']');
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

    private static void msg(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
