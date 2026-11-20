package io.github.crafteve.idlefactory.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryLifecycle;

import io.github.crafteve.idlefactory.network.payload.ViewTableDiffS2C;
import io.github.crafteve.idlefactory.network.codec.ViewTableCodec;
import io.github.crafteve.idlefactory.network.payload.ViewTableS2C;
import io.github.crafteve.idlefactory.view.ViewHeader;
import io.github.crafteve.idlefactory.view.ViewIoRule;
import io.github.crafteve.idlefactory.view.ViewMachine;
import io.github.crafteve.idlefactory.view.ViewNode;
import io.github.crafteve.idlefactory.view.ViewNodeCore;
import io.github.crafteve.idlefactory.view.ViewNodeDiff;
import io.github.crafteve.idlefactory.view.ViewTable;
import io.github.crafteve.idlefactory.view.ViewTableBuilder;
import io.github.crafteve.idlefactory.view.ViewTableDiff;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 视图表的唯一发送出口：会话、差量基线与全量分页都在这里。
 *
 * <p>版本由<b>整表比较</b>驱动，不依赖 {@code NodeLibrary.revision}：每次 sync / refresh 都重建
 * 当前表，与基线逐字段比较。这样观测变化（掉线、恢复、开工、仓库离线）也会被捕获——它们本来
 * 就不改配置版本。</p>
 *
 * <p>节奏一（草稿 23）：差量只在终端打开期间生效；最后一个会话关闭时丢弃基线，重开必走全量。
 * 基线与会话都是服务端内存状态，不写档。</p>
 */
public final class ViewPort {
    /**
     * 每个全量页的编码后字节预算。
     *
     * <p>按字节而非节点数分页：一个节点的规则、成员数量差别很大，按节点数切会在大节点上
     * 撞到包大小上限。16 KiB 留足余量，又不会把 4096 个节点切成上千包。</p>
     */
    private static final int PAGE_BUDGET_BYTES = 16 * 1024;

    /** 工厂 id → 差量基线（上一次已发送的表与版本）。 */
    private static final Map<UUID, Baseline> BASELINES = new HashMap<>();
    /** 玩家 id → 会话（打开中的终端）。 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ViewPort() {
        // 纯静态出口
    }

    /**
     * 一个工厂的差量基线。
     *
     * <p>只在有会话时存在；最后一个会话关闭时整条丢弃，下次打开从全量重新开始。</p>
     */
    private static final class Baseline {
        private long revision;
        private ViewTable table;

        private Baseline(long revision, ViewTable table) {
            this.revision = revision;
            this.table = table;
        }
    }

    /** 一个打开中的终端：它属于哪个工厂、容器 id、已收到哪一版。 */
    private static final class Session {
        private UUID factoryId;
        private int containerId;
        private long revision;

        private Session(UUID factoryId, int containerId, long revision) {
            this.factoryId = factoryId;
            this.containerId = containerId;
            this.revision = revision;
        }
    }

