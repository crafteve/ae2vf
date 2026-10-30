package io.github.crafteve.idlefactory.factory.line;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRefs;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

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
    /** 一组机器最多多少台；和机器表上限一致。 */
    public static final int MAX_STACK = 512;

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

    /**
     * 把一组机器的台数改到目标值。
     *
     * <p>组是按「同产线、同方块、同方块实体、同规则」现算出来的，不存盘。扩容从空闲池里
     * 按机器库登记顺序取；新成员没有规则时，会把锚点的规则整套套给它，保证加入后仍然是同组。
     * 缩容按产线登记顺序倒着移（等于撤销最近一次扩容），并且永远保留锚点。</p>
     *
     * <p>服务端夹紧目标值：客户端只传「想变成几台」，实际数量由这里决定。池子不够就按
     * 实际加到的数量回执，不报错——这是常态，不是失败。</p>
     *
     * @param factory    工厂
     * @param lineId     产线 id
     * @param anchorId   组里任意一台机器（界面传锚点）
     * @param targetCount 目标台数；小于 1 按 1 算，大于 {@link #MAX_STACK} 按上限算
     * @return 结果；回执里是实际台数
     */
    public static Result resizeStack(Factory factory, UUID lineId, UUID anchorId, int targetCount) {
        Optional<PassiveLine> lineFound = factory.config().lines().byId(lineId);
        if (lineFound.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = lineFound.get();
        Optional<Machine> anchorFound = factory.config().machines().byId(anchorId);
        if (anchorFound.isEmpty()) {
            return Result.err("找不到机器");
        }
        if (!line.containsMachine(anchorId)) {
            return Result.err("这台机器不在当前产线里");
        }
        ObservationService observations = observations();
        List<UUID> members = FactoryRefs.stackMembers(factory, observations, lineId, anchorId);
        if (members.isEmpty()) {
            return Result.err("找不到这一组机器");
        }
        int current = members.size();
        int target = Math.max(1, Math.min(MAX_STACK, targetCount));
        if (target == current) {
            return Result.ok("这一组已经是 " + current + " 台", lineId);
        }

        Machine anchor = anchorFound.get();
        int changed;
        if (target > current) {
            changed = grow(factory, line, anchor, observations, target - current);
        } else {
            changed = shrink(factory, line, anchorId, members, current - target);
        }
        wakeLine(lineId);
        markDirty();

        int actual = current + changed;
        if (target > current && actual < target) {
            return Result.ok("已扩到 " + actual + " 台 · 空闲的同类机器不够了", lineId);
        }
        return Result.ok((target > current ? "已扩到 " : "已缩到 ") + actual + " 台", lineId);
    }

    /**
     * 把一组机器整体移出产线。
     *
     * <p>机器和规则都留在机器库；移出后它们会变成「空闲组」，可以再被别的产线用。</p>
     *
     * @param factory  工厂
     * @param lineId   产线 id
     * @param anchorId 组里任意一台机器
     * @return 结果；回执里是实际移出的台数
     */
    public static Result removeStack(Factory factory, UUID lineId, UUID anchorId) {
        Optional<PassiveLine> lineFound = factory.config().lines().byId(lineId);
        if (lineFound.isEmpty()) {
            return Result.err("找不到产线");
        }
        PassiveLine line = lineFound.get();
        List<UUID> members = FactoryRefs.stackMembers(factory, observations(), lineId, anchorId);
        if (members.isEmpty()) {
            return Result.err("找不到这一组机器");
        }
        int removed = 0;
        for (UUID memberId : members) {
            if (line.removeMachine(memberId)) {
                forgetRoutes(lineId, memberId);
                removed++;
            }
        }
        wakeLine(lineId);
        markDirty();
        return Result.ok("已把 " + removed + " 台移出产线 " + line.name()
                + " · 机器和规则留在机器库", lineId);
    }

    /**
     * 扩容：从池子里取机器加进产线，并给新成员套用锚点的规则。
     *
     * @param factory      工厂
     * @param line         产线
     * @param anchor       锚点机器，规则来源
     * @param observations 观测服务，可为 {@code null}
     * @param need         需要加几台
     * @return 实际加进来的台数
     */
    private static int grow(Factory factory, PassiveLine line, Machine anchor,
            @Nullable ObservationService observations, int need) {
        int added = 0;
        for (UUID candidateId : FactoryRefs.stackPool(factory, observations, anchor.id())) {
            if (added >= need) {
                break;
            }
            Optional<Machine> candidateFound = factory.config().machines().byId(candidateId);
            if (candidateFound.isEmpty() || !line.addMachine(candidateId)) {
                continue;
            }
            // 新成员先套用本组规则，否则它一加入就因为签名不同而分出去。
            if (!anchor.io().isEmpty()) {
                candidateFound.get().io().copyFrom(anchor.io());
            }
            forgetRoutes(line.id(), candidateId);
            added++;
        }
        return added;
    }

    /**
     * 缩容：从产线登记顺序的末尾倒着移出，永远跳过锚点。
     *
     * @param factory  工厂
     * @param line     产线
     * @param anchorId 锚点机器 id，不会被移出
     * @param members  当前成员（产线登记顺序）
     * @param need     需要移出几台
     * @return 实际移出的台数（负数）
     */
    private static int shrink(Factory factory, PassiveLine line, UUID anchorId,
            List<UUID> members, int need) {
        int removed = 0;
        for (int i = members.size() - 1; i >= 0 && removed < need; i--) {
            UUID memberId = members.get(i);
            if (memberId.equals(anchorId)) {
                continue;
            }
            if (line.removeMachine(memberId)) {
                forgetRoutes(line.id(), memberId);
                removed++;
            }
        }
        return -removed;
    }

    /**
     * 当前观测服务；服务端还没启动时是 {@code null}，分组会退化成「只看规则」。
     *
     * @return 观测服务或 {@code null}
     */
    @Nullable
    private static ObservationService observations() {
        FactoryRuntime runtime = FactoryRuntime.get();
        return runtime == null ? null : runtime.observations();
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
