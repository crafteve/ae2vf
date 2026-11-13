package io.github.crafteve.idlefactory.factory.io;

import java.util.Map;
import java.util.function.Function;

import io.github.crafteve.idlefactory.factory.machine.Machine;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 容量查询：这一轮这台机器能完整收下几份。
 *
 * <p>只回答「几份」，不选面、不写入：每一行走 {@link FacePick} 定面，然后在那个面上模拟
 * {@code perPortion × n}，逐行取最小值。整数份语义下，任何一行收不满就不算这一份。</p>
 *
 * <p>主动分配的份数与智能倍率共用这一个口径：两处如果各写一套模拟，同一台机器会给出两个
 * 不同的「能装几份」，倍率乘上去就是复制或丢料。</p>
 */
public final class CapacityPick {
    /**
     * 单次探测的份数上限。
     *
     * <p>不是玩法上限，只是二分探测的初始右界：正常机器一轮装不到这个量，真到了也该由
     * 倍率的业务上限去夹。有了它，二分不会被 {@code Long.MAX_VALUE} 卡住。</p>
     */
    private static final int MAX_PORTIONS_PROBE = 65_536;

    private final FacePick facePick;
    private final PortExecutor ports;

    /**
     * @param facePick 选面
     * @param ports    端口缓存
     */
    public CapacityPick(FacePick facePick, PortExecutor ports) {
        this.facePick = facePick;
        this.ports = ports;
    }

    /**
     * 这台机器能完整收下几份。
     *
     * @param machine   机器
     * @param perPortion 每行资源 → 一份的用量
     * @return 可装份数；有任何一行放不下时为 {@code 0}
     */
    public long portionsThatFit(Machine machine, Map<AEKey, Long> perPortion) {
        return this.portionsThatFit(machine, perPortion, key -> null);
    }

    /**
     * 这台机器在指定面上能完整收下几份。
     *
     * <p>面已经由调用方（主动规划器按经验面 / 配置面）定好，这里只模拟，不再选面。主动投递
     * 必须走这条：容量与随后写入的面必须是同一批，否则会出现「按 A 面算容量、写进 B 面」。</p>
     *
     * @param machine    机器
     * @param perPortion 每行资源 → 一份的用量
     * @param faces      每行资源 → 面上的选择结果
     * @return 可装份数；任何一行没有面时为 {@code 0}
     */
    public long portionsThatFit(Machine machine, Map<AEKey, Long> perPortion,
            Map<AEKey, Direction> faces) {
        if (perPortion.isEmpty()) {
            return 0L;
        }
        long best = Long.MAX_VALUE;
        for (Map.Entry<AEKey, Long> line : perPortion.entrySet()) {
            Direction face = faces.get(line.getKey());
            long amount = line.getValue();
            if (face == null || amount <= 0L) {
                return 0L;
            }
            MEStorage storage = this.ports.storage(machine, face);
            if (storage == null) {
                return 0L;
            }
            best = Math.min(best, this.fitOnFace(storage, line.getKey(), amount));
            if (best <= 0L) {
                return 0L;
            }
        }
        return best == Long.MAX_VALUE ? 0L : best;
    }

    /**
     * 这台机器对这份样板的总容量份数：当前空位 + 机器里已有的同种输入。
     *
     * <p>智能倍率必须按「容量」而不是「空位」：倍数写进样板广告后长期固定，按空位算会随机器
     * 内容漂移（剩多少就变成多少），同一个槽位一会儿是 48、一会儿是 37。把已在机器里的同种
     * 输入加回来，结果就与空 / 满无关，只在拓扑或槽位被别的物品占用时才变。</p>
     *
     * <p>只统计同一行、同一个面：面与投递用的面一致，避免「按 A 面算容量、写进 B 面」。</p>
     *
     * @param machine    机器
     * @param perPortion 每行资源 → 一份的用量
     * @param configured 资源 → 玩家配置的面
     * @return 可装份数；任何一行没有面时为 {@code 0}
     */
    public long capacityPortions(Machine machine, Map<AEKey, Long> perPortion,
            Function<AEKey, Direction> configured) {
        if (perPortion.isEmpty()) {
            return 0L;
        }
        long best = Long.MAX_VALUE;
        for (Map.Entry<AEKey, Long> line : perPortion.entrySet()) {
            AEKey key = line.getKey();
            long amount = line.getValue();
            if (amount <= 0L) {
                return 0L;
            }
            FacePick.Result pick = this.facePick.pick(machine, key, IoSide.INPUT,
                    configured.apply(key), null);
            if (!pick.picked()) {
                return 0L;
            }
            MEStorage storage = this.ports.storage(machine, pick.face());
            if (storage == null) {
                return 0L;
            }
            long free = this.fitOnFace(storage, key, amount);
            long held = AeKeySupport.snapshot(storage).getOrDefault(key, 0L);
            best = Math.min(best, free + held / amount);
            if (best <= 0L) {
                return 0L;
            }
        }
        return best == Long.MAX_VALUE ? 0L : best;
    }

    /**
     * 这台机器能完整收下几份，并按玩家配置的面优先。
     *
     * <p>配置面必须参与模拟：pushPattern 用配置面判定能不能收，投递也走同一个面；两处如果
     * 用不同的面算，就会出现「判定能收、实际永远收不下」的卡单。</p>
     *
     * @param machine    机器
     * @param perPortion 每行资源 → 一份的用量
     * @param configured 资源 → 玩家配置的面；没配返回 {@code null}
     * @return 可装份数；有任何一行放不下时为 {@code 0}
     */
    public long portionsThatFit(Machine machine, Map<AEKey, Long> perPortion,
            Function<AEKey, Direction> configured) {
        if (perPortion.isEmpty()) {
            return 0L;
        }
        Map<AEKey, Direction> faces = new java.util.LinkedHashMap<>();
        for (AEKey key : perPortion.keySet()) {
            FacePick.Result pick = this.facePick.pick(machine, key, IoSide.INPUT,
                    configured.apply(key), null);
            if (!pick.picked()) {
                return 0L;
            }
            faces.put(key, pick.face());
        }
        return this.portionsThatFit(machine, perPortion, faces);
    }

    /**
     * 在一个面上二份探测：最大的 n 使 {@code n × amount} 能被完整收下。
     *
     * @param storage 面的存储
     * @param key     资源
     * @param amount  一份的用量
     * @return 可装份数
     */
    private long fitOnFace(MEStorage storage, AEKey key, long amount) {
        long high = Math.min(MAX_PORTIONS_PROBE, Long.MAX_VALUE / amount);
        long low = 0L;
        long best = 0L;
        while (low <= high) {
            long mid = low + ((high - low) >>> 1);
            if (mid == 0L) {
                low = 1L;
                continue;
            }
            long want = mid * amount;
            long accepted;
            try {
                accepted = storage.insert(key, want, Actionable.SIMULATE, AeKeySupport.SOURCE);
            } catch (RuntimeException e) {
                // 第三方能力实现抛异常：这个面按「一份都收不下」处理，不把异常带出去。
                return 0L;
            }
            if (accepted >= want) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }
}