    /**
     * 客户端请求同步：按客户端版本决定回全量还是差量。
     *
     * @param player        请求的玩家
     * @param containerId   菜单容器 id
     * @param knownRevision 客户端已知版本；{@code 0} 表示还没有表
     */
    public static void sync(ServerPlayer player, int containerId, long knownRevision) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime == null) {
            sendEmpty(player, containerId);
            return;
        }
        Optional<Factory> found = runtime.manager().byOwner(player.getUUID());
        if (found.isEmpty()) {
            sendEmpty(player, containerId);
            return;
        }
        Factory factory = found.get();
        ViewTable current = build(runtime, factory, player.serverLevel().getGameTime());
        Baseline baseline = BASELINES.get(factory.id());
        Session session = session(player.getUUID(), factory.id(), containerId, knownRevision);
        if (baseline == null) {
            // 首次打开：建立基线并从 1 开始版本，全量下发。
            baseline = new Baseline(1L, current);
            BASELINES.put(factory.id(), baseline);
            session.revision = baseline.revision;
            sendFull(player, containerId, baseline);
            return;
        }
        if (!current.equals(baseline.table)) {
            advance(factory.id(), baseline, current, player.registryAccess());
        }
        if (session.revision != baseline.revision) {
            // 客户端错过中间某一版：回全量，不补历史。
            session.revision = baseline.revision;
            sendFull(player, containerId, baseline);
        }
    }

    /**
     * 写命令成功后刷新一个工厂：重建当前表，与基线比较，有变化就给所有在线会话推差量。
     *
     * <p>没有会话就没有基线，直接返回——没打开终端时不必构建视图表。</p>
     *
     * @param factory 发生写入的工厂
     */
    public static void refresh(Factory factory) {
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime == null) {
            return;
        }
        Baseline baseline = BASELINES.get(factory.id());
        if (baseline == null) {
            return;
        }
        ViewTable current = build(runtime, factory, runtime.server().overworld().getGameTime());
        if (!current.equals(baseline.table)) {
            advance(factory.id(), baseline, current, runtime.server().registryAccess());
        }
    }

    /**
     * 关闭一个会话；最后一个会话关闭时丢弃该工厂的基线。
     *
     * @param player 关闭终端的玩家
     */
    public static void close(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session == null) {
            return;
        }
        for (Session other : SESSIONS.values()) {
            if (other.factoryId.equals(session.factoryId)) {
                return;
            }
        }
        BASELINES.remove(session.factoryId);
    }

    /** 服务端停止时清空全部会话与基线（静态状态不能跨实例存活）。 */
    public static void clear() {
        BASELINES.clear();
        SESSIONS.clear();
    }

    /**
     * 推进一版：算差量、更新基线、按会话版本推送。
     *
     * @param factoryId  工厂 id
     * @param baseline   基线
     * @param current    当前表
     * @param registries 注册表访问，用于编码预算估算
     */
    private static void advance(UUID factoryId, Baseline baseline, ViewTable current,
            RegistryAccess registries) {
        ViewTableDiff diff = diff(baseline.table, current, baseline.revision,
                baseline.revision + 1L);
        baseline.revision = diff.revision();
        baseline.table = current;
        FactoryLifecycle runtime = FactoryLifecycle.get();
        if (runtime == null || runtime.server() == null) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (!session.factoryId.equals(factoryId) || session.revision != diff.baseRevision()) {
                // 版本对不上的会话自己会请求全量，这里不补历史。
                continue;
            }
            ServerPlayer target = runtime.server().getPlayerList().getPlayer(entry.getKey());
            if (target == null) {
                continue;
            }
            session.revision = diff.revision();
            PacketDistributor.sendToPlayer(target,
                    new ViewTableDiffS2C(session.containerId, diff.baseRevision(), diff.revision(),
                            diff));
        }
    }

    /**
     * 取或建一个会话。
     *
     * <p>客户端自报的 {@code knownRevision} 优先：它刚重建数据源时服务端还记着旧版本，
     * 以客户端为准才能正确地回全量。</p>
     */
    private static Session session(UUID playerId, UUID factoryId, int containerId,
            long knownRevision) {
        Session session = SESSIONS.get(playerId);
        if (session == null) {
            session = new Session(factoryId, containerId, knownRevision);
            SESSIONS.put(playerId, session);
        }
        session.factoryId = factoryId;
        session.containerId = containerId;
        session.revision = knownRevision;
        return session;
    }

    /**
     * 把一张表切成分页，每页节点完整。
     *
     * <p>字节预算按节点单独编码后的长度累加；单节点超过预算时它独占一页。这样客户端永远
     * 不会看到"半个节点"，也就不需要跨页拼装。</p>
     */
    private static List<ViewTable> paginate(ViewTable table, RegistryAccess registries) {
        List<ViewTable> pages = new ArrayList<>();
        List<ViewNode> page = new ArrayList<>();
        int pageBytes = 0;
        for (ViewNode node : table.nodes()) {
            int size = encodedSize(node, registries);
            if (!page.isEmpty() && pageBytes + size > PAGE_BUDGET_BYTES) {
                pages.add(new ViewTable(table.header(), page));
                page = new ArrayList<>();
                pageBytes = 0;
            }
            page.add(node);
            pageBytes += size;
        }
        pages.add(new ViewTable(table.header(), page));
        return pages;
    }

    /**
     * 一个节点编码后的字节数；只用于分页预算，不参与线协议。
     *
     * <p>全量下发本来就要编码一次，这里多编一次换的是"按字节分页"的正确性；全量只在开界面
     * 与版本对不上时发生，频率是秒级而不是每 tick。</p>
     */
    private static int encodedSize(ViewNode node, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        ViewTableCodec.writeNode(buf, node);
        return buf.readableBytes();
    }

    /**
     * 回一份全量。
     *
     * @param player      目标玩家
     * @param containerId 容器 id
     * @param baseline    当前基线
     */
    private static void sendFull(ServerPlayer player, int containerId, Baseline baseline) {
        List<ViewTable> pages = paginate(baseline.table, player.registryAccess());
        for (int index = 0; index < pages.size(); index++) {
            PacketDistributor.sendToPlayer(player, new ViewTableS2C(containerId, baseline.revision,
                    index, pages.size(), pages.get(index)));
        }
    }

    /**
     * 回一份"玩家还没有工厂"的空表。
     *
     * <p>空表不建基线、不占版本：玩家建好工厂后第一次 sync 会重新从 1 开始。</p>
     */
    private static void sendEmpty(ServerPlayer player, int containerId) {
        PacketDistributor.sendToPlayer(player,
                new ViewTableS2C(containerId, 0L, 0, 1, ViewTable.empty()));
    }

    private static ViewTable build(FactoryLifecycle runtime, Factory factory, long now) {
        return ViewTableBuilder.build(factory, runtime.observations(), runtime.formFor(factory),
                runtime.nodeManager(), now);
    }

    /**
     * 算两版表之间的差量。
     *
     * @param before      旧表
     * @param after       新表
     * @param baseRevision 旧版本
     * @param revision    新版本
     * @return 差量
     */
    private static ViewTableDiff diff(ViewTable before, ViewTable after, long baseRevision,
            long revision) {
        ViewHeader header = before.header().equals(after.header()) ? null : after.header();
        Map<UUID, ViewNode> oldNodes = indexNodes(before);
        Map<UUID, ViewNode> newNodes = indexNodes(after);
        List<ViewNodeDiff> diffs = new ArrayList<>();
        for (ViewNode node : after.nodes()) {
            ViewNode old = oldNodes.get(node.nodeId());
            if (old == null) {
                // 新节点：一次带全，客户端不需要再请求它的明细。
                diffs.add(new ViewNodeDiff(node.nodeId(), false, node.core(), node.rules(),
                        List.of(), node.machines(), List.of()));
                continue;
            }
            ViewNodeDiff nodeDiff = diffNode(old, node);
            if (nodeDiff != null) {
                diffs.add(nodeDiff);
            }
        }
        for (ViewNode node : before.nodes()) {
            if (!newNodes.containsKey(node.nodeId())) {
                diffs.add(new ViewNodeDiff(node.nodeId(), true, null, List.of(), List.of(),
                        List.of(), List.of()));
            }
        }
        return new ViewTableDiff(baseRevision, revision, header, diffs);
    }

    /**
     * 算一个节点的差异；没变返回 {@code null}。
     *
     * <p>规则按 {@link ViewIoRule.Key}、机器按 {@code machineId} 对齐：删一条规则不影响机器行，
     * 删一台机器不影响规则行。</p>
     */
    @Nullable
    private static ViewNodeDiff diffNode(ViewNode before, ViewNode after) {
        ViewNodeCore core = before.core().equals(after.core()) ? null : after.core();
        Map<ViewIoRule.Key, ViewIoRule> oldRules = indexRules(before);
        Map<ViewIoRule.Key, ViewIoRule> newRules = indexRules(after);
        List<ViewIoRule> ruleUpserts = new ArrayList<>();
        for (ViewIoRule rule : after.rules()) {
            ViewIoRule old = oldRules.get(rule.identity());
            if (old == null || !old.equals(rule)) {
                ruleUpserts.add(rule);
            }
        }
        List<ViewIoRule.Key> ruleRemoves = new ArrayList<>();
        for (ViewIoRule.Key key : oldRules.keySet()) {
            if (!newRules.containsKey(key)) {
                ruleRemoves.add(key);
            }
        }
        Map<UUID, ViewMachine> oldMachines = indexMachines(before);
        Map<UUID, ViewMachine> newMachines = indexMachines(after);
        List<ViewMachine> machineUpserts = new ArrayList<>();
        for (ViewMachine machine : after.machines()) {
            ViewMachine old = oldMachines.get(machine.machineId());
            if (old == null || !old.equals(machine)) {
                machineUpserts.add(machine);
            }
        }
        List<UUID> machineRemoves = new ArrayList<>();
        for (UUID machineId : oldMachines.keySet()) {
            if (!newMachines.containsKey(machineId)) {
                machineRemoves.add(machineId);
            }
        }
        if (core == null && ruleUpserts.isEmpty() && ruleRemoves.isEmpty()
                && machineUpserts.isEmpty() && machineRemoves.isEmpty()) {
            return null;
        }
        return new ViewNodeDiff(after.nodeId(), false, core, ruleUpserts, ruleRemoves,
                machineUpserts, machineRemoves);
    }

    private static Map<UUID, ViewNode> indexNodes(ViewTable table) {
        Map<UUID, ViewNode> nodes = new LinkedHashMap<>();
        for (ViewNode node : table.nodes()) {
            nodes.put(node.nodeId(), node);
        }
        return nodes;
    }

    private static Map<ViewIoRule.Key, ViewIoRule> indexRules(ViewNode node) {
        Map<ViewIoRule.Key, ViewIoRule> rules = new LinkedHashMap<>();
        for (ViewIoRule rule : node.rules()) {
            rules.put(rule.identity(), rule);
        }
        return rules;
    }

    private static Map<UUID, ViewMachine> indexMachines(ViewNode node) {
        Map<UUID, ViewMachine> machines = new LinkedHashMap<>();
        for (ViewMachine machine : node.machines()) {
            machines.put(machine.machineId(), machine);
        }
        return machines;
    }
}
