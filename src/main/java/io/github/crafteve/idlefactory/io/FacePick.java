package io.github.crafteve.idlefactory.io;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.machine.Machine;
import io.github.crafteve.idlefactory.key.AeKeyAdapter;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.Direction;

/**
 * 选面：只回答「这一条该走哪个面」，不决定搬多少。
 *
 * <p>优先级固定：玩家配置的面 → 经验面 → 逐面探。前两者**直接返回，不做任何模拟**——配置面是
 * 玩家意图，经验面是上一轮真搬通过的面，替它们模拟一次既贵又会把「这轮满了」误判成「面不对」。
 * 两者都没有才逐面建存储、模拟一次，拿到第一个能收下 / 抽得出的面。</p>
 *
 * <p>失败分两种：{@link IoReason#FACE_CONFIG_INVALID} 表示玩家配的面根本没有能力（配置错误，
 * 不兜底、不换面）；{@link IoReason#NO_FACE} 表示机器没加载或六个面都拿不到。只读，不写经验面。</p>
 */
public final class FacePick {
    /** 能量面探测用的模拟量：Mekanism 的 FE↔J 换算会在小数值上截断。 */
    private static final int ENERGY_PROBE = 1000;
    /** 其它资源探测的模拟量：1 个就足以判断这个面收不收 / 抽不抽得出来。 */
    private static final int ITEM_PROBE = 1;
    /** 固定顺序的六面，避免 {@code Direction.values()} 每次复制数组。 */
    private static final Direction[] FACES = Direction.values();

    /**
     * 一次选面结果。
     *
     * <p>成功时 {@code face != null} 且 {@code failure == null}；失败时反过来。用 record 而不是
     * null 或异常，是因为调用方要把失败原因写进账本，不能只回一个空。</p>
     *
     * @param face    选中的面；失败时为 {@code null}
     * @param failure 失败原因；成功时为 {@code null}
     */
    public record Result(@Nullable Direction face, @Nullable IoReason failure) {
        public Result {
            if ((face == null) == (failure == null)) {
                throw new IllegalArgumentException("选面结果必须二者其一");
            }
        }

        /** 成功。 */
        public static Result ok(Direction face) {
            return new Result(face, null);
        }

        /** 失败。 */
        public static Result fail(IoReason reason) {
            return new Result(null, reason);
        }

        /** 是否选到了面。 */
        public boolean picked() {
            return this.face != null;
        }
    }

    private final PortExecutor ports;

    /**
     * @param ports 端口缓存，本类只读它
     */
    public FacePick(PortExecutor ports) {
        this.ports = ports;
    }

    /**
     * 给一条规则 / 一个资源选面。
     *
     * @param machine    机器
     * @param key        资源
     * @param side       方向
     * @param configured 玩家配置的面；{@code null} 表示不限
     * @param hint       经验面；{@code null} 表示没有
     * @return 选面结果
     */
    public Result pick(Machine machine, AEKey key, IoSide side, @Nullable Direction configured,
            @Nullable Direction hint) {
        if (!this.ports.loaded(machine)) {
            return Result.fail(IoReason.NO_FACE);
        }
        if (configured != null) {
            // 配置面无效不兜底：这是玩家配置问题，替玩家猜会把问题藏起来。
            return this.ports.storage(machine, configured) == null
                    ? Result.fail(IoReason.FACE_CONFIG_INVALID)
                    : Result.ok(configured);
        }
        if (hint != null && this.ports.storage(machine, hint) != null) {
            return Result.ok(hint);
        }
        for (Direction face : FACES) {
            MEStorage storage = this.ports.storage(machine, face);
            if (storage != null && this.accepts(storage, key, side)) {
                return Result.ok(face);
            }
        }
        return Result.fail(IoReason.NO_FACE);
    }

    /**
     * 取某个面的存储。
     *
     * <p>只给「同一轮里已经选好面」的变体扫描与容量模拟用：选面已经由 {@link #pick} 负责，
     * 这里不再重复探测。</p>
     *
     * @param machine 机器
     * @param face    面
     * @return 存储；这一面没有能力时为 {@code null}
     */
    @Nullable
    public MEStorage storageOf(Machine machine, Direction face) {
        return this.ports.storage(machine, face);
    }

    /**
     * 这个面此刻能不能按方向服务该资源。
     *
     * @param storage 面的存储
     * @param key     资源
     * @param side    方向
     * @return 能收下 / 抽得出时为 {@code true}
     */
    private boolean accepts(MEStorage storage, AEKey key, IoSide side) {
        long probe = AeKeyAdapter.isEnergy(key) ? ENERGY_PROBE : ITEM_PROBE;
        try {
            return side == IoSide.INPUT
                    ? storage.insert(key, probe, Actionable.SIMULATE, AeKeyAdapter.SOURCE) > 0L
                    : storage.extract(key, probe, Actionable.SIMULATE, AeKeyAdapter.SOURCE) > 0L;
        } catch (RuntimeException e) {
            // 第三方能力实现抛异常：这个面视为不可用，换下一个面；异常不能穿出选面。
            return false;
        }
    }
}
