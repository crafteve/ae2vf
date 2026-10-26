package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

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
            "create", "delete", "info", "start", "end", "add", "remove", "manage", "list");

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
        Optional<PassiveLine> byShortId = factory.lines().byShortId(ref);
        return byShortId.isPresent() ? byShortId : factory.lines().byName(ref);
    }

    /**
     * 按短 id 找机器。
     *
     * @param factory 工厂，可为 {@code null}
     * @param ref     短 id，可为 {@code null}
     * @return 机器；找不到或前缀有歧义时为空
     */
    public static Optional<MachineRef> machine(@Nullable Factory factory, @Nullable String ref) {
        if (factory == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return factory.machines().byShortId(ref);
    }

    /**
     * 在产线里找一台机器对应的 IO 组。
     *
     * @param factory    工厂
     * @param line       产线
     * @param machineRef 机器短 id
     * @return IO 组；机器不在产线里时为空
     */
    public static Optional<IoGroup> group(Factory factory, PassiveLine line, String machineRef) {
        return machine(factory, machineRef).flatMap(target -> line.groupByMachine(target.id()));
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
        for (PassiveLine line : factory.lines().all()) {
            for (IoGroup group : line.groups()) {
                assigned.add(group.machineId());
            }
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
        for (PassiveLine line : factory.lines().all()) {
            if (line.groupByMachine(machineId).isPresent()) {
                names.add(line.name());
            }
        }
        return names;
    }

    /**
     * 一台机器配了几条规则（输入 + 输出）。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 规则条数；没挂任何产线时为 {@code 0}
     */
    public static int ruleCountOf(Factory factory, UUID machineId) {
        int count = 0;
        for (PassiveLine line : factory.lines().all()) {
            Optional<IoGroup> group = line.groupByMachine(machineId);
            if (group.isPresent()) {
                count += group.get().inputs().size() + group.get().outputs().size();
            }
        }
        return count;
    }
}
