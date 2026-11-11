package io.github.crafteve.idlefactory.menu;

/**
 * 终端排序档位。
 *
 * <p>声明顺序就是档位按钮的循环顺序，也是写入物品组件与网络包的 ordinal。改动顺序会让
 * 旧终端里存着的档位含义错位，所以只允许在末尾追加，不允许重排。</p>
 */
public enum FactorySortMode {
    /** 节点显示名：自定义名优先，否则用类型方块名。 */
    NAME,
    /** 节点类型方块的注册名（{@code typeBlockId}）。 */
    ID,
    /** 节点机器台数。 */
    MACHINE_COUNT,
    /** 节点 IO 规则数。 */
    RULE_COUNT,
    /** 节点展示状态：升序从好到坏。 */
    STATUS,
    /** 到玩家的距离：升序由近到远，不同维度按最远处理。 */
    DISTANCE;

    /**
     * 下一个档位；档位按钮点击按这个顺序循环。
     *
     * @return 下一个档位
     */
    public FactorySortMode next() {
        FactorySortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
