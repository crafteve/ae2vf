package io.github.crafteve.ae2vf.client.screen.text;


import io.github.crafteve.ae2vf.command.CommandReason;

import net.minecraft.network.chat.Component;
import io.github.crafteve.ae2vf.command.CommandResult;

/**
 * 回执原因 → 中文文案。
 *
 * <p>服务端只回 {@link CommandReason}，文案全部在客户端；同一原因在不同界面上的措辞统一，
 * 不允许两个屏幕各写一份。</p>
 */
public final class CommandText {
    private CommandText() {
        // 纯静态映射
    }

    /**
     * 一次回执的展示文本。
     *
     * @param result 回执
     * @return 中文文案
     */
    public static String text(CommandResult result) {
        return switch (result.reason()) {
            case OK -> tr("ae2vf.command.ok");
            case NOT_STARTED -> tr("ae2vf.command.not_started");
            case FACTORY_MISSING -> tr("ae2vf.command.factory_missing");
            case NODE_MISSING -> tr("ae2vf.command.node_missing");
            case RULE_FULL -> tr("ae2vf.command.rule_full");
            case RULE_EXISTS -> tr("ae2vf.command.rule_exists");
            case RULE_MISSING -> tr("ae2vf.command.rule_missing");
            case SAME_SIDE -> tr("ae2vf.command.same_side");
            case NAME_TOO_LONG -> tr("ae2vf.command.name_too_long");
            case NAME_CONTROL_CHAR -> tr("ae2vf.command.name_control_char");
            case RENAME_FAILED -> tr("ae2vf.command.rename_failed");
            case RESOURCE_EMPTY -> tr("ae2vf.command.resource_empty");
            case RESOURCE_UNKNOWN_ITEM -> tr("ae2vf.command.resource_unknown_item");
            case RESOURCE_UNKNOWN_FLUID -> tr("ae2vf.command.resource_unknown_fluid");
            case RESOURCE_NOT_FE -> tr("ae2vf.command.resource_not_fe");
            case OCCUPIED_OTHER -> tr("ae2vf.command.occupied_other");
            case OCCUPIED_SELF_ROLE -> tr("ae2vf.command.occupied_self_role");
            case TARGET_UNLOADED -> tr("ae2vf.command.target_unloaded");
            case NOT_ME_BLOCK -> tr("ae2vf.command.not_me_block");
            case OTHER_BOUND -> tr("ae2vf.command.other_bound");
            case SESSION_MISMATCH -> tr("ae2vf.command.session_mismatch");
            case SEQ_STALE -> tr("ae2vf.command.seq_stale");
            case TARGET_NOT_MACHINE -> tr("ae2vf.command.target_not_machine");
            case NO_CHANGE -> tr("ae2vf.command.no_change");
            case INTERNAL -> tr("ae2vf.command.internal");
            case EMPTY_DRAFT -> tr("ae2vf.command.empty_draft");
            case NOT_HOLDING_WAND -> tr("ae2vf.command.not_holding_wand");
            case STALE_REQUEST -> tr("ae2vf.command.stale_request");
            case TYPE_MISMATCH -> tr("ae2vf.command.type_mismatch");
            case DUPLICATE_BLOCK -> tr("ae2vf.command.duplicate_block");
            case BLOCK_CLAIMED -> tr("ae2vf.command.block_claimed");
            case BLOCK_UNLOADED -> tr("ae2vf.command.block_unloaded");
            case NOT_A_MACHINE -> tr("ae2vf.command.not_a_machine");
            case LIMIT_NODES -> tr("ae2vf.command.limit_nodes");
            case LIMIT_MACHINES -> tr("ae2vf.command.limit_machines");
            case MEMBER_NOT_REMOVABLE -> tr("ae2vf.command.member_not_removable");
            case DELETE_BLOCKED_RULES -> tr("ae2vf.command.delete_blocked_rules");
            case DELETE_BLOCKED_PATTERNS -> tr("ae2vf.command.delete_blocked_patterns");
            case INVALID_DRAFT -> tr("ae2vf.command.invalid_draft");
        };
    }

    /**
     * 世界命令的 HUD 文本：成功说锚点已更新，失败直接用原因文案。
     *
     * @param result 回执
     * @return 文案
     */
    public static String hud(CommandResult result) {
        return result.ok()
                ? Component.translatable("ae2vf.command.anchor_updated").getString()
                : text(result);
    }

    /**
     * 成功时的短句；失败时返回空串，便于屏幕只弹失败提示。
     *
     * @param result 回执
     * @return 文案或空串
     */
    /**
     * 取一条命令文案。
     *
     * @param key 语言键
     * @return 当前语言下的文本
     */
    private static String tr(String key) {
        return net.minecraft.network.chat.Component.translatable(key).getString();
    }

    public static String successText(CommandResult result) {
        return result.ok() ? text(result) : "";
    }
}
