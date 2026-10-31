package io.github.crafteve.idlefactory.integration.ae2;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.crafteve.idlefactory.IdleFactory;
import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.integration.appflux.AppFluxKeys;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

/**
 * 把 AE2 的 {@link MEStorage} 包成工厂自己的 {@link Storage}。
 *
 * <p>这是工厂与 AE2 之间唯一的存储适配点。工厂主体只看到 {@code insert / extract / snapshot}，
 * 不 import 任何 {@code appeng} 类型。</p>
 *
 * <p>物品、流体是 AE2 原生；FE 需要 Applied Flux；化学品暂不支持。</p>
 */
public final class Ae2Storage implements Storage {
    /** 无主操作源：搬运和调试指令都算系统行为，不带玩家上下文。 */
    private static final IActionSource SOURCE = IActionSource.empty();

    private final MEStorage storage;

    /**
     * @param storage ME 网络库存
     */
    public Ae2Storage(MEStorage storage) {
        this.storage = storage;
    }

    @Override
    public long insert(AEKey key, long amount, boolean simulate) {
        AEKey aeKey = Ae2Keys.toAe(key);
        if (aeKey == null || amount <= 0L) {
            return 0L;
        }
        long inserted = this.storage.insert(aeKey, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, SOURCE);
        return inserted;
    }

    @Override
    public long extract(AEKey key, long amount, boolean simulate) {
        AEKey aeKey = Ae2Keys.toAe(key);
        if (aeKey == null || amount <= 0L) {
            return 0L;
        }
        long extracted = this.storage.extract(aeKey, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, SOURCE);
        return extracted;
    }

    @Override
    public boolean supports(CapabilityKind kind) {
        return switch (kind) {
            case ITEM, FLUID -> true;
            // 装了 Applied Flux 还不够，得等 FE key 真正接上才算支持
            case ENERGY -> AppFluxKeys.feKey() != null;
        };
    }

    @Override
    public boolean isEmpty() {
        KeyCounter counter = new KeyCounter();
        this.storage.getAvailableStacks(counter);
        return counter.isEmpty();
    }

    @Override
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
}
