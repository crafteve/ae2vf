package io.github.crafteve.idlefactory.machine;

/**
 * 一条 IO 规则匹配物品时是否比较数据组件。
 *
 * <p>仓库里的资源身份永远是精确的（同名不同组件就是两条记录，和 AE2 的
 * {@code AEItemKey} 一致）；模糊只发生在规则这一侧，决定「同注册名的哪些变体算命中」。</p>
 *
 * <p>旧存档没有这个字段，物品规则一律按 {@link #IGNORE_COMPONENTS} 读入，保持老版本
 * 「按物品 id 搬」的直觉。</p>
 */
public enum MatchMode {
    /** 只匹配组件完全相同的栈。 */
    EXACT,
    /** 只要求注册名相同，忽略全部数据组件（含损伤值）。 */
    IGNORE_COMPONENTS
}
