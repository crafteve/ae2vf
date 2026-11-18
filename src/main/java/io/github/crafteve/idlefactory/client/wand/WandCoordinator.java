package io.github.crafteve.idlefactory.client.wand;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.CommandMessages;
import io.github.crafteve.idlefactory.command.CommandKind;
import io.github.crafteve.idlefactory.command.CommandResult;
import io.github.crafteve.idlefactory.command.CommandSource;
import io.github.crafteve.idlefactory.command.FactoryCommand;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.network.payload.FactoryCommandC2S;
import io.github.crafteve.idlefactory.factory.wand.WandCommitOutcome;
import io.github.crafteve.idlefactory.command.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.factory.wand.WandLimits;
import io.github.crafteve.idlefactory.network.payload.wand.WandChainC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandChainS2C;
import io.github.crafteve.idlefactory.network.payload.wand.WandCommitC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandCommitS2C;
import io.github.crafteve.idlefactory.network.payload.wand.WandOpenC2S;
import io.github.crafteve.idlefactory.network.payload.wand.WandOpenS2C;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 绑定杖的网络协调器：发请求、收执、超时与预校验缓存。
 *
 * <p>它是唯一直接发包的客户端类：草稿状态机与手势只描述意图，序列号、提交锁、回执匹配
 * 都在这里。回执按「容器 id + 草稿版本号」匹配，迟到的包直接丢弃；提交 5 秒没有回执就回到
 * 编辑态允许重试。</p>
 */
public final class WandCoordinator {
    /** 单调递增的草稿版本号；登录归零。 */
    private static long nextSeq;
    /** 正在等待回执的提交序号。 */
    private static long pendingSeq;
    /** 正在等待回执的容器 id；{@code -1} 表示没有在等。 */
    private static int pendingContainerId = -1;
    /** 提交超时的绝对毫秒时刻。 */
    private static long submitDeadlineMillis;
    /** 最近一次预校验的问题；面板构造时读，关闭时清。 */
    private static List<WandIssue> pendingIssues = List.of();
    /** 本次打开是否已经收到服务端预校验回执；收到后服务端结果就是唯一权威。 */
    private static boolean openResultReceived;
    /** 面板内的一行提示（超时 / 提交失败）；没有时为 {@code null}。 */
    @Nullable
    private static Component panelStatus;

    private WandCoordinator() {
        // 纯静态协调器
    }

    /**
     * 取下一个草稿版本号。
     *
     * @return 严格递增的版本号
     */
    public static long nextSeq() {
        return ++nextSeq;
    }

    /** 登录 / 登出时清空序号、等待状态与缓存。 */
    public static void reset() {
        nextSeq = 0;
        pendingSeq = 0;
        pendingContainerId = -1;
        submitDeadlineMillis = 0L;
        pendingIssues = List.of();
        openResultReceived = false;
        panelStatus = null;
    }

    /**
     * 空闲态右键方块：发 1:1 toggle。
     *
     * @param block 目标方块
     */
    public static void toggle(BlockKey block) {
        PacketDistributor.sendToServer(new FactoryCommandC2S(new FactoryCommand(-1,
                CommandKind.WAND_TOGGLE, CommandSource.PLAYER, null, block, 0L,
                new FactoryCommand.Empty())));
    }

    /**
     * 编辑态 Ctrl + 右键：请求连锁。
     *
     * @param block 连锁起点
     */
    public static void chain(BlockKey block) {
        PacketDistributor.sendToServer(new WandChainC2S(block));
    }

    /** 编辑态蹲下右键：请求服务端预校验并打开面板。 */
    public static void openDraft() {
        if (WandSession.state() != WandSession.State.EDITING) {
            return;
        }
        if (WandSession.draft().isEmpty()) {
            WandHud.failure(WandMessages.failure(WandFailure.EMPTY_DRAFT));
            return;
        }
        pendingIssues = List.of();
        openResultReceived = false;
        panelStatus = null;
        PacketDistributor.sendToServer(new WandOpenC2S(WandSession.draft()));
    }

