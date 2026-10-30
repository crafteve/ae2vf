package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineIoGroup;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

import net.minecraft.resources.ResourceLocation;

/**
 * 产线 / 机器的引用解析。
 *
 * <p>命令和 Tab 补全共用这一套：<b>只回答「找没找到」，不发消息</b>。补全时按 Tab 不该被灌错误
 * 消息，所以解析必须是静默的；要不要提示由调用方决定。</p>
 *
 * <p>产线引用按「短 id → 名字」的顺序解析；机器只有短 id。</p>
 */
public final class FactoryRefs {
    /**
     * 产线名的保留字。
     *
     * <p>Brigadier 解析时字面量优先于参数，所以一条叫 {@code create} 的产线永远进不去
     * {@code /idf line create}。建线时直接拒绝这些名字，比事后猜「为什么这条线点不到」便宜。</p>
     */
    private static final List<String> RESERVED_NAMES = List.of(
            "create", "delete", "info", "start", "end", "add", "remove", "manage", "list",
            "rename");

    private FactoryRefs() {
        // 纯静态工具类
    }

    /**
     * 按短 id 或名字找产线。
     *
     * @param factory 工厂，可为 {@code null}
     * @param ref     引用，可为 {@code null}
     * @return 产线；找不到或引用为空时为空
     */
    public static Optional<PassiveLine> line(@Nullable Factory factory, @Nullable String ref) {
        if (factory == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<PassiveLine> byShortId = factory.config().lines().byShortId(ref);
        return byShortId.isPresent() ? byShortId : factory.config().lines().byName(ref);
    }

    /**
     * 按短 id 找机器身份。
     *
     * @param factory 工厂，可为 {@code null}
     * @param ref     短 id，可为 {@code null}
     * @return 机器身份；找不到或前缀有歧义时为空
     */
    public static Optional<MachineRef> machine(@Nullable Factory factory, @Nullable String ref) {
        if (factory == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return factory.config().machines().byShortId(ref).map(Machine::ref);
    }

    /**
     * 找一台机器所属的产线（一台机器最多属于一条产线）。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 产线；没编线时为空
     */
    public static Optional<PassiveLine> lineOf(Factory factory, UUID machineId) {
        for (PassiveLine line : factory.config().lines().all()) {
            if (line.containsMachine(machineId)) {
                return Optional.of(line);
            }
        }
        return Optional.empty();
    }

    /**
     * 名字是不是保留字。
     *
     * @param name 产线名
     * @return 是保留字时为 {@code true}
     */
    public static boolean isReservedName(String name) {
        for (String reserved : RESERVED_NAMES) {
            if (reserved.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** 保留字清单，用来拼提示。 */
    public static String describeReservedNames() {
        return String.join(" / ", RESERVED_NAMES);
    }

    /**
     * 已经属于某条产线的机器 id 集合。
     *
     * <p>补全 {@code line X add} 时要把这些排除掉——一台机器只能属于一条产线，列出来只会让玩家
     * 选中后被拒。</p>
     *
     * @param factory 工厂
     * @return 已分配的机器 id
     */
    public static Set<UUID> assignedMachines(Factory factory) {
        Set<UUID> assigned = new HashSet<>();
        for (PassiveLine line : factory.config().lines().all()) {
            assigned.addAll(line.machineIds());
        }
        return assigned;
    }

    /**
     * 一台机器挂在哪些产线上（按产线登记顺序）。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 产线名列表；没挂任何产线时为空
     */
    public static List<String> lineNamesOf(Factory factory, UUID machineId) {
        List<String> names = new ArrayList<>();
        for (PassiveLine line : factory.config().lines().all()) {
            if (line.containsMachine(machineId)) {
                names.add(line.name());
            }
        }
        return names;
    }

    /**
     * 一台机器挂在哪些产线上（按产线登记顺序），给界面按 id 精确筛选。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 产线 id 列表；与 {@link #lineNamesOf} 同序
     */
    public static List<UUID> lineIdsOf(Factory factory, UUID machineId) {
        List<UUID> ids = new ArrayList<>();
        for (PassiveLine line : factory.config().lines().all()) {
            if (line.containsMachine(machineId)) {
                ids.add(line.id());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // ---- 机器组（界面堆叠行） ----
    // ------------------------------------------------------------------

    /** 分组身份：能不能并成一行，只看这三样。 */
    private record GroupIdentity(@Nullable ResourceLocation blockId,
            @Nullable ResourceLocation beTypeId, MachineIoGroup.Signature signature) {
    }

    /**
     * 取一台机器的分组身份。
     *
     * <p>方块 id 用观测到的值；未加载时回退到「最后一次看到的」，否则玩家走远一步，
     * 界面上的组会全部散成未知组。规则签名从机器自己的规则现算，不缓存。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时方块 id 视为未知
     * @param machineId    机器 id
     * @return 身份；机器不在库里时为空
     */
    private static Optional<GroupIdentity> identityOf(Factory factory,
            @Nullable ObservationService observations, UUID machineId) {
        Optional<Machine> found = factory.config().machines().byId(machineId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Machine machine = found.get();
        Observation observation = observations == null
                ? Observation.UNKNOWN : observations.observe(machine.ref().blockKey());
        ResourceLocation blockId = observation.blockId() != null
                ? observation.blockId() : observation.lastBlockId();
        return Optional.of(new GroupIdentity(blockId, observation.beTypeId(),
                machine.io().signature()));
    }

    /**
     * 一条产线里和锚点同组的机器 id。
     *
     * <p>同组 = 同产线 + 同方块 + 同方块实体类型 + 同规则签名。结果保持产线登记顺序，
     * 锚点自己在列表里（调用方保证锚点属于这条线）。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器方块 id 视为未知
     * @param lineId       产线 id
     * @param anchorId     锚点机器 id
     * @return 成员 id；产线或锚点找不到时为空
     */
    public static List<UUID> stackMembers(Factory factory, @Nullable ObservationService observations,
            UUID lineId, UUID anchorId) {
        Optional<PassiveLine> lineFound = factory.config().lines().byId(lineId);
        if (lineFound.isEmpty()) {
            return List.of();
        }
        Optional<GroupIdentity> anchor = identityOf(factory, observations, anchorId);
        if (anchor.isEmpty()) {
            return List.of();
        }
        List<UUID> members = new ArrayList<>();
        for (UUID machineId : lineFound.get().machineIds()) {
            Optional<GroupIdentity> identity = identityOf(factory, observations, machineId);
            if (identity.isPresent() && identity.get().equals(anchor.get())) {
                members.add(machineId);
            }
        }
        return members;
    }

    /**
     * 没有编线的、和锚点完全同组的机器。
     *
     * <p>和 {@link #stackPool} 的区别是签名必须严格相同：池子允许「没有规则的空机器」，
     * 那是为了扩容套用规则；这里不允许，因为整组删除不能把别的空机器一起删掉。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器方块 id 视为未知
     * @param anchorId     锚点机器 id
     * @return 成员 id，不含锚点自己
     */
    public static List<UUID> idleStackMembers(Factory factory,
            @Nullable ObservationService observations, UUID anchorId) {
        Optional<GroupIdentity> anchor = identityOf(factory, observations, anchorId);
        if (anchor.isEmpty()) {
            return List.of();
        }
        Set<UUID> assigned = assignedMachines(factory);
        List<UUID> members = new ArrayList<>();
        for (Machine machine : factory.config().machines().all()) {
            UUID machineId = machine.id();
            if (machineId.equals(anchorId) || assigned.contains(machineId)) {
                continue;
            }
            Optional<GroupIdentity> identity = identityOf(factory, observations, machineId);
            if (identity.isPresent() && identity.get().equals(anchor.get())) {
                members.add(machineId);
            }
        }
        return members;
    }

    /**
     * 可以并进这一组的空闲机器，按机器库登记顺序。
     *
     * <p>条件：不在任何产线、同方块、同方块实体类型，且「没有规则」或者「规则和本组完全一样」。
     * 允许「没有规则」是因为扩容时会把本组规则套给新成员；不允许「有别的规则」是因为那会让
     * 它加入后立刻分叉成两组。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时所有机器方块 id 视为未知
     * @param anchorId     锚点机器 id
     * @return 候选机器 id；锚点找不到时为空
     */
    public static List<UUID> stackPool(Factory factory, @Nullable ObservationService observations,
            UUID anchorId) {
        Optional<GroupIdentity> anchor = identityOf(factory, observations, anchorId);
        if (anchor.isEmpty()) {
            return List.of();
        }
        Set<UUID> assigned = assignedMachines(factory);
        List<UUID> pool = new ArrayList<>();
        for (Machine machine : factory.config().machines().all()) {
            UUID machineId = machine.id();
            if (assigned.contains(machineId)) {
                continue;
            }
            Optional<GroupIdentity> identity = identityOf(factory, observations, machineId);
            if (identity.isEmpty()) {
                continue;
            }
            GroupIdentity candidate = identity.get();
            if (!Objects.equals(candidate.blockId(), anchor.get().blockId())
                    || !Objects.equals(candidate.beTypeId(), anchor.get().beTypeId())) {
                continue;
            }
            if (!machine.io().isEmpty() && !candidate.signature().equals(anchor.get().signature())) {
                continue;
            }
            pool.add(machineId);
        }
        return pool;
    }
}
