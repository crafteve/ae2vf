package io.github.crafteve.idlefactory.factory.wand;

/**
 * 绑定杖的线协议与交互上限。
 *
 * <p>这些值同时被客户端与服务端使用，集中在这里避免两边写不同的数：回执里的 rejected 只传
 * 前 {@link #MAX_ISSUES_WIRE} 条（总数另用 {@code rejectedCount} 传），提交超时按毫秒计，
 * HUD 闪条按 tick 计。</p>
 */
public final class WandLimits {
    /** 回执与预校验里逐条问题的线上上限；超出部分只累计数量。 */
    public static final int MAX_ISSUES_WIRE = 32;
    /** 提交后等待回执的超时；超时回到编辑态并允许重试。 */
    public static final long SUBMIT_TIMEOUT_MILLIS = 5000L;
    /** HUD 闪条停留时间。 */
    public static final int FLASH_TICKS = 60;

    private WandLimits() {
        // 纯常量容器，禁止实例化
    }
}
