package io.github.crafteve.idlefactory.client;

import io.github.crafteve.idlefactory.command.CommandReason;
import io.github.crafteve.idlefactory.command.CommandResult;

/**
 * 回执原因 → 中文文案。
 *
 * <p>服务端只回 {@link CommandReason}，文案全部在客户端；同一原因在不同界面上的措辞统一，
 * 不允许两个屏幕各写一份。</p>
 */
public final class CommandMessages {
    private CommandMessages() {
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
            case OK -> "已生效";
            case NOT_STARTED -> "Idle Factory 尚未启动";
            case FACTORY_MISSING -> "你还没有工厂";
            case NODE_MISSING -> "找不到节点";
            case RULE_FULL -> "规则已达上限";
            case RULE_EXISTS -> "这一侧已经有这条规则";
            case RULE_MISSING -> "找不到这条规则";
            case SAME_SIDE -> "方向没有变化";
            case NAME_TOO_LONG -> "名字太长";
            case NAME_CONTROL_CHAR -> "名字不能包含控制字符";
            case RENAME_FAILED -> "改名失败";
            case RESOURCE_EMPTY -> "资源为空";
            case RESOURCE_UNKNOWN_ITEM -> "未知物品";
            case RESOURCE_UNKNOWN_FLUID -> "未知流体";
            case RESOURCE_NOT_FE -> "能量规则只能使用 Applied Flux 的 FE";
            case OCCUPIED_OTHER -> "这个位置属于别的工厂";
            case OCCUPIED_SELF_ROLE -> "这个位置已经被本工厂的另一种角色占用";
            case TARGET_UNLOADED -> "目标区块未加载";
            case NOT_ME_BLOCK -> "该方块没有接入 ME 网络";
            case OTHER_BOUND -> "已绑定其他仓库，先解绑";
            case SESSION_MISMATCH -> "界面会话已过期，请重新打开终端";
            case SEQ_STALE -> "这次提交已过期";
            case TARGET_NOT_MACHINE -> "目标不是可接入的机器";
            case NO_CHANGE -> "没有可执行的改动";
            case INTERNAL -> "内部错误";
        };
    }

    /**
     * 成功时的短句；失败时返回空串，便于屏幕只弹失败提示。
     *
     * @param result 回执
     * @return 文案或空串
     */
    public static String successText(CommandResult result) {
        return result.ok() ? text(result) : "";
    }
}
