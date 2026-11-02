package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.io.MachineRunner;
import io.github.crafteve.idlefactory.factory.links.LinkRole;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;
import io.github.crafteve.idlefactory.factory.node.NodeManager;
import io.github.crafteve.idlefactory.factory.observation.Observation;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.BlockProbe;
import io.github.crafteve.idlefactory.factory.probe.Health;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 机器接线：准星接入、断开、删除，以及删除时的样板退回与执行器清理。
 *
 * <p>它是机器库写操作的唯一出口：三处删除调用点（命令确认、界面删除、连锁断开）只负责
 * 取参与出文案，真正的副作用在 {@link #removeMachine} 里一次做完——清失联标记、退样板、
 * 移出机器库、拆虚拟节点、清执行器瞬态。</p>
 *
 * <p>本类不持有运行时单例：档案柜、发布回调、节点与执行器都从构造注入。</p>
 */
final class MachineLinkService {
    private final FactoryManager manager;
    private final NodeManager nodeManager;
    private final MachineRunner machineRunner;
    private final MachineLibraryService machineLibrary;
    @Nullable
    private final ObservationService observations;
    private final MinecraftServer server;

    /**
     * @param manager       工厂档案柜
     * @param nodeManager   虚拟节点管理器
     * @param machineRunner 执行器
     * @param machineLibrary 机器库写入口（落盘由它统一触发）
     * @param observations  观测服务；服务端未就绪时为 {@code null}
     * @param server        服务端
     */
    MachineLinkService(FactoryManager manager, NodeManager nodeManager,
            MachineRunner machineRunner, MachineLibraryService machineLibrary,
            @Nullable ObservationService observations, MinecraftServer server) {
        this.manager = manager;
        this.nodeManager = nodeManager;
        this.machineRunner = machineRunner;
        this.machineLibrary = machineLibrary;
        this.observations = observations;
        this.server = server;
    }

    /**
     * 把准星选中的机器接入玩家自己的工厂。
     *
     * <p>只接受「方块实体 + 至少提供 {@code IItemHandler} / {@code IFluidHandler}」的方块。
     * id 由维度 + 坐标派生，重复接入会自然去重。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 失败）
     */
    public int connectMachine(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 " + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            FactoryMessages.err(player, "这个方块没有方块实体");
            return 0;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            FactoryMessages.err(player, "服务端未就绪");
            return 0;
        }
        // 指令路径也是绑定入口，必须和绑定杖走同一套占用规则；自占同角色交给下面的
        // byId 判断（可能是数据修复，也可能是重复 connect）。
        LinkGate gate = LinkGate.of(this.manager);
        LinkGate.ClaimCheck claim =
                gate.check(BlockKey.of(level.dimension(), pos), factory.id(), LinkRole.MACHINE);
        if (claim.blocked()) {
            FactoryMessages.err(player, LinkGateMessages.gateMessage(claim));
            return 0;
        }
        MachineRef target = MachineRef.of(level.dimension(), pos);
        BlockProbe.Presence presence = BlockProbe.presence(server,
                BlockKey.of(target.dimension(), target.pos()));
        if (presence.health() != Health.PRESENT || presence.kinds().isEmpty()) {
            FactoryMessages.err(player, "这个方块没有可搬运的存储（需要物品 / 流体 / 能量能力）");
            return 0;
        }
        Optional<Machine> existing = factory.config().machines().byId(target.id());
        if (existing.isPresent()) {
            FactoryMessages.err(player, "这台机器已经接入过了：" + existing.get().ref().shortId());
            return 0;
        }
        MachineLibraryService.Batch batch = this.machineLibrary.begin(factory);
        MachineLibrary.AddResult added = batch.add(target);
        if (added != MachineLibrary.AddResult.ADDED) {
            FactoryMessages.err(player, added == MachineLibrary.AddResult.FULL
                    ? "机器库已满（上限 " + MachineLibrary.MAX_MACHINES + " 台）"
                    : "这台机器已经接入过了：" + target.shortId());
            return 0;
        }
        refreshObservation(target);
        batch.close();
        FactoryMessages.ok(player, "已接入机器：" + target.shortId() + " @ " + level.dimension().location()
                + " " + pos.toShortString() + " [" + presence.describe() + "]");
        return 1;
    }

    /**
     * 删除准星选中的机器；有 IO 规则时先要求确认。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public int disconnectMachine(ServerPlayer player) {
        return deleteMachine(player, DeletePolicy.PROMPT);
    }

    /**
     * 确认删除准星选中的机器。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public int confirmDisconnectMachine(ServerPlayer player) {
        return deleteMachine(player, DeletePolicy.CONFIRMED);
    }

    /**
     * 按短 id 删除机器；有 IO 规则时先要求确认。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public int disconnectMachine(ServerPlayer player, String shortId) {
        return deleteMachine(player, shortId, DeletePolicy.PROMPT);
    }

    /**
     * 按短 id 确认删除机器。
     *
     * @param player  发起请求的玩家
     * @param shortId 短 id（前 8 位）
     * @return 命令结果
     */
    public int confirmDisconnectMachine(ServerPlayer player, String shortId) {
        return deleteMachine(player, shortId, DeletePolicy.CONFIRMED);
    }

    private int deleteMachine(ServerPlayer player, DeletePolicy policy) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        BlockPos pos = LinkTargetResolver.pickedBlock(player);
        if (pos == null) {
            FactoryMessages.err(player, "没有对准方块（射线 " + (int) LinkTargetResolver.REACH + " 格）");
            return 0;
        }
        ServerLevel level = player.serverLevel();
        Machine target = factory.config().machines().byPosition(level.dimension(), pos);
        if (target == null) {
            FactoryMessages.err(player, "这个位置没有接入的机器");
            return 0;
        }
        return reportDelete(player, factory, target, policy,
                "/idf factory disconnect confirm");
    }

    private int deleteMachine(ServerPlayer player, String shortId, DeletePolicy policy) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        Optional<Machine> target = factory.config().machines().byShortId(shortId);
        if (target.isEmpty()) {
            FactoryMessages.err(player, "找不到目标 " + shortId + "（或前缀有歧义）");
            return 0;
        }
        return reportDelete(player, factory, target.get(), policy,
                "/idf factory disconnect " + shortId + " confirm");
    }

    /**
     * 删除策略：决定「有 IO 规则」的机器怎么处理。
     *
     * <p>不用布尔参数：三处调用点需要三种语义，一个 {@code confirmed} 布尔只能表达两种，
     * 连锁路径只能靠调用方自己先跳过，正是旧实现漏掉执行器清理的根源。</p>
     */
    enum DeletePolicy {
        /** 命令首次删除：有规则只提示确认，不删。 */
        PROMPT,
        /** 已确认：有规则也删；命令 confirm 与界面删除都走这里。 */
        CONFIRMED,
        /** 连锁断开：有规则就跳过，规则不能批量丢。 */
        SKIP_WITH_RULES
    }

    /**
     * 一次删除尝试的结果。
     *
     * @param deleted  是否真的删掉了；{@code false} 只表示「因策略要求未删」，
     *                 「找不到机器」由调用方在进入出口前用 err 返回，不混进这个分支
     * @param rules    删除前的规则条数
     * @param patterns 退回的样板数
     */
    record DeleteOutcome(boolean deleted, int rules, int patterns) {
    }

    /**
     * 删除一台机器的唯一出口。
     *
     * <p>三处调用点（命令确认、界面删除、连锁断开）只负责取参与出文案，真正的副作用在这里
     * 一次做完：清失联标记、退样板、移出机器库、拆虚拟节点、清执行器瞬态。执行器瞬态必须清：
     * {@code RunnerState} 按机器 id 存睡眠与面路由，不删的话同 id 重连会继承旧睡眠。</p>
     *
     * <p>本方法不落盘：批量句柄由调用方传入，整批删除结束后由调用方 {@code close()} 一次，
     * 连锁删除才不会变成 N 次存盘。</p>
     *
     * @param factory  工厂
     * @param machine  机器
     * @param policy   删除策略
     * @param receiver 样板优先接收者；{@code null} 表示找工厂拥有者
     * @param batch    本次操作的机器库批量句柄
     * @return 结果；{@code deleted=false} 表示策略要求跳过
     */
    DeleteOutcome removeMachine(Factory factory, Machine machine,
            DeletePolicy policy, @Nullable ServerPlayer receiver, MachineLibraryService.Batch batch) {
        int rules = machine.ruleCount();
        boolean blocked = switch (policy) {
            case PROMPT, SKIP_WITH_RULES -> rules > 0;
            case CONFIRMED -> false;
        };
        if (blocked) {
            return new DeleteOutcome(false, rules, 0);
        }
        factory.live().clearMachineMissing(machine.id());
        int returned = returnPatterns(factory, machine, receiver);
        batch.remove(machine.id());
        // 先拆节点：机器已经不归工厂管了，不能再让 CPU 往里派单
        this.nodeManager.removeMachine(machine.id());
        // 睡眠、面路由都是按机器 id 存的内存状态；不清掉，同 id 重连会继承旧睡眠
        this.machineRunner.forget(machine.id());
        return new DeleteOutcome(true, rules, returned);
    }

    /**
     * 删除成功后的回执文案；命令与界面共用以保证逐字一致。
     *
     * @param machine 被删掉的机器
     * @param outcome 删除结果
     * @return 如「已删除机器 [ab12cd34]（2 条 IO 规则已删除）（退回 1 块样板）」
     */
    private static String deletedText(Machine machine, DeleteOutcome outcome) {
        String rulesText = outcome.rules() > 0 ? "（" + outcome.rules() + " 条 IO 规则已删除）" : "";
        String patternText = outcome.patterns() > 0 ? "（退回 " + outcome.patterns() + " 块样板）" : "";
        return "已删除机器 [" + machine.ref().shortId() + "]" + rulesText + patternText;
    }

    /**
     * 命令侧删除：取机器、按策略删、出文案。
     *
     * <p>有规则时 {@link DeletePolicy#PROMPT} 只提示确认指令，不删除；
     * {@link DeletePolicy#CONFIRMED} 才真正删除。</p>
     *
     * @param player         玩家
     * @param factory        工厂
     * @param target         机器
     * @param policy         删除策略
     * @param confirmCommand 给玩家的确认指令
     * @return 命令结果
     */
    private int reportDelete(ServerPlayer player, Factory factory, Machine target,
            DeletePolicy policy, String confirmCommand) {
        MachineLibraryService.Batch batch = this.machineLibrary.begin(factory);
        DeleteOutcome outcome = removeMachine(factory, target, policy, null, batch);
        if (!outcome.deleted()) {
            FactoryMessages.warn(player, "机器 [" + target.ref().shortId() + "] 还有 " + outcome.rules()
                    + " 条 IO 配置，确认删除请执行 " + confirmCommand + "，或在机器库界面删除");
            return 0;
        }
        batch.close();
        FactoryMessages.ok(player, deletedText(target, outcome));
        return 1;
    }

    /**
     * 按机器 id 删除；界面确认后走这个入口。
     *
     * <p>界面与命令确认后才会走到这里；确认逻辑在 {@link #reportDelete} 的
     * {@link DeletePolicy#PROMPT} 策略里，本入口固定按已确认处理。</p>
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 动作结果
     */
    public MachineActions.Result deleteMachineById(Factory factory, UUID machineId) {
        Machine target = factory.config().machines().byId(machineId).orElse(null);
        if (target == null) {
            return MachineActions.Result.err("找不到机器");
        }
        MachineLibraryService.Batch batch = this.machineLibrary.begin(factory);
        DeleteOutcome outcome = removeMachine(factory, target, DeletePolicy.CONFIRMED, null, batch);
        batch.close();
        return MachineActions.Result.ok(deletedText(target, outcome));
    }

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；玩家没有工厂时为 {@code null}
     */
    @Nullable
    private Factory owned(ServerPlayer player) {
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }

    /**
     * 立刻刷新一台机器的观测（接入、取回之后调，别让 {@code info} 看到 UNKNOWN）。
     *
     * @param target 机器
     * @return 刷新后的观测；观测服务未就绪时为 {@code null}
     */
    @Nullable
    private Observation refreshObservation(MachineRef target) {
        return this.observations == null ? null : this.observations.refresh(target.blockKey());
    }

    /**
     * 把一台机器的样板退回玩家。
     *
     * <p>样板是玩家的东西，删机器不能吞掉：优先塞接收者（没有接收者就找工厂拥有者）的背包，
     * 塞不下或不在线就掉在机器坐标；机器所在区块没加载时退回接收者脚下，再不行落到主世界
     * 出生点，保证不丢。</p>
     *
     * @param factory  工厂
     * @param machine  要被删掉的机器
     * @param receiver 优先接收者；{@code null} 表示用工厂拥有者
     * @return 退回去的样板数
     */
    int returnPatterns(Factory factory, Machine machine, @Nullable ServerPlayer receiver) {
        List<ItemStack> patterns = machine.provider().takePatterns();
        if (patterns.isEmpty()) {
            return 0;
        }
        ServerPlayer target = receiver != null ? receiver : onlineOwner(factory);
        for (ItemStack stack : patterns) {
            if (target != null) {
                target.getInventory().add(stack);
            }
            if (!stack.isEmpty()) {
                dropPattern(machine, stack, target);
            }
        }
        return patterns.size();
    }

    /**
     * 掉一块样板：机器坐标优先，其次接收者脚下，最后主世界出生点。
     *
     * @param machine 机器
     * @param stack   样板（可能是背包塞不下的剩余）
     * @param target  在线玩家，可为 {@code null}
     */
    private void dropPattern(Machine machine, ItemStack stack, @Nullable ServerPlayer target) {
        ServerLevel level = this.server.getLevel(machine.ref().dimension());
        if (level != null && level.isLoaded(machine.ref().pos())) {
            Block.popResource(level, machine.ref().pos(), stack);
            return;
        }
        if (target != null) {
            Block.popResource(target.serverLevel(), target.blockPosition(), stack);
            return;
        }
        ServerLevel overworld = this.server.overworld();
        Block.popResource(overworld, overworld.getSharedSpawnPos(), stack);
    }

    /** 工厂拥有者；不在线时为 {@code null}。 */
    @Nullable
    private ServerPlayer onlineOwner(Factory factory) {
        return this.server.getPlayerList().getPlayer(factory.config().owner());
    }
}
