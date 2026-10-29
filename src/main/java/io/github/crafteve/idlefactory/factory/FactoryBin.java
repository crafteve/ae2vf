package io.github.crafteve.idlefactory.factory;

import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.line.PassiveLine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;

/**
 * 回收站：谓词 + 操作。
 *
 * <p>核心只有一条布尔表达式：</p>
 *
 * <pre>
 *   回收站（显示） = detached || health.gone()
 *   真正删除      = !referenced &amp;&amp; (detached || health.gone())
 * </pre>
 *
 * <p>三个输入分别来自三处，职责不混：</p>
 * <ul>
 *   <li>{@code referenced} —— 配置层现算（{@link FactoryRefs#assignedMachines}）；</li>
 *   <li>{@code detached} —— 配置层存的玩家意图（{@code MachineLibrary}）；</li>
 *   <li>{@code health} —— 观测层的事实（{@code ObservationService}）。</li>
 * </ul>
 *
 * <p>两个谓词是纯函数，可以直接单测；{@link #purge} 是唯一会动配置的入口。</p>
 */
public final class FactoryBin {
    private FactoryBin() {
        // 纯静态工具类
    }

    /**
     * 这台机器算不算在回收站里。
     *
     * @param detached 玩家是否已断开
     * @param health   观测结论
     * @return 断开或确实不在了时为 {@code true}
     */
    public static boolean binned(boolean detached, Health health) {
        return detached || health.gone();
    }

    /**
     * 该不该把它从机器库彻底删掉。
     *
     * @param referenced 是否还有 IO 组引用它
     * @param detached   玩家是否已断开
     * @param health     观测结论
     * @return 无引用、且在回收站里时为 {@code true}
     */
    public static boolean purgeable(boolean referenced, boolean detached, Health health) {
        return !referenced && binned(detached, health);
    }

    /**
     * 现算「还有谁被 IO 组引用着」。
     *
     * @param factory 工厂
     * @return 被引用的机器 id
     */
    public static Set<UUID> referenced(Factory factory) {
        return FactoryRefs.assignedMachines(factory);
    }

    /**
     * 把一台机器从机器库彻底移除，并级联删掉所有产线里指向它的组。
     *
     * <p>这是唯一会让 {@code IoGroup.machineId} 失去落点的操作，所以必须级联——否则就出现
     * 「组指向一台不存在的机器」的孤儿态。</p>
     *
     * @param factory   工厂
     * @param machineId 机器 id
     */
    public static void purge(Factory factory, UUID machineId) {
        for (PassiveLine line : factory.lines().all()) {
            line.removeGroupByMachine(machineId);
        }
        factory.machines().remove(machineId);
    }

    /**
     * 解引用之后调用：够条件就顺手清掉。
     *
     * <p>用于 {@code line remove} / {@code line delete} 之后——玩家把最后一条引用拿掉了，
     * 如果这台机器本来就在回收站，就没必要再留。</p>
     *
     * @param factory      工厂
     * @param observations 观测服务；{@code null} 时保守不动手
     * @param machineId    机器 id
     * @return 是否真的删了
     */
    public static boolean purgeIfOrphaned(Factory factory, @Nullable ObservationService observations,
            UUID machineId) {
        Health health = healthOf(factory, observations, machineId);
        if (health == null) {
            return false;
        }
        boolean detached = factory.machines().isDetached(machineId);
        boolean referenced = referenced(factory).contains(machineId);
        if (!purgeable(referenced, detached, health)) {
            return false;
        }
        purge(factory, machineId);
        return true;
    }

    /**
     * 读一台机器的观测结论。
     *
     * @param factory      工厂
     * @param observations 观测服务
     * @param machineId    机器 id
     * @return 结论；机器不在库里或服务未就绪时为 {@code null}
     */
    @Nullable
    public static Health healthOf(Factory factory, @Nullable ObservationService observations,
            UUID machineId) {
        if (observations == null) {
            return null;
        }
        MachineRef ref = factory.machines().byId(machineId).orElse(null);
        if (ref == null) {
            return null;
        }
        return observations.observe(ref.blockKey()).health();
    }
}
