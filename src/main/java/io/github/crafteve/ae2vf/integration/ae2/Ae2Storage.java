package io.github.crafteve.ae2vf.integration.ae2;

import java.util.LinkedHashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import io.github.crafteve.ae2vf.integration.appflux.AppFluxKeys;

/**
 * 把 AE2 的 {@link MEStorage} 包成工厂自己的 {@link MEStorage}。
 *
 * <p>这是工厂与 AE2 之间唯一的存储适配点。工厂主体只看到 {@code insert / extract / snapshot}，
 * 不 import 任何 {@code appeng} 类型。</p>
 *
 * <p>物品、流体是 AE2 原生；FE 需要 Applied Flux；化学品暂不支持。</p>
 */
public final class Ae2Storage implements MEStorage {
    /** 无主操作源：搬运和调试指令都算系统行为，不带玩家上下文。 */
    private static final IActionSource SOURCE = IActionSource.empty();

    private final MEStorage storage;

    /**
     * @param storage ME 网络库存
     */
    public Ae2Storage(MEStorage storage) {
        this.storage = storage;
    }

    public long insert(AEKey key, long amount, boolean simulate) {
        AEKey aeKey = Ae2Keys.toAe(key);
        if (aeKey == null || amount <= 0L) {
            return 0L;
        }
        long inserted = this.storage.insert(aeKey, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, SOURCE);
        return inserted;
    }

    public long extract(AEKey key, long amount, boolean simulate) {
        AEKey aeKey = Ae2Keys.toAe(key);
        if (aeKey == null || amount <= 0L) {
            return 0L;
        }
        long extracted = this.storage.extract(aeKey, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, SOURCE);
        return extracted;
    }

    public boolean isEmpty() {
        KeyCounter counter = new KeyCounter();
        this.storage.getAvailableStacks(counter);
        return counter.isEmpty();
    }

    public Map<AEKey, Long> snapshot() {
        KeyCounter counter = new KeyCounter();
        this.storage.getAvailableStacks(counter);
        Map<AEKey, Long> result = new LinkedHashMap<>();
        for (var entry : counter) {
            AEKey key = Ae2Keys.toResource(entry.getKey());
            if (key != null) {
                result.merge(key, entry.getLongValue(), Long::sum);
            }
        }
        return result;
    }

    /**
     * AE2 侧的全量枚举入口：终端列表、配方查看器与 CPU 都读这里。
     *
     * <p>不覆盖时 {@link MEStorage} 的默认实现为空，终端会看到「能存能取但列表全空」。</p>
     *
     * @param out 收集器；由调用方负责清空
     */
    @Override
    public void getAvailableStacks(KeyCounter out) {
        this.storage.getAvailableStacks(out);
    }

    /** 仓库在 AE2 快照里的描述文本。 */
    @Override
    public net.minecraft.network.chat.Component getDescription() {
        return net.minecraft.network.chat.Component.literal("Idle Factory ME 仓库");
    }

    /** AE2 侧的写入入口：转调布尔版本，转换只在这里发生。 */
    @Override
    public long insert(appeng.api.stacks.AEKey what, long amount,
            appeng.api.config.Actionable mode, appeng.api.networking.security.IActionSource source) {
        return insert(what, amount, mode == appeng.api.config.Actionable.SIMULATE);
    }

    /** AE2 侧的取出入口：转调布尔版本。 */
    @Override
    public long extract(appeng.api.stacks.AEKey what, long amount,
            appeng.api.config.Actionable mode, appeng.api.networking.security.IActionSource source) {
        return extract(what, amount, mode == appeng.api.config.Actionable.SIMULATE);
    }

}