    /**
     * 面板点确定：进入提交态并上传草稿。
     *
     * @param containerId 面板菜单的容器 id
     */
    public static void submit(int containerId) {
        if (WandSession.state() != WandSession.State.EDITING) {
            WandHud.neutral(WandMessages.waiting());
            return;
        }
        WandDraft draft = WandSession.renewRequestSeq();
        if (draft.isEmpty()) {
            WandHud.failure(WandMessages.failure(WandFailure.EMPTY_DRAFT));
            return;
        }
        pendingSeq = draft.requestSeq();
        pendingContainerId = containerId;
        panelStatus = null;
        submitDeadlineMillis = System.currentTimeMillis() + WandLimits.SUBMIT_TIMEOUT_MILLIS;
        WandSession.beginSubmit();
        PacketDistributor.sendToServer(new WandCommitC2S(containerId, draft));
    }

    /**
     * 1:1 回执：成功出绿字，失败出红字。
     *
     * @param result 回执
     */
    public static void handleToggleResult(CommandResult result) {
        if (!result.ok()) {
            WandHud.failure(Component.literal(CommandMessages.text(result)));
            return;
        }
        boolean connected = result.block() != null && WandLinks.owner(result.block()).isPresent();
        WandHud.success(connected ? WandMessages.toggleConnected()
                : WandMessages.toggleDisconnected());
    }

    /**
     * 连锁回执：失败出红字，成功批量加进草稿。
     *
     * @param payload 回执
     */
    public static void handleChain(WandChainS2C payload) {
        if (payload.failure() != WandFailure.NONE) {
            WandHud.failure(WandMessages.failure(payload.failure()));
            return;
        }
        WandSession.applyChain(payload.machines());
    }

    /**
     * 预校验回执：失败只出 HUD，成功缓存问题等面板构造时读。
     *
     * @param payload 回执
     */
    public static void handleOpen(WandOpenS2C payload) {
        if (payload.failure() != WandFailure.NONE) {
            WandHud.failure(WandMessages.failure(payload.failure()));
            return;
        }
        pendingIssues = payload.issues();
        openResultReceived = true;
    }

    /**
     * 提交回执：按容器 id 与版本号匹配，成功后清草稿并关面板。
     *
     * @param payload 回执
     */
    public static void handleCommit(WandCommitS2C payload) {
        if (payload.containerId() != pendingContainerId || payload.requestSeq() != pendingSeq) {
            return;
        }
        pendingContainerId = -1;
        if (!payload.outcome().ok()) {
            WandSession.submitFailed();
            panelStatus = WandMessages.failure(payload.outcome().failure());
            WandHud.failure(WandMessages.failure(payload.outcome().failure()));
            return;
        }
        WandHud.success(WandMessages.saved(payload.outcome().addedMachines(),
                payload.outcome().removedMachines()));
        WandSession.submitSucceeded();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.closeContainer();
            minecraft.setScreen(null);
        }
    }

    /**
     * 每 tick 检查提交超时。
     *
     * @param minecraft 客户端实例
     */
    public static void tick(Minecraft minecraft) {
        if (WandSession.state() != WandSession.State.SUBMITTING) {
            return;
        }
        if (System.currentTimeMillis() <= submitDeadlineMillis) {
            return;
        }
        pendingContainerId = -1;
        pendingSeq = 0;
        WandSession.submitFailed();
        panelStatus = WandMessages.timeout();
        WandHud.failure(WandMessages.timeout());
    }

    /** 面板构造时读的预校验问题。 */
    public static List<WandIssue> pendingIssues() {
        return pendingIssues;
    }

    /** 本次打开是否已经收到服务端预校验回执；收到后不再退回客户端预检。 */
    public static boolean openResultReceived() {
        return openResultReceived;
    }

    /**
     * 放弃正在等待的提交：草稿被清（换手 / 换维度 / 登出）后迟到回执必须丢弃。
     *
     * <p>服务端仍可能已经写库成功；那份回执到这里按过期丢弃，客户端下次请求快照就同步了。</p>
     */
    public static void abandonSubmission() {
        pendingContainerId = -1;
        pendingSeq = 0;
        submitDeadlineMillis = 0L;
    }

    /** 面板关闭时清掉预校验缓存，避免下一次打开读到旧问题。 */
    public static void clearPendingIssues() {
        pendingIssues = List.of();
        openResultReceived = false;
        panelStatus = null;
    }

    /** 面板内的一行提示；没有时为 {@code null}。 */
    @Nullable
    public static Component panelStatus() {
        return panelStatus;
    }
}
