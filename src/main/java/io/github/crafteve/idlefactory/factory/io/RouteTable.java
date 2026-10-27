package io.github.crafteve.idlefactory.factory.io;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.resource.MatchMode;
import io.github.crafteve.idlefactory.factory.resource.ResourceKey;
import io.github.crafteve.idlefactory.factory.resource.ResourceSpec;

import net.minecraft.core.Direction;

/**
 * 面路由表：记住「这条规则上次从哪个面搬通了」。
 *
 * <p>这是<b>派生事实</b>，不是配置：程序自己建、自己失效，玩家不配置它。规则本身写的面
 * （{@link ResourceSpec#face()}）才是配置，优先级更高——配了面就永远用那个面，不查路由表。</p>
 *
 * <p>键是 {@code (输入/输出, 资源 key, 匹配模式)}。资源 key 带组件，模糊与精确也分开记，
 * 免得同名不同变体共用一条路由。</p>
 *
 * <h2>失效</h2>
 * <ul>
 *   <li><b>代数（generation）</b>：观测层每次作废面表就 +1。路由条目记着自己建表时的代数，
 *       代数一对不上就作废——方块换了、模组改了面配置，都会走这条路。</li>
 *   <li><b>{@link #clear}</b>：执行层发现「这个面明明有容量就是不收」（{@code REJECTED}）时踢掉，
 *       下次重扫。</li>
 *   <li><b>{@link #clearAll}</b>：玩家改了规则时清空。</li>
 * </ul>
 *
 * <p>不写存档。服务器重启、产线重开都会从空表开始，第一次搬运自己重建。</p>
 */
public final class RouteTable {
    /**
     * 一条路由。
     *
     * @param face 上次搬通的面；{@code null} 表示无向（绕过机器的面配置）
     */
    public record Route(@Nullable Direction face) {
    }

    private record Key(boolean input, ResourceKey key, MatchMode mode) {
    }

    private record Entry(Route route, long generation) {
    }

    private final Map<Key, Entry> entries = new HashMap<>();

    /**
     * 取一条路由。
     *
     * @param spec       规则
     * @param input      {@code true} 表示输入方向
     * @param generation 当前面表代数
     * @return 路由；没有或已过期时为空
     */
    public Optional<Route> route(ResourceSpec spec, boolean input, long generation) {
        Key key = key(spec, input);
        Entry entry = this.entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.generation() != generation) {
            this.entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.route());
    }

    /**
     * 记住一条路由。
     *
     * @param spec       规则
     * @param input      {@code true} 表示输入方向
     * @param route      路由
     * @param generation 当前面表代数
     */
    public void remember(ResourceSpec spec, boolean input, Route route, long generation) {
        this.entries.put(key(spec, input), new Entry(route, generation));
    }

    /**
     * 踢掉一条路由（下次重扫）。
     *
     * @param spec  规则
     * @param input {@code true} 表示输入方向
     */
    public void clear(ResourceSpec spec, boolean input) {
        this.entries.remove(key(spec, input));
    }

    /** 清空整张表（规则变更时）。 */
    public void clearAll() {
        this.entries.clear();
    }

    /** 当前路由条数（诊断用）。 */
    public int size() {
        return this.entries.size();
    }

    private static Key key(ResourceSpec spec, boolean input) {
        return new Key(input, spec.key(), spec.mode());
    }
}
