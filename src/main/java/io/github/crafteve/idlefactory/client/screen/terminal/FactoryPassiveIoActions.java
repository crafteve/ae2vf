package io.github.crafteve.idlefactory.client.screen.terminal;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.io.IoSide;
import io.github.crafteve.idlefactory.machine.MatchMode;
import io.github.crafteve.idlefactory.machine.ResourceSpec;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;
import io.github.crafteve.idlefactory.network.payload.FactoryRuleActionC2S;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 被动 IO 终端的规则动作：把界面上的点击翻译成一个 C2S 包。
 *
 * <p>只发意图，不做本地乐观修改：服务端校验后回反馈与一份新快照，界面按快照重画。这样目标侧
 * 满、重名、规则已被别的界面删掉等情况都能立刻显示真实结果。</p>
 *
 * <p>面的循环顺序固定为「任意 → 上 → 下 → 北 → 南 → 东 → 西 → 任意」，点满七次回到起点。</p>
 */
final class FactoryPassiveIoActions {
    /** 面按钮的循环顺序；不含「任意」，任意由 {@code null} 表示。 */
    private static final Direction[] FACE_ORDER = {
        Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH,
        Direction.EAST, Direction.WEST,
    };

    private FactoryPassiveIoActions() {
        // 纯静态动作层
    }

    /**
     * 方向 chip：把规则搬到另一侧。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void flip(int containerId, FactoryPassiveIoModel.BlockView block,
            FactoryPassiveIoModel.RuleRow rule) {
        IoSide target = rule.side() == IoSide.INPUT ? IoSide.OUTPUT : IoSide.INPUT;
        probe("flip", block, rule, "-> " + target);
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.MOVE, block.nodeId(), rule.side(),
                rule.spec().key(), rule.spec().face(), rule.spec().mode(), target));
    }

    /**
     * 面 chip：循环到下一个面；{@code backwards} 时反向循环。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     * @param backwards   是否反向
     */
    static void cycleFace(int containerId, FactoryPassiveIoModel.BlockView block,
            FactoryPassiveIoModel.RuleRow rule, boolean backwards) {
        Direction next = backwards ? previousFace(rule.spec().face()) : nextFace(rule.spec().face());
        probe("face", block, rule, String.valueOf(next));
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.UPDATE, block.nodeId(), rule.side(),
                rule.spec().key(), next, rule.spec().mode()));
    }

    /**
     * 模式 chip：在模糊 / 精确之间切换；非物品规则固定精确，不发包。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void toggleMode(int containerId, FactoryPassiveIoModel.BlockView block,
            FactoryPassiveIoModel.RuleRow rule) {
        if (!(rule.spec().key() instanceof AEItemKey)) {
            return;
        }
        MatchMode next = rule.spec().mode() == MatchMode.EXACT
                ? MatchMode.IGNORE_COMPONENTS : MatchMode.EXACT;
        probe("mode", block, rule, next.name());
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.UPDATE, block.nodeId(), rule.side(),
                rule.spec().key(), rule.spec().face(), next));
    }

    /**
     * 右键删除。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void delete(int containerId, FactoryPassiveIoModel.BlockView block,
            FactoryPassiveIoModel.RuleRow rule) {
        probe("delete", block, rule, "");
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.REMOVE, block.nodeId(), rule.side(),
                rule.spec().key(), rule.spec().face(), rule.spec().mode()));
    }

    /**
     * 机器行 FE 按钮：加一条输入 FE 规则。
     *
     * <p>不依赖 JEI 搜索（FE 不是可浏览的配料），按钮是 FE 规则的主要入口；加了以后玩家
     * 可以用规则行的「入」chip 翻成输出。</p>
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     */
    static void addEnergy(int containerId, FactoryPassiveIoModel.BlockView block) {
        AEKey key = AeKeyAdapter.energy();
        if (key == null) {
            // 没装 Applied Flux：没有 FE 这类资源，按钮本来也不该出现。
            return;
        }
        IdleFactory.LOGGER.info("[ui-probe] add-energy block={} node={} key={}",
                block.name(), block.nodeId(), key.getId());
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.ADD, block.nodeId(), IoSide.INPUT, key, null,
                MatchMode.EXACT));
    }

    /**
     * 从 JEI 拖入一条新规则，方向固定输入。
     *
     * @param containerId 菜单容器 id
     * @param block       落到哪台机器
     * @param key         资源
     */
    static void add(int containerId, FactoryPassiveIoModel.BlockView block, AEKey key) {
        MatchMode mode = new ResourceSpec(key, null, 0L).mode();
        IdleFactory.LOGGER.info("[ui-probe] add block={} machine={} key={} mode={}",
                block.name(), block.machineId(), key.getId(), mode);
        PacketDistributor.sendToServer(new FactoryRuleActionC2S(containerId,
                FactoryRuleActionC2S.Action.ADD, block.nodeId(), IoSide.INPUT, key, null,
                mode));
    }

    /**
     * 下一个面。
     *
     * @param current 当前面；{@code null} 表示任意
     * @return 下一个面；循环到末尾时回到 {@code null}
     */
    @Nullable
    static Direction nextFace(@Nullable Direction current) {
        if (current == null) {
            return FACE_ORDER[0];
        }
        for (int i = 0; i < FACE_ORDER.length; i++) {
            if (FACE_ORDER[i] == current) {
                return i + 1 < FACE_ORDER.length ? FACE_ORDER[i + 1] : null;
            }
        }
        return null;
    }

    /**
     * 上一个面：右键 chip 用的反向循环。
     *
     * @param current 当前面；{@code null} 表示任意
     * @return 上一个面；从任意反向会先到西
     */
    @Nullable
    static Direction previousFace(@Nullable Direction current) {
        if (current == null) {
            return FACE_ORDER[FACE_ORDER.length - 1];
        }
        for (int i = 0; i < FACE_ORDER.length; i++) {
            if (FACE_ORDER[i] == current) {
                return i == 0 ? null : FACE_ORDER[i - 1];
            }
        }
        return null;
    }

    private static void probe(String action, FactoryPassiveIoModel.BlockView block,
            FactoryPassiveIoModel.RuleRow rule, String detail) {
        IdleFactory.LOGGER.info("[ui-probe] {} block={} machine={} side={} key={} {}",
                action, block.name(), block.machineId(), rule.side(), rule.spec().key().getId(),
                detail);
    }
}
