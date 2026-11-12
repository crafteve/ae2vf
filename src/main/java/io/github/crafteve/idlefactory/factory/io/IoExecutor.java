package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import appeng.api.storage.MEStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * 一个 IO 组的执行器：对一台机器按配置补料、抽产物。
 *
 * <p>面与 handler 的解析在 {@link IoPortResolver}；物品变体的展开在 {@link ItemCandidates}。
 * 本类只负责按规则跑事务、汇总本轮结果与逐规则原因。</p>
 *
 * <p>搬成功就记住这个面；{@link Reason#REJECTED}（有容量却不收）说明面不对，踢掉路由下次重扫。
 * 玩家改规则、方块被换、能力失效都会让路由整体作废（观测层的面表代数 +1）。</p>
 *
 * <p><b>物品的模糊规则</b>：{@link io.github.crafteve.idlefactory.factory.machine.MatchMode#IGNORE_COMPONENTS}
 * 会把一条规则展开成多个「一个变体一个适配器」，每个变体走一遍 {@link IoTransfer} 的事务逻辑；
 * 候选变体来自 {@link ItemVariantIndex}（输入）或机器库存（输出）。每 tick 的变体预算为
 * 单规则 8、单机器 16，输入/输出按侧分配，防止变体爆炸时一次打满；规则处理顺序按 tick
 * 轮转，避免后面的规则被饿死。</p>
 */
public final class IoExecutor {
    /**
     * 一次机器搬运的结果：只保留调用方真的会读的分量。
     *
     * <p>按资源的总量、侧级原因、输入是否堵塞曾经也在这里，但视图与排期都改读账本与结论后
     * 就没人读了——两份「为什么不动」只会打架，所以删掉。</p>
     *
     * @param fed          补进机器的总数
     * @param pulled       从机器抽出的总数
     * @param fedByRule    按规则拆分的补料量
     * @param pulledByRule 按规则拆分的抽取量
     * @param inputReasons 逐输入规则的本轮原因
     * @param outputReasons 逐输出规则的本轮原因
     * @param faces        逐规则本轮实际用的面，给单元的经验面与账本用
     */
    public record BlockRun(long fed, long pulled,
            Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule,
            Map<RuleKey, Reason> inputReasons,
            Map<RuleKey, Reason> outputReasons,
            Map<RuleKey, Direction> faces) {

        public BlockRun {
            // 逐规则原因会被快照读取，先冻结成只读，避免调用方后续改动影响已发出的视图。
            inputReasons = Map.copyOf(inputReasons);
            outputReasons = Map.copyOf(outputReasons);
            faces = Map.copyOf(faces);
        }

        /** 一共搬了多少。 */
        public long moved() {
            return this.fed + this.pulled;
        }

        /** 这一轮是否搬了东西。 */
        public boolean movedAnything() {
            return this.fed + this.pulled > 0L;
        }
    }

    private static Reason worse(@Nullable Reason current,
            Reason next) {
        if (current == null) {
            return next;
        }
        return next.severity() > current.severity() ? next : current;
    }

    private IoExecutor() {
        // 纯静态工具类
    }

    /**
     * 跑一台机器的一轮。
     *
     * @param storage    仓库
     * @param level      机器所在维度
     * @param pos        机器坐标
     * @param inputs     本轮要跑的输入单元（规划器已排好顺序）
     * @param outputs    本轮要跑的输出单元（规划器已排好顺序）
     * @param caps       能力句柄缓存；{@code null} 时直接查世界
     * @param variants   模糊物品规则的变体索引；{@code null} 时模糊输入退化为无候选
     * @param now          当前服务端 tick，给变体索引算 TTL
     * @param sharedBudget 本台机器本 tick 共享的变体预算；每个方块按序扣减
     * @return 本方块结果
     */
    public static BlockRun runBlock(MEStorage storage, ServerLevel level, BlockPos pos,
            List<IoUnit> inputs, List<IoUnit> outputs, @Nullable LevelCapabilityCache caps,
            @Nullable ItemVariantIndex variants, long now, int[] sharedBudget) {
        long fed = 0L;
        long pulled = 0L;
        Map<RuleKey, Long> fedByRule = new LinkedHashMap<>();
        Map<RuleKey, Long> pulledByRule = new LinkedHashMap<>();
        Map<RuleKey, Reason> inputReasons = new LinkedHashMap<>();
        Map<RuleKey, Reason> outputReasons = new LinkedHashMap<>();
        Map<RuleKey, Direction> faces = new LinkedHashMap<>();
        // 变体预算按侧分配：输出侧预留 4 个变体，输入侧每 tick 最多 16 - 4 = 12 个。
        // 输入循环先跑，共用一个池时两条模糊输入就能把 16 用光，输出规则每 tick 都被跳过；
        // 输出侧没有模糊物品规则时它不扣预算，输入可以拿满 16。
        boolean outputUsesVariants = usesVariants(outputs);
        int sideAllowance = outputUsesVariants
                ? ItemCandidates.MAX_VARIANTS_PER_INPUT_TICK
                : ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK;
        int[] inputBudget = {Math.min(sideAllowance, Math.max(0, sharedBudget[0]))};
        int inputQuota = inputBudget[0];

        // 顺序由规划器给定（游标轮转在规划器里），执行器只按清单跑。
        for (int ruleIndex = 0; ruleIndex < inputs.size(); ruleIndex++) {
            IoUnit unit = inputs.get(ruleIndex);
            ResourceSpec spec = unit.spec();
            RuleKey ruleKey = unit.key().rule();
            IoPortResolver.Resolved resolved = IoPortResolver.resolve(level, pos, spec, true,
                    caps, unit.digest().faceHint());
            if (resolved == null) {
                // 原因已经进账本，不再逐 tick 打日志：debug 级别下会按规则数刷屏。
                inputReasons.put(ruleKey, Reason.NO_FACE);
                continue;
            }
            if (resolved.face() != null) {
                faces.put(ruleKey, resolved.face());
            }
            Adapters adapters = ruleAdapters(storage, resolved, spec, true, variants, now,
                    inputBudget);
            if (adapters.starved()) {
                // 本侧额度被前面的规则用完：本轮跳过，不代表机器拒收，但原因必须可见。
                inputReasons.put(ruleKey, Reason.BUDGET_OUT);
                continue;
            }
            if (adapters.list().isEmpty()) {
                inputReasons.put(ruleKey, Reason.NO_MATERIAL);
                // 没有候选 = 仓库当前没有这个物品；机器侧并没有拒收，不能算「输入堵塞」。
                continue;
            }
            long moved = 0L;
            Reason reason = null;
            boolean accepted = false;
            for (ResourceAdapter adapter : adapters.list()) {
                IoResult result = IoTransfer.feed(storage, adapter);
                moved += result.moved();
                reason = worse(reason, result.reason());
                if (result.reason() == Reason.RUN || result.reason() == Reason.NO_MATERIAL) {
                    // NO_MATERIAL 只表示仓库没料，机器侧已经点头接收
                    accepted = true;
                }
                // 搬通了什么都不用做；拒收由分析器清掉经验面。原因只进账本，不逐 tick 打日志。
            }
            Reason combined = moved > 0L
                    ? Reason.RUN : (reason == null ? Reason.NO_MATERIAL : reason);
            inputReasons.put(ruleKey, combined);
            fed += moved;
            if (moved > 0L) {
                fedByRule.merge(ruleKey, moved, Long::sum);
            }
        }

        int inputUsed = inputQuota - inputBudget[0];
        sharedBudget[0] = Math.max(0, sharedBudget[0] - inputUsed);
        // 输出实际额度 = 16 - 输入实际用量，再与本台机器剩余共享预算取小。
        int[] outputBudget = {
                Math.min(ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK - inputUsed, sharedBudget[0])};
        int outputQuota = outputBudget[0];

        for (int ruleIndex = 0; ruleIndex < outputs.size(); ruleIndex++) {
            IoUnit unit = outputs.get(ruleIndex);
            ResourceSpec spec = unit.spec();
            RuleKey ruleKey = unit.key().rule();
            IoPortResolver.Resolved resolved = IoPortResolver.resolve(level, pos, spec, false,
                    caps, unit.digest().faceHint());
            if (resolved == null) {
                // 同输入侧：原因进账本，日志留给账本与界面。
                outputReasons.put(ruleKey, Reason.NO_FACE);
                continue;
            }
            if (resolved.face() != null) {
                faces.put(ruleKey, resolved.face());
            }
            Adapters adapters = ruleAdapters(storage, resolved, spec, false, variants, now,
                    outputBudget);
            if (adapters.starved()) {
                // 本侧额度耗尽：本轮不判断方向，交给下一 tick 的轮转；同样不能算机器堵塞。
                outputReasons.put(ruleKey, Reason.BUDGET_OUT);
                continue;
            }
            if (adapters.list().isEmpty()) {
                outputReasons.put(ruleKey, Reason.NO_MATERIAL);
                continue;
            }
            long moved = 0L;
            Reason reason = null;
            for (ResourceAdapter adapter : adapters.list()) {
                IoResult result = IoTransfer.pull(storage, adapter);
                moved += result.moved();
                reason = worse(reason, result.reason());
                // 搬通了什么都不用做；拒收由分析器清掉经验面。原因只进账本，不逐 tick 打日志。
            }
            Reason combined = moved > 0L
                    ? Reason.RUN : (reason == null ? Reason.NO_MATERIAL : reason);
            outputReasons.put(ruleKey, combined);
            pulled += moved;
            if (moved > 0L) {
                pulledByRule.merge(ruleKey, moved, Long::sum);
            }
        }

        int outputUsed = outputQuota - outputBudget[0];
        sharedBudget[0] = Math.max(0, sharedBudget[0] - outputUsed);

        return new BlockRun(fed, pulled, fedByRule, pulledByRule,
                inputReasons, outputReasons, faces);
    }

    /**
     * 一组搬运通道；{@code starved} 表示本侧预算耗尽，而不是真的没有候选。
     */
    private record Adapters(List<ResourceAdapter> list, boolean starved) {
    }

    /**
     * 按规则展开出一条或多条搬运通道。
     *
     * <p>非物品规则只有解析出的那一条；物品规则按精确/模糊展开：精确只有一条，模糊在输入侧
     * 取仓库变体索引、输出侧扫机器库存。</p>
     */
    private static Adapters ruleAdapters(MEStorage storage, IoPortResolver.Resolved resolved,
            ResourceSpec spec, boolean input, @Nullable ItemVariantIndex variants, long now,
            int[] budget) {
        if (!(resolved.adapter() instanceof ItemAdapter base)) {
            // 流体/能量没有变体概念，只有一条通道。
            return new Adapters(List.of(resolved.adapter()), false);
        }
        ItemCandidates.Candidates candidates = ItemCandidates.of(storage, base, spec, input,
                variants, now, budget);
        List<ResourceAdapter> result = new ArrayList<>(candidates.adapters());
        return new Adapters(result, candidates.budgetStarved());
    }

    /**
     * 这一侧的规则里是否有会扣变体预算的模糊物品规则。
     *
     * <p>只有模糊物品规则会进 {@link ItemCandidates} 展开并扣预算：精确物品规则直接返回单条
     * 通道，流体 / 能量根本不进变体展开。用它决定另一侧要不要留额度，避免按「规则列表非空」
     * 判断时把只有流体或精确物品输出的机器也算进去，白白扣掉输入额度。</p>
     *
     * @param units 一侧的单元
     * @return 存在模糊物品规则时 {@code true}
     */
    private static boolean usesVariants(List<IoUnit> units) {
        for (IoUnit unit : units) {
            if (unit.spec().fuzzy()) {
                return true;
            }
        }
        return false;
    }
}
