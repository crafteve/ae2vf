package io.github.crafteve.idlefactory.factory.registry;

/**
 * 占用角色：机器还是仓库。
 *
 * <p>ME 和容器不分——模式逻辑看工厂自己的地址类型，门禁只需要知道“这是机器还是仓库”，
 * 报冲突时才用得上这个区分。</p>
 */
public enum LinkRole {
    /** 机器库里的机器。 */
    MACHINE,
    /** 仓库地址：ME 方块、旧量子环或容器。 */
    STORAGE
}
