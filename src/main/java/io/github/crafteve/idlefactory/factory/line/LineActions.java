package io.github.crafteve.idlefactory.factory.line;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluids;

/**
 * 被动产线的写操作层：指令和界面共用。
 *
 * <p>它只做「校验 + 改配置 + 标记存档 + 唤醒执行器」，不直接发聊天栏消息，也不依赖菜单/网络。
 * 返回值里的 {@link Result#message()} 是给玩家看的短句，命令侧用 {@code FactoryMessages} 包一层，
 * 界面侧放进反馈包。</p>
 *
 * <p>产线只存机器引用；IO 规则在机器上。把机器移出产线只删引用，不删规则。</p>
 */
public final class LineActions {
    /** 一个工厂最多的产线数；和快照包的上限保持一致。 */
    public static final int MAX_LINES = 64;
    /** 产线名最大字符数；命令按空格分词，所以名字里不能有空白。 */
    public static final int MAX_NAME_LENGTH = 32;
    /** 每侧规则上限；和网络包、AGENTS 的规模约束一致。 */
    public static final int MAX_RULES_PER_SIDE = 16;

    private LineActions() {
        // 纯静态动作层
    }

    /**
     * 一次写操作的结果。
     *
     * @param ok      是否成功
     * @param message 给玩家看的一句话
     * @param lineId  受影响的产线 id；新建时是新线 id，失败时为 {@code null}
     */
    public record Result(boolean ok, String message, @Nullable UUID lineId) {
        /**
         * 成功结果。
         *
         * @param message 文案
         * @param lineId  受影响的产线
         * @return 结果
         */
        public static Result ok(String message, @Nullable UUID lineId) {
            return new Result(true, message, lineId);
        }

        /**
         * 失败结果。
         *
         * @param message 文案
         * @return 结果
         */
        public static Result err(String message) {
            return new Result(false, message, null);
        }
    }

    // ------------------------------------------------------------------
    // 产线
    // ------------------------------------------------------------------

    /**
     * 新建一条产线。
     *
     * @param factory 工厂
     * @param rawName 玩家输入的名字
     * @return 结果；成功时带新产线 id
     */
    public static Result create(Factory factory, String rawName) {
        String name = trim(rawName);
        String error = validateName(factory, name, null);
        if (error != null) {
            return Result.err(error);
        }
        if (factory.config().lines().size() >= MAX_LINES) {
            return Result.err("产线数量已达上限 " + MAX_LINES + " 条");
        }
        PassiveLine line = new PassiveLine(UUID.randomUUID(), name);
        factory.config().lines().add(line);
        markDirty();
        return Result.ok("已创建产线 [" + line.shortId() + "] " + name, line.id());
    }

