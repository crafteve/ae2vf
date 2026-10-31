package io.github.crafteve.idlefactory.factory.node;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import appeng.api.AECapabilities;
import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.storage.CompositeStorage;
import appeng.parts.automation.StackWorldBehaviors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 机器面的解析器：把「机器这个方块」按六个面翻译成派单目标与可抽取存储。
 *
 * <p>虚拟供应器没有自己的方块，材料直接塞进机器本身，所以每个面都是
 * {@code 机器坐标 + 该面} 这个能力查询。面选择顺序：机器输入规则里配过这个 key 的面优先，
 * 其次六面模拟（每个 key 至少要能收 1 个）。选择结果不缓存：机器的面配置与能力随时会变，
 * 缓存反而要额外维护失效。</p>
 *
 * <p>一次派单用一个 {@link Faces} 快照，同一次派单里六面只解析一次。</p>
 */
final class ProviderFaceResolver {
    /** 固定顺序的六面，避免 {@code Direction.values()} 每次复制数组。 */
    private static final Direction[] FACES = Direction.values();
    /** 外部存储包装用的空回调。 */
    private static final Runnable NOOP = () -> {
    };

    private final MachineNode node;

    /**
     * @param node 所属机器节点
     */
    ProviderFaceResolver(MachineNode node) {
        this.node = node;
    }

    /**
     * 机器所在世界；区块没加载时为 {@code null}。
     *
     * @return 服务端世界
     */
    @Nullable
    ServerLevel level() {
        ServerLevel level = this.node.machineLevel();
        if (level == null || !level.isLoaded(this.node.machine().ref().pos())) {
            return null;
        }
        return level;
    }

    /**
     * 开一次派单 / 回收用的面快照。
     *
     * @return 快照；机器未加载时为 {@code null}
     */
    @Nullable
    Faces snapshot() {
        ServerLevel level = this.level();
        return level == null ? null : new Faces(this, level);
    }

    /**
     * 某个面的可抽取存储：先看原生 ME 存储能力，没有再用 AE2 的外部存储策略包一层。
     *
     * @param level 机器所在世界
     * @param pos   机器坐标
     * @param face  面
     * @return 存储；这个面没有可抽取的东西时为 {@code null}
     */
    @Nullable
    MEStorage storage(ServerLevel level, BlockPos pos, Direction face) {
        BlockEntity be = level.getBlockEntity(pos);
        MEStorage nativeStorage = be != null
                ? level.getCapability(AECapabilities.ME_STORAGE, pos, be.getBlockState(), be, face)
                : level.getCapability(AECapabilities.ME_STORAGE, pos, face);
        if (nativeStorage != null) {
            return nativeStorage;
        }
        Map<AEKeyType, ExternalStorageStrategy> strategies =
                StackWorldBehaviors.createExternalStorageStrategies(level, pos, face);
        IdentityHashMap<AEKeyType, MEStorage> wrappers = new IdentityHashMap<>(2);
        for (Map.Entry<AEKeyType, ExternalStorageStrategy> entry : strategies.entrySet()) {
            MEStorage wrapper = entry.getValue().createWrapper(false, NOOP);
            if (wrapper != null) {
                wrappers.put(entry.getKey(), wrapper);
            }
        }
        return wrappers.isEmpty() ? null : new CompositeStorage(wrappers);
    }

    /**
     * 机器输入规则里给这个 key 配的面。
     *
     * <p>先找完整 key 相同的精确规则，再找同注册名的模糊规则；没配面的规则不参与。</p>
     *
     * @param key 资源
     * @return 面；没有配置时为 {@code null}
     */
    @Nullable
    private Direction ruleFace(AEKey key) {
        List<ResourceSpec> inputs = this.node.machine().io().inputs();
        Direction fuzzy = null;
        for (ResourceSpec spec : inputs) {
            if (spec.face() == null) {
                continue;
            }
            if (spec.key().equals(key)) {
                return spec.face();
            }
            if (fuzzy == null && spec.fuzzy()
                    && spec.key().getType().equals(key.getType())
                    && spec.key().getId().equals(key.getId())) {
                fuzzy = spec.face();
            }
        }
        return fuzzy;
    }

    /**
     * 一次派单 / 一次回收里复用的六面快照。
     *
     * <p>目标按需解析并记住「解析过没有」，某个面确实没有目标时不重复查能力。</p>
     */
    static final class Faces {
        private final ProviderFaceResolver owner;
        private final ServerLevel level;
        private final BlockPos pos;
        @Nullable
        private final BlockEntity blockEntity;
        private final PatternProviderTarget[] targets = new PatternProviderTarget[FACES.length];
        private final boolean[] resolved = new boolean[FACES.length];

        private Faces(ProviderFaceResolver owner, ServerLevel level) {
            this.owner = owner;
            this.level = level;
            this.pos = owner.node.machine().ref().pos();
            this.blockEntity = level.getBlockEntity(this.pos);
        }

        /**
         * 某个面的派单目标。
         *
         * @param face 面
         * @return 目标；这个面没有可写的存储时为 {@code null}
         */
        @Nullable
        PatternProviderTarget target(Direction face) {
            int index = face.get3DDataValue();
            if (!this.resolved[index]) {
                this.resolved[index] = true;
                IActionSource source = this.owner.node().actionSource();
                this.targets[index] = PatternProviderTarget.get(this.level, this.pos,
                        this.blockEntity, face, source);
            }
            return this.targets[index];
        }

        /**
         * 某个面的可抽取存储。
         *
         * @param face 面
         * @return 存储；没有时为 {@code null}
         */
        @Nullable
        MEStorage storage(Direction face) {
            return this.owner.storage(this.level, this.pos, face);
        }

        /**
         * 阻挡判定：任意一个面上还有这台机器的样板输入就整单拒绝。
         *
         * @param inputs 这台机器所有样板输入的 key 并集（已去组件）
         * @return 是否撞到阻挡
         */
        boolean blockingHit(Set<AEKey> inputs) {
            if (inputs.isEmpty()) {
                return false;
            }
            for (Direction face : FACES) {
                PatternProviderTarget target = this.target(face);
                if (target != null && target.containsPatternInput(inputs)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 给一个 key 选面：规则面优先，其次六面模拟，要求这个面至少能收 1 个。
         *
         * @param key    资源
         * @param amount 这一份的数量
         * @return 能收的面；一个都收不了时为 {@code null}
         */
        @Nullable
        Direction select(AEKey key, long amount) {
            Direction preferred = this.owner.ruleFace(key);
            if (preferred != null && this.accepts(preferred, key, amount)) {
                return preferred;
            }
            for (Direction face : FACES) {
                if (face == preferred) {
                    continue;
                }
                if (this.accepts(face, key, amount)) {
                    return face;
                }
            }
            return null;
        }

        /** 这个面模拟插入是否至少收 1 个。 */
        private boolean accepts(Direction face, AEKey key, long amount) {
            PatternProviderTarget target = this.target(face);
            return target != null && target.insert(key, amount, Actionable.SIMULATE) > 0L;
        }
    }

    /** 给 {@link Faces} 读动作源。 */
    MachineNode node() {
        return this.node;
    }
}
