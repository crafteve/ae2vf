package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * IO 执行器的睡眠状态。
 *
 * <p>只有运行时，不写存档。服务器停止、产线启停、配置变更都会清掉。</p>
 *
 * <ul>
 *   <li>组连续 {@link #SLEEP_AFTER_ROUNDS} 轮没搬动 → 组睡眠；</li>
 *   <li>一条产线的组全睡 → 产线睡眠；</li>
 *   <li>睡眠单位每 {@link #WAKE_INTERVAL} tick 醒一次。</li>
 * </ul>
 */
public final class RunnerState {
    /** 连续多少轮没搬动就睡眠。 */
    public static final int SLEEP_AFTER_ROUNDS = 20;
    /** 睡眠后多久醒一次（tick）。 */
    public static final long WAKE_INTERVAL = 100;

    private final Map<UUID, LineState> lines = new HashMap<>();

    /**
     * 取产线状态，没有就建。
     *
     * @param lineId 产线 id
     * @return 产线状态
     */
    public LineState line(UUID lineId) {
        return this.lines.computeIfAbsent(lineId, id -> new LineState());
    }

    /**
     * 唤醒整条产线。
     *
     * @param lineId 产线 id
     */
    public void wakeLine(UUID lineId) {
        LineState line = this.lines.get(lineId);
        if (line == null) {
            return;
        }
        line.sleeping = false;
        for (GroupState group : line.groups.values()) {
            group.sleeping = false;
            group.idleRounds = 0;
        }
    }

    /**
     * 唤醒一个组（并顺带唤醒所在产线）。
     *
     * @param lineId  产线 id
     * @param groupId 组 id
     */
    public void wakeGroup(UUID lineId, UUID groupId) {
        LineState line = this.lines.get(lineId);
        if (line == null) {
            return;
        }
        line.sleeping = false;
        GroupState group = line.groups.get(groupId);
        if (group != null) {
            group.sleeping = false;
            group.idleRounds = 0;
        }
    }

    /**
     * 丢掉一条产线的状态（删产线时）。
     *
     * @param lineId 产线 id
     */
    public void forget(UUID lineId) {
        this.lines.remove(lineId);
    }

    /** 清空全部状态（服务器停止时）。 */
    public void clear() {
        this.lines.clear();
    }

    /** 一条产线的睡眠状态。 */
    public static final class LineState {
        /** 整条产线是否睡眠。 */
        public boolean sleeping;
        /** 下次唤醒时间（game time）。 */
        public long wakeAt;
        /** 组状态。 */
        public final Map<UUID, GroupState> groups = new HashMap<>();

        /**
         * 取组状态，没有就建。
         *
         * @param groupId 组 id
         * @return 组状态
         */
        public GroupState group(UUID groupId) {
            return this.groups.computeIfAbsent(groupId, id -> new GroupState());
        }
    }

    /** 一个组的睡眠状态。 */
    public static final class GroupState {
        /** 连续没搬动的轮数。 */
        public int idleRounds;
        /** 是否睡眠。 */
        public boolean sleeping;
        /** 下次唤醒时间（game time）。 */
        public long wakeAt;
    }
}
