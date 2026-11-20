package io.github.crafteve.ae2vf.client.screen.terminal.passiveio;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

import io.github.crafteve.ae2vf.IdleFactory;
import io.github.crafteve.ae2vf.io.IoSide;
import io.github.crafteve.ae2vf.machine.MatchMode;
import io.github.crafteve.ae2vf.machine.IoRuleSpec;
import io.github.crafteve.ae2vf.key.AeKeyAdapter;
import io.github.crafteve.ae2vf.command.CommandKind;
import io.github.crafteve.ae2vf.command.CommandSource;
import io.github.crafteve.ae2vf.command.FactoryCommand;
import io.github.crafteve.ae2vf.network.payload.FactoryCommandC2S;

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
final class PassiveIoActions {
    /** 面按钮的循环顺序；不含「任意」，任意由 {@code null} 表示。 */
    private static final Direction[] FACE_ORDER = {
        Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH,
        Direction.EAST, Direction.WEST,
    };

    private PassiveIoActions() {
        // 纯静态动作层
    }

    /**
     * 方向 chip：把规则搬到另一侧。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void flip(int containerId, PassiveIoModel.BlockView block,
            PassiveIoModel.RuleRow rule) {
        IoSide target = rule.side() == IoSide.INPUT ? IoSide.OUTPUT : IoSide.INPUT;
        sendRule(containerId, block.nodeId(), CommandKind.RULE_MOVE, rule.side(),
                rule.key(), rule.face(), rule.mode(), target);
    }

    /**
     * 面 chip：循环到下一个面；{@code backwards} 时反向循环。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     * @param backwards   是否反向
     */
    static void cycleFace(int containerId, PassiveIoModel.BlockView block,
            PassiveIoModel.RuleRow rule, boolean backwards) {
        Direction next = backwards ? previousFace(rule.face()) : nextFace(rule.face());
        sendRule(containerId, block.nodeId(), CommandKind.RULE_UPDATE, rule.side(),
                rule.key(), next, rule.mode(), null);
    }

    /**
     * 模式 chip：在模糊 / 精确之间切换；非物品规则固定精确，不发包。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void toggleMode(int containerId, PassiveIoModel.BlockView block,
            PassiveIoModel.RuleRow rule) {
        if (!(rule.key() instanceof AEItemKey)) {
            return;
        }
        MatchMode next = rule.mode() == MatchMode.EXACT
                ? MatchMode.IGNORE_COMPONENTS : MatchMode.EXACT;
        sendRule(containerId, block.nodeId(), CommandKind.RULE_UPDATE, rule.side(),
                rule.key(), rule.face(), next, null);
    }

    /**
     * 右键删除。
     *
     * @param containerId 菜单容器 id
     * @param block       机器块
     * @param rule        规则
     */
    static void delete(int containerId, PassiveIoModel.BlockView block,
            PassiveIoModel.RuleRow rule) {
        sendRule(containerId, block.nodeId(), CommandKind.RULE_REMOVE, rule.side(),
                rule.key(), rule.face(), rule.mode(), null);
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
    static void addEnergy(int containerId, PassiveIoModel.BlockView block) {
        AEKey key = AeKeyAdapter.energy();
        if (key == null) {
            // 没装 Applied Flux：没有 FE 这类资源，按钮本来也不该出现。
            return;
        }
        sendRule(containerId, block.nodeId(), CommandKind.RULE_ADD, IoSide.INPUT, key, null,
                MatchMode.EXACT, null);
    }

    /**
     * 从 JEI 拖入一条新规则，方向固定输入。
     *
     * @param containerId 菜单容器 id
     * @param block       落到哪台机器
     * @param key         资源
     */
    static void add(int containerId, PassiveIoModel.BlockView block, AEKey key) {
        MatchMode mode = new IoRuleSpec(key, null, 0L).mode();
        sendRule(containerId, block.nodeId(), CommandKind.RULE_ADD, IoSide.INPUT, key, null,
                mode, null);
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

    /**
     * 发一条规则命令。
     *
     * @param containerId 菜单容器 id
     * @param nodeId      节点 id
     * @param kind        命令种类
     * @param side        当前侧
     * @param key         资源
     * @param face        面；{@code null} 表示不限
     * @param mode        匹配模式
     * @param targetSide  换方向的目标侧；其它动作为 {@code null}
     */
    private static void sendRule(int containerId, java.util.UUID nodeId, CommandKind kind,
            IoSide side, AEKey key, @Nullable Direction face, MatchMode mode,
            @Nullable IoSide targetSide) {
        PacketDistributor.sendToServer(new FactoryCommandC2S(new FactoryCommand(containerId, kind,
                CommandSource.PLAYER, nodeId, null, 0L,
                new FactoryCommand.Rule(side, key, face, mode, targetSide))));
    }

}
