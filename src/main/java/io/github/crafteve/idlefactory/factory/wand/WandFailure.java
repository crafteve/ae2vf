package io.github.crafteve.idlefactory.factory.wand;

/**
 * 绑定杖操作的失败原因。
 *
 * <p>客户端按它出 HUD 与面板标红，不再解析服务端字符串：{@link #NONE} 表示成功，其余每个值
 * 都有唯一文案（见客户端文案表）。删不掉的节点用 {@link #DELETE_BLOCKED} 加
 * {@link WandDeleteReason} 说明是规则还是样板。</p>
 *
 * <p>枚举顺序即线协议序号，新增值只能追加在末尾，并调用方必须补全穷尽 {@code switch}。</p>
 */
public enum WandFailure {
    /** 成功，没有失败原因。 */
    NONE,
    /** 草稿里既没有要加的方块，也没有要删的机器。 */
    EMPTY_DRAFT,
    /** 主手不是机器绑定杖。 */
    NOT_HOLDING_WAND,
    /** 玩家还没有工厂；建厂只发生在打开终端时。 */
    NO_FACTORY,
    /** 提交序号不新于服务端已接受的最大序号，按重放丢弃。 */
    STALE_REQUEST,
    /** 请求结构不合法或写库结果与预期不符。 */
    INVALID_REQUEST,
    /** 草稿指向的节点不存在。 */
    NODE_NOT_FOUND,
    /** 方块类型与节点（或草稿首台机器）不一致。 */
    TYPE_MISMATCH,
    /** 草稿内方块重复。 */
    DUPLICATE_BLOCK,
    /** 方块已经属于某个节点，或已被其它工厂占用。 */
    BLOCK_CLAIMED,
    /** 方块所在区块没有加载。 */
    BLOCK_UNLOADED,
    /** 方块没有方块实体，或没有可搬运能力，不是可接入的机器。 */
    NOT_A_MACHINE,
    /** 新建节点会超过节点数上限。 */
    LIMIT_NODES,
    /** 最终机器数会超过单节点或全工厂机器数上限。 */
    LIMIT_MACHINES,
    /** 节点不能删：还有规则或样板。 */
    DELETE_BLOCKED,
    /** 1:1 断开的目标是多机器节点的成员，只能去终端编辑。 */
    MEMBER_NOT_REMOVABLE
}
