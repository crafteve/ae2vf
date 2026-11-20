package io.github.crafteve.ae2vf.command;

/**
 * 写命令的种类。
 *
 * <p>种类同时决定三件事：门禁要查哪几步、执行后推哪种快照、客户端要不要回执。世界命令
 * （绑定 / 解绑仓库）用 {@code containerId = -1} 跳过会话步骤。</p>
 */
public enum CommandKind {
    /** 建厂：SYSTEM、幂等、无回执。 */
    FACTORY_CREATE(Scope.FACTORY, false, true),
    /** 删节点。 */
    NODE_DELETE(Scope.NODE, true, false),
    /** 节点改名。 */
    NODE_RENAME(Scope.NODE, true, false),
    /** 加一条规则。 */
    RULE_ADD(Scope.NODE, true, false),
    /** 改规则的匹配模式 / 面。 */
    RULE_UPDATE(Scope.NODE, true, false),
    /** 规则换方向。 */
    RULE_MOVE(Scope.NODE, true, false),
    /** 删一条规则。 */
    RULE_REMOVE(Scope.NODE, true, false),
    /** 改阻挡。 */
    PROVIDER_BLOCKING(Scope.NODE, true, false),
    /** 改倍率。 */
    PROVIDER_MULTIPLIER(Scope.NODE, true, false),
    /** 改派单模式。 */
    PROVIDER_DISPATCH(Scope.NODE, true, false),
    /** 绑定杖 toggle 机器。 */
    WAND_TOGGLE(Scope.BLOCK, true, false),
    /** 绑定杖提交草稿。 */
    WAND_COMMIT(Scope.FACTORY, true, false),
    /** 设置仓库锚点。 */
    SET_ANCHOR(Scope.BLOCK, true, false),
    /** 清空仓库锚点。 */
    CLEAR_ANCHOR(Scope.BLOCK, true, false),
    /** 节点类型迁移：SYSTEM、失败下一轮再试。 */
    NODE_TYPE_MIGRATE(Scope.NODE, false, true);

    /** 命令作用域。 */
    public enum Scope {
        /** 只改工厂级配置。 */
        FACTORY,
        /** 需要 nodeId。 */
        NODE,
        /** 需要目标方块。 */
        BLOCK
    }

    private final Scope scope;
    private final boolean needsReceipt;
    private final boolean systemOnly;

    CommandKind(Scope scope, boolean needsReceipt, boolean systemOnly) {
        this.scope = scope;
        this.needsReceipt = needsReceipt;
        this.systemOnly = systemOnly;
    }

    /** 作用域。 */
    public Scope scope() {
        return this.scope;
    }

    /** 是否要给客户端回执。 */
    public boolean needsReceipt() {
        return this.needsReceipt;
    }

    /** 是否只允许 SYSTEM 发起。 */
    public boolean systemOnly() {
        return this.systemOnly;
    }
}
