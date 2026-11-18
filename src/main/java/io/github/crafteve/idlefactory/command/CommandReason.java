package io.github.crafteve.idlefactory.command;

/**
 * 一次写命令的结果原因。
 *
 * <p>服务端只回原因、不回文本：文案由客户端 {@code CommandMessages} 统一映射。原因同时承担
 * 「成功」与「失败」两种语义，{@link #ok()} 是唯一的判定入口，调用方不要按 {@code == OK} 写字面量。</p>
 */
public enum CommandReason {
    /** 成功。 */
    OK,
    /** 服务端尚未启动。 */
    NOT_STARTED,
    /** 玩家还没有工厂。 */
    FACTORY_MISSING,
    /** 找不到节点。 */
    NODE_MISSING,
    /** 规则条数已达上限。 */
    RULE_FULL,
    /** 规则已存在。 */
    RULE_EXISTS,
    /** 规则不存在。 */
    RULE_MISSING,
    /** 方向没有变化。 */
    SAME_SIDE,
    /** 名字超长。 */
    NAME_TOO_LONG,
    /** 名字含控制字符。 */
    NAME_CONTROL_CHAR,
    /** 改名写入失败。 */
    RENAME_FAILED,
    /** 资源为空。 */
    RESOURCE_EMPTY,
    /** 未知物品。 */
    RESOURCE_UNKNOWN_ITEM,
    /** 未知流体。 */
    RESOURCE_UNKNOWN_FLUID,
    /** 能量规则只能使用附属提供的 FE。 */
    RESOURCE_NOT_FE,
    /** 位置被别的工厂占了。 */
    OCCUPIED_OTHER,
    /** 位置被自己的另一种角色占了。 */
    OCCUPIED_SELF_ROLE,
    /** 目标区块未加载。 */
    TARGET_UNLOADED,
    /** 目标方块没有接入 ME 网络。 */
    NOT_ME_BLOCK,
    /** 已经绑定过别的仓库。 */
    OTHER_BOUND,
    /** 命令会话不匹配（菜单已关闭或换了界面）。 */
    SESSION_MISMATCH,
    /** 请求序号过期，按重放丢弃。 */
    SEQ_STALE,
    /** 目标不是可接入的机器。 */
    TARGET_NOT_MACHINE,
    /** 没有可执行的改动。 */
    NO_CHANGE,
    /** 内部错误。 */
    INTERNAL;

    /** 是否是成功结果。 */
    public boolean ok() {
        return this == OK;
    }
}
