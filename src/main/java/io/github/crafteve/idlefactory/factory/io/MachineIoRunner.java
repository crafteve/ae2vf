package io.github.crafteve.idlefactory.factory.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.capability.LevelCapabilityCache;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 一个 IO 组的执行器：对一台机器按配置补料、抽产物。
 *
 * <p>面与 handler 的解析在 {@link RuleResolver}；物品变体的展开在 {@link ItemCandidates}。
 * 本类只负责按规则跑事务、汇总本轮结果与逐规则原因。</p>
 *
 * <p>搬成功就记住这个面；{@link IoResult.Reason#REJECTED}（有容量却不收）说明面不对，踢掉路由下次重扫。
 * 玩家改规则、方块被换、能力失效都会让路由整体作废（观测层的面表代数 +1）。</p>
 *
 * <p><b>物品的模糊规则</b>：{@link io.github.crafteve.idlefactory.factory.machine.MatchMode#IGNORE_COMPONENTS}
 * 会把一条规则展开成多个「一个变体一个适配器」，每个变体走一遍 {@link IoTransfer} 的事务逻辑；
 * 候选变体来自 {@link ItemVariantIndex}（输入）或机器库存（输出）。每 tick 的变体预算为
 * 单规则 8、单机器 16，输入/输出按侧分配，防止变体爆炸时一次打满；规则处理顺序按 tick
 * 轮转，避免后面的规则被饿死。</p>
 */
public final class MachineIoRunner {
    /**
     * 一次组级搬运的结果。
     *
     * @param fed         补进机器的总数
     * @param pulled      从机器抽出的总数
     * @param fedByKey    按资源拆分的补料量
     * @param pulledByKey 按资源拆分的抽取量
     * @param fedByRule   按规则拆分的补料量
     * @param pulledByRule 按规则拆分的抽取量
     * @param lastReason  没搬动时的最后一个原因
     * @param inputFull   所有输入规则都无法接收
     * @param inputReason 输入侧原因
     * @param outputReason 输出侧原因
     * @param inputReasons 逐输入规则的本轮原因，供界面显示每条规则的诊断
     * @param outputReasons 逐输出规则的本轮原因
     */
    public record BlockRun(long fed, long pulled,
            Map<AEKey, Long> fedByKey, Map<AEKey, Long> pulledByKey,
            Map<RuleKey, Long> fedByRule, Map<RuleKey, Long> pulledByRule,
            IoResult.Reason lastReason, boolean inputFull,
            IoResult.Reason inputReason, IoResult.Reason outputReason,
            Map<RuleKey, IoResult.Reason> inputReasons,
            Map<RuleKey, IoResult.Reason> outputReasons) {

        public BlockRun {
            // 逐规则原因会被快照读取，先冻结成只读，避免调用方后续改动影响已发出的视图。
            inputReasons = Map.copyOf(inputReasons);
            outputReasons = Map.copyOf(outputReasons);
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

    private static IoResult.Reason worse(@Nullable IoResult.Reason current,
            IoResult.Reason next) {
        if (current == null) {
            return next;
        }
        return severity(next) > severity(current) ? next : current;
    }

    private static int severity(IoResult.Reason reason) {
        return switch (reason) {
            case REJECTED -> 4;
            case NO_HANDLER -> 3;
            case NO_SPACE -> 2;
            case NO_ITEM -> 1;
            case OK -> 0;
        };
    }

    private static IoResult.Reason combine(@Nullable IoResult.Reason input,
            @Nullable IoResult.Reason output) {
        if (output != null && output != IoResult.Reason.OK) {
            return output;
        }
        if (input != null && input != IoResult.Reason.OK) {
            return input;
        }
        return IoResult.Reason.OK;
    }

    private MachineIoRunner() {
        // 纯静态工具类
    }

    /**
     * 跑一台机器的一轮。
     *
     * @param storage    仓库
     * @param level      机器所在维度
     * @param pos        机器坐标
     * @param slot       本台机器在本 tick 表单上的槽位
     * @param caps       能力句柄缓存；{@code null} 时直接查世界
     * @param routes     面路由表；{@code null} 时每次扫面
     * @param generation 观测层的面表代数
     * @param variants   模糊物品规则的变体索引；{@code null} 时模糊输入退化为无候选
     * @param now          当前服务端 tick，给变体索引算 TTL 与规则轮转
     * @param sharedBudget 本台机器本 tick 共享的变体预算；每个方块按序扣减
     * @return 本方块结果
     */
    public static BlockRun runBlock(MEStorage storage, ServerLevel level, BlockPos pos,
            IoForm.Slot slot, @Nullable LevelCapabilityCache caps, @Nullable RouteTable routes,
            long generation, @Nullable ItemVariantIndex variants, long now, int[] sharedBudget) {
        long fed = 0L;
        long pulled = 0L;
        Map<AEKey, Long> fedByKey = new LinkedHashMap<>();
        Map<AEKey, Long> pulledByKey = new LinkedHashMap<>();
        Map<RuleKey, Long> fedByRule = new LinkedHashMap<>();
        Map<RuleKey, Long> pulledByRule = new LinkedHashMap<>();
        Map<RuleKey, IoResult.Reason> inputReasons = new LinkedHashMap<>();
        Map<RuleKey, IoResult.Reason> outputReasons = new LinkedHashMap<>();
        IoResult.Reason inputReason = null;
        IoResult.Reason outputReason = null;
        boolean anyInputAccepted = false;
        boolean anyInputRule = false;
        List<ResourceSpec> inputs = slot.inputSpecs();
        List<ResourceSpec> outputs = slot.outputSpecs();
        // 变体预算按侧分配：输出侧预留 4 个变体，输入侧每 tick 最多 16 - 4 = 12 个。
        // 输入循环先跑，共用一个池时两条模糊输入就能把 16 用光，输出规则每 tick 都被跳过；
        // 输出侧没有模糊物品规则时它不扣预算，输入可以拿满 16。
        boolean outputUsesVariants = usesVariants(outputs);
        int sideAllowance = outputUsesVariants
                ? ItemCandidates.MAX_VARIANTS_PER_INPUT_TICK
                : ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK;
        int[] inputBudget = {Math.min(sideAllowance, Math.max(0, sharedBudget[0]))};
        int inputQuota = inputBudget[0];

        // 规则按 tick 轮转起点：同一侧的多条模糊规则共享本侧额度时，后面的规则不会被前面的永久挤掉。
        int inputStart = inputs.isEmpty() ? 0 : (int) Math.floorMod(now, (long) inputs.size());
        for (int ruleIndex = 0; ruleIndex < inputs.size(); ruleIndex++) {
            ResourceSpec spec = inputs.get((inputStart + ruleIndex) % inputs.size());
            anyInputRule = true;
            RuleKey ruleKey = new RuleKey(true, spec.key(), spec.face(), spec.mode());
            RuleResolver.Resolved resolved = RuleResolver.resolve(level, pos, spec, true, caps, routes,
                    generation);
            if (resolved == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                inputReason = worse(inputReason, IoResult.Reason.NO_HANDLER);
                inputReasons.put(ruleKey, IoResult.Reason.NO_HANDLER);
                continue;
            }
            Adapters adapters = ruleAdapters(storage, resolved, spec, true, variants, now,
                    inputBudget);
            if (adapters.starved()) {
                // 本侧额度被前面的规则用完：本轮跳过，不写入原因；也不代表机器拒收。
                anyInputAccepted = true;
                continue;
            }
            if (adapters.list().isEmpty()) {
                inputReasons.put(ruleKey, IoResult.Reason.NO_ITEM);
                inputReason = worse(inputReason, IoResult.Reason.NO_ITEM);
                // 没有候选 = 仓库当前没有这个物品；机器侧并没有拒收，不能算「输入堵塞」。
                anyInputAccepted = true;
                continue;
            }
            long moved = 0L;
            IoResult.Reason reason = null;
            boolean accepted = false;
            for (ResourceAdapter adapter : adapters.list()) {
                IoResult result = IoTransfer.feed(storage, adapter);
                moved += result.moved();
                reason = worse(reason, result.reason());
                if (result.reason() == IoResult.Reason.OK || result.reason() == IoResult.Reason.NO_ITEM) {
                    // NO_ITEM 只表示仓库没料，机器侧已经点头接收
                    accepted = true;
                }
                if (result.moved() > 0L) {
                    remember(routes, spec, true, resolved, generation);
                } else if (result.reason() == IoResult.Reason.REJECTED) {
                    // 有容量却不收：这个面不对，下次重扫
                    clear(routes, spec, true);
                } else {
                    IdleFactory.LOGGER.debug(
                            "IdleFactory IO feed blocked: machine={} spec={} reason={} capacity={}",
                            pos.toShortString(), spec.describe(), result.reason(),
                            adapter.capacity());
                }
            }
            anyInputAccepted |= accepted;
            IoResult.Reason combined = moved > 0L
                    ? IoResult.Reason.OK : (reason == null ? IoResult.Reason.NO_ITEM : reason);
            inputReasons.put(ruleKey, combined);
            fed += moved;
            if (moved > 0L) {
                fedByKey.merge(spec.key(), moved, Long::sum);
                fedByRule.merge(ruleKey, moved, Long::sum);
            } else {
                inputReason = worse(inputReason, combined);
            }
        }

        int inputUsed = inputQuota - inputBudget[0];
        sharedBudget[0] = Math.max(0, sharedBudget[0] - inputUsed);
        // 输出实际额度 = 16 - 输入实际用量，再与本台机器剩余共享预算取小。
        int[] outputBudget = {
                Math.min(ItemCandidates.MAX_VARIANTS_PER_MACHINE_TICK - inputUsed, sharedBudget[0])};
        int outputQuota = outputBudget[0];

        int outputStart = outputs.isEmpty() ? 0 : (int) Math.floorMod(now + 1L, (long) outputs.size());
        for (int ruleIndex = 0; ruleIndex < outputs.size(); ruleIndex++) {
            ResourceSpec spec = outputs.get((outputStart + ruleIndex) % outputs.size());
            RuleKey ruleKey = new RuleKey(false, spec.key(), spec.face(), spec.mode());
            RuleResolver.Resolved resolved = RuleResolver.resolve(level, pos, spec, false, caps, routes,
                    generation);
            if (resolved == null) {
                IdleFactory.LOGGER.debug("IdleFactory IO: no adapter, machine={} spec={}",
                        pos.toShortString(), spec.describe());
                outputReason = worse(outputReason, IoResult.Reason.NO_HANDLER);
                outputReasons.put(ruleKey, IoResult.Reason.NO_HANDLER);
                continue;
            }
            Adapters adapters = ruleAdapters(storage, resolved, spec, false, variants, now,
                    outputBudget);
            if (adapters.starved()) {
                // 本侧额度耗尽：本轮不判断方向，交给下一 tick 的轮转；同样不能算机器堵塞。
                continue;
            }
            if (adapters.list().isEmpty()) {
                outputReasons.put(ruleKey, IoResult.Reason.NO_ITEM);
                outputReason = worse(outputReason, IoResult.Reason.NO_ITEM);
                continue;
            }
            long moved = 0L;
            IoResult.Reason reason = null;
            for (ResourceAdapter adapter : adapters.list()) {
                IoResult result = IoTransfer.pull(storage, adapter);
                moved += result.moved();
                reason = worse(reason, result.reason());
                if (result.moved() > 0L) {
                    remember(routes, spec, false, resolved, generation);
                } else if (result.reason() == IoResult.Reason.REJECTED) {
                    clear(routes, spec, false);
                } else {
                    IdleFactory.LOGGER.debug("IdleFactory IO pull blocked: machine={} spec={} reason={}",
                            pos.toShortString(), spec.describe(), result.reason());
                }
            }
            IoResult.Reason combined = moved > 0L
                    ? IoResult.Reason.OK : (reason == null ? IoResult.Reason.NO_ITEM : reason);
            outputReasons.put(ruleKey, combined);
            pulled += moved;
            if (moved > 0L) {
                pulledByKey.merge(spec.key(), moved, Long::sum);
                pulledByRule.merge(ruleKey, moved, Long::sum);
            } else {
                outputReason = worse(outputReason, combined);
            }
        }

        int outputUsed = outputQuota - outputBudget[0];
        sharedBudget[0] = Math.max(0, sharedBudget[0] - outputUsed);

        boolean inputFull = anyInputRule && !anyInputAccepted;
        IoResult.Reason lastReason = combine(inputReason, outputReason);
        return new BlockRun(fed, pulled, fedByKey, pulledByKey,
                fedByRule, pulledByRule, lastReason, inputFull,
                inputReason == null ? IoResult.Reason.OK : inputReason,
                outputReason == null ? IoResult.Reason.OK : outputReason,
                inputReasons, outputReasons);
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
    private static Adapters ruleAdapters(MEStorage storage, RuleResolver.Resolved resolved,
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
     * @param specs 一侧的规则
     * @return 存在模糊物品规则时 {@code true}
     */
    private static boolean usesVariants(List<ResourceSpec> specs) {
        for (ResourceSpec spec : specs) {
            if (spec.fuzzy()) {
                return true;
            }
        }
        return false;
    }

    private static void remember(@Nullable RouteTable routes, ResourceSpec spec, boolean input,
            RuleResolver.Resolved resolved, long generation) {
        // 物品规则不记路由：无向句柄可能只读（Mekanism），模糊变体也可能落在不同面；
        // 每次按当前物品重新探面，避免把某一条变体或某一侧钉死。
        if (routes == null || spec.face() != null || (spec.key() instanceof AEItemKey)) {
            return;
        }
        routes.remember(spec, input, new RouteTable.Route(resolved.face()), generation);
    }

    private static void clear(@Nullable RouteTable routes, ResourceSpec spec, boolean input) {
        if (routes != null && spec.face() == null) {
            routes.clear(spec, input);
        }
    }
}
