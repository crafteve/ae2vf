package io.github.crafteve.idlefactory.client.screen.text;


import io.github.crafteve.idlefactory.command.CommandReason;

import net.minecraft.network.chat.Component;
import io.github.crafteve.idlefactory.command.CommandResult;

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
            case OK -> tr("idlefactory.command.ok");
            case NOT_STARTED -> tr("idlefactory.command.not_started");
            case FACTORY_MISSING -> tr("idlefactory.command.factory_missing");
            case NODE_MISSING -> tr("idlefactory.command.node_missing");
            case RULE_FULL -> tr("idlefactory.command.rule_full");
            case RULE_EXISTS -> tr("idlefactory.command.rule_exists");
            case RULE_MISSING -> tr("idlefactory.command.rule_missing");
            case SAME_SIDE -> tr("idlefactory.command.same_side");
            case NAME_TOO_LONG -> tr("idlefactory.command.name_too_long");
            case NAME_CONTROL_CHAR -> tr("idlefactory.command.name_control_char");
            case RENAME_FAILED -> tr("idlefactory.command.rename_failed");
            case RESOURCE_EMPTY -> tr("idlefactory.command.resource_empty");
            case RESOURCE_UNKNOWN_ITEM -> tr("idlefactory.command.resource_unknown_item");
            case RESOURCE_UNKNOWN_FLUID -> tr("idlefactory.command.resource_unknown_fluid");
            case RESOURCE_NOT_FE -> tr("idlefactory.command.resource_not_fe");
            case OCCUPIED_OTHER -> tr("idlefactory.command.occupied_other");
            case OCCUPIED_SELF_ROLE -> tr("idlefactory.command.occupied_self_role");
            case TARGET_UNLOADED -> tr("idlefactory.command.target_unloaded");
            case NOT_ME_BLOCK -> tr("idlefactory.command.not_me_block");
            case OTHER_BOUND -> tr("idlefactory.command.other_bound");
            case SESSION_MISMATCH -> tr("idlefactory.command.session_mismatch");
            case SEQ_STALE -> tr("idlefactory.command.seq_stale");
            case TARGET_NOT_MACHINE -> tr("idlefactory.command.target_not_machine");
            case NO_CHANGE -> tr("idlefactory.command.no_change");
            case INTERNAL -> tr("idlefactory.command.internal");
            case EMPTY_DRAFT -> tr("idlefactory.command.empty_draft");
            case NOT_HOLDING_WAND -> tr("idlefactory.command.not_holding_wand");
            case STALE_REQUEST -> tr("idlefactory.command.stale_request");
            case TYPE_MISMATCH -> tr("idlefactory.command.type_mismatch");
            case DUPLICATE_BLOCK -> tr("idlefactory.command.duplicate_block");
            case BLOCK_CLAIMED -> tr("idlefactory.command.block_claimed");
            case BLOCK_UNLOADED -> tr("idlefactory.command.block_unloaded");
            case NOT_A_MACHINE -> tr("idlefactory.command.not_a_machine");
            case LIMIT_NODES -> tr("idlefactory.command.limit_nodes");
            case LIMIT_MACHINES -> tr("idlefactory.command.limit_machines");
            case MEMBER_NOT_REMOVABLE -> tr("idlefactory.command.member_not_removable");
            case DELETE_BLOCKED_RULES -> tr("idlefactory.command.delete_blocked_rules");
            case DELETE_BLOCKED_PATTERNS -> tr("idlefactory.command.delete_blocked_patterns");
            case INVALID_DRAFT -> tr("idlefactory.command.invalid_draft");
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
                ? Component.translatable("idlefactory.command.anchor_updated").getString()
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
