package io.github.crafteve.ae2vf.view;

/**
 * 视图表表头：整张表共用、与节点无关的两个事实。
 *
 * <p>这里只放界面真正读的两个布尔。版本号不在表里——它属于报文（{@code ViewTableS2C}），
 * 放进表头会让"同一张表内容相同"变得永远不成立，差量比较也就失去意义。</p>
 *
 * <p>{@code hasFactory} 为 {@code false} 只出现在玩家还没有工厂时构造的空表
 * （{@link ViewTable#empty()}）；有工厂时表由 {@code ViewTableBuilder} 组装，恒为 {@code true}。</p>
 *
 * @param hasFactory    玩家是否已有工厂
 * @param storageOnline 仓库是否在线
 */
public record ViewHeader(boolean hasFactory, boolean storageOnline) {
}