    /**
     * 只改产线名，id 和机器引用都不动。
     *
     * @param factory 工厂
     * @param lineId  产线 id
     * @param rawName 新名字
     * @return 结果
     */
    public static Result rename(Factory factory, UUID lineId, String rawName) {
        Optional<PassiveLine> found = factory.config().lines().byId(lineId);
        if (found.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = found.get();
        String name = trim(rawName);
        String error = validateName(factory, name, lineId);
        if (error != null) {
            return Result.err(error);
        }
        if (line.name().equals(name)) {
            return Result.ok("产线名未变：" + name, lineId);
        }
        line.rename(name);
        markDirty();
        return Result.ok("已把产线 [" + line.shortId() + "] 重命名为 " + name, lineId);
    }

    /**
     * 删除一条产线。只删产线自己的引用，机器和规则留在机器库。
     *
     * @param factory 工厂
     * @param lineId  产线 id
     * @return 结果
     */
    public static Result delete(Factory factory, UUID lineId) {
        Optional<PassiveLine> found = factory.config().lines().byId(lineId);
        if (found.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = found.get();
        String label = "[" + line.shortId() + "] " + line.name();
        factory.config().lines().remove(lineId);
        forgetLine(lineId);
        markDirty();
        return Result.ok("已删除产线 " + label + " · 机器和规则留在机器库", lineId);
    }

    /**
     * 启动 / 停止一条产线。
     *
     * @param factory 工厂
     * @param lineId  产线 id
     * @param running 目标状态
     * @return 结果
     */
    public static Result setRunning(Factory factory, UUID lineId, boolean running) {
        Optional<PassiveLine> found = factory.config().lines().byId(lineId);
        if (found.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = found.get();
        line.setRunning(running);
        wakeLine(line.id());
        markDirty();
        return Result.ok("产线 [" + line.shortId() + "] " + line.name()
                + " → " + (running ? "运行" : "停止"), lineId);
    }

    // ------------------------------------------------------------------
    // 机器
    // ------------------------------------------------------------------

    /**
     * 把机器加入产线；机器已在别的产线时拒绝（指令的 {@code line add} 语义）。
     *
     * @param factory   工厂
     * @param lineId    目标产线 id
     * @param machineId 机器 id
     * @return 结果
     */
    public static Result addMachine(Factory factory, UUID lineId, UUID machineId) {
        Optional<PassiveLine> targetFound = factory.config().lines().byId(lineId);
        if (targetFound.isEmpty()) {
            return Result.err("找不到产线");
        }
        Optional<Machine> machineFound = factory.config().machines().byId(machineId);
        if (machineFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        Machine machine = machineFound.get();
        PassiveLine target = targetFound.get();
        if (target.containsMachine(machineId)) {
            return Result.err("这台机器已经在产线 " + target.name() + " 里了");
        }
        Optional<PassiveLine> other = FactoryRefs.lineOf(factory, machineId);
        if (other.isPresent()) {
            return Result.err("这台机器已经属于产线 " + other.get().name() + "，先从原产线移出");
        }
        if (!target.addMachine(machineId)) {
            return Result.err("这台机器已经在产线 " + target.name() + " 里了");
        }
        wakeLine(target.id());
        markDirty();
        return Result.ok("已把机器 [" + machine.ref().shortId() + "] 加入产线 " + target.name(),
                target.id());
    }

    /**
     * 把机器放到产线里；机器已在别的产线时按“移动”处理，规则留在机器上。
     *
     * @param factory   工厂
     * @param lineId    目标产线 id
     * @param machineId 机器 id
     * @return 结果
     */
    public static Result placeMachine(Factory factory, UUID lineId, UUID machineId) {
        Optional<PassiveLine> targetFound = factory.config().lines().byId(lineId);
        if (targetFound.isEmpty()) {
            return Result.err("找不到产线");
        }
        Optional<Machine> machineFound = factory.config().machines().byId(machineId);
        if (machineFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        Machine machine = machineFound.get();
        PassiveLine target = targetFound.get();
        if (target.containsMachine(machineId)) {
            return Result.err("机器 [" + machine.ref().shortId() + "] 已在本产线");
        }

        Optional<PassiveLine> currentFound = FactoryRefs.lineOf(factory, machineId);
        if (currentFound.isPresent()) {
            PassiveLine from = currentFound.get();
            from.removeMachine(machineId);
            if (!target.addMachine(machineId)) {
                // 理论上到不了这里；回滚，避免机器在两条线里都没有。
                from.addMachine(machineId);
                return Result.err("目标产线已有这台机器");
            }
            forgetRoutes(from.id(), machineId);
            forgetRoutes(target.id(), machineId);
            wakeLine(from.id());
            wakeLine(target.id());
            markDirty();
            return Result.ok("已把机器 [" + machine.ref().shortId() + "] 从产线 " + from.name()
                    + " 移到 " + target.name() + "（IO 规则保留）", target.id());
        }

        if (!target.addMachine(machineId)) {
            return Result.err("目标产线已有这台机器");
        }
        wakeLine(target.id());
        markDirty();
        return Result.ok("已把机器 [" + machine.ref().shortId() + "] 加入产线 " + target.name(),
                target.id());
    }

    /**
     * 把机器从产线移出；机器和规则留在机器库。
     *
     * @param factory   工厂
     * @param lineId    产线 id
     * @param machineId 机器 id
     * @return 结果
     */
    public static Result removeMachine(Factory factory, UUID lineId, UUID machineId) {
        Optional<PassiveLine> found = factory.config().lines().byId(lineId);
        if (found.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = found.get();
        Optional<Machine> machineFound = factory.config().machines().byId(machineId);
        if (machineFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        if (!line.removeMachine(machineId)) {
            return Result.err("机器不在产线 " + line.name() + " 里");
        }
        wakeLine(line.id());
        markDirty();
        return Result.ok("已把机器 [" + machineFound.get().ref().shortId() + "] 移出产线 "
                + line.name() + " · 机器和规则留在机器库", lineId);
    }

    // ------------------------------------------------------------------
    // IO 规则
    // ------------------------------------------------------------------

    /**
     * 加一条输入/输出规则。
     *
     * @param factory   工厂
     * @param lineId    产线 id
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       资源
     * @param face      面；{@code null} 表示不限
     * @param mode      匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static Result addRule(Factory factory, UUID lineId, UUID machineId, IoSide side,
            ResourceKey key, @Nullable Direction face, MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        String resourceError = validateResource(key);
        if (resourceError != null) {
            return Result.err(resourceError);
        }
        List<ResourceSpec> rules = rules(io, side);
        if (rules.size() >= MAX_RULES_PER_SIDE) {
            return Result.err(sideName(side) + "规则最多 " + MAX_RULES_PER_SIDE + " 条");
        }
        if (find(rules, key) != null) {
            return Result.err(sideName(side) + "里已经有 " + key.describe() + " 了");
        }
        ResourceSpec spec = new ResourceSpec(key, face, 0L, mode);
        boolean added = side == IoSide.INPUT ? io.addInput(spec) : io.addOutput(spec);
        if (!added) {
            return Result.err(sideName(side) + "里已经有 " + key.describe() + " 了");
        }
        afterRuleChange(factory, machineId);
        return Result.ok("已加" + sideName(side) + "：" + spec.describe(), lineId);
    }

    /**
     * 原地更新一条规则的面 / 匹配模式，规则身份（资源 key）不变。
     *
     * @param factory   工厂
     * @param lineId    产线 id
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @param face      新面；{@code null} 表示不限
     * @param mode      新匹配模式；非物品会被规范化成精确
     * @return 结果
     */
    public static Result updateRule(Factory factory, UUID lineId, UUID machineId, IoSide side,
            ResourceKey key, @Nullable Direction face, MatchMode mode) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        ResourceSpec current = find(rules(io, side), key);
        if (current == null) {
            return Result.err(sideName(side) + "里没有 " + key.describe());
        }
        ResourceSpec replacement = new ResourceSpec(key, face, current.amount(),
                mode == null ? current.mode() : mode);
        boolean replaced = side == IoSide.INPUT
                ? io.replaceInput(key, replacement)
                : io.replaceOutput(key, replacement);
        if (!replaced) {
            return Result.err(sideName(side) + "里没有 " + key.describe());
        }
        afterRuleChange(factory, machineId);
        return Result.ok("已更新" + sideName(side) + "：" + replacement.describe(), lineId);
    }

    /**
     * 删除一条规则。
     *
     * @param factory   工厂
     * @param lineId    产线 id
     * @param machineId 机器 id
     * @param side      输入 / 输出
     * @param key       规则 key
     * @return 结果
     */
    public static Result removeRule(Factory factory, UUID lineId, UUID machineId, IoSide side,
            ResourceKey key) {
        Optional<MachineIoGroup> ioFound = requireIo(factory, machineId);
        if (ioFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        MachineIoGroup io = ioFound.get();
        boolean removed = side == IoSide.INPUT
                ? io.removeInput(key)
                : io.removeOutput(key);
        if (!removed) {
            return Result.err(sideName(side) + "里没有 " + key.describe());
        }
        afterRuleChange(factory, machineId);
        return Result.ok("已移除" + sideName(side) + "：" + key.describe(), lineId);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static String trim(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * 校验产线名；空串返回错误文案。
     *
     * @param factory 工厂
     * @param name    已 trim 的名字
     * @param exclude 重命名时排除自身；新建传 {@code null}
     * @return 错误文案；合法时为 {@code null}
     */
    @Nullable
    private static String validateName(Factory factory, String name, @Nullable UUID exclude) {
        if (name.isEmpty()) {
            return "产线名不能为空";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "产线名最多 " + MAX_NAME_LENGTH + " 个字符";
        }
        if (name.chars().anyMatch(ch -> Character.isWhitespace(ch) || Character.isISOControl(ch))) {
            return "产线名不能包含空白或控制字符";
        }
        if (FactoryRefs.isReservedName(name)) {
            return "「" + name + "」是保留字（" + FactoryRefs.describeReservedNames() + "），换个名字";
        }
        for (PassiveLine line : factory.config().lines().all()) {
            if (!line.id().equals(exclude) && line.name().equals(name)) {
                return "已有同名产线：" + name;
            }
        }
        return null;
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

    private static void afterRuleChange(Factory factory, UUID machineId) {
        markDirty();
        FactoryRefs.lineOf(factory, machineId).ifPresent(line -> {
            forgetRoutes(line.id(), machineId);
            wakeGroup(line.id(), machineId);
        });
    }

    private static void markDirty() {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
    }

    private static void wakeLine(UUID lineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().wake(lineId);
        }
    }

    private static void wakeGroup(UUID lineId, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().wakeGroup(lineId, machineId);
        }
    }

    private static void forgetRoutes(UUID lineId, UUID machineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().forgetRoutes(lineId, machineId);
        }
    }

    private static void forgetLine(UUID lineId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.lineRunner().forget(lineId);
        }
    }
}
