package io.github.crafteve.idlefactory.factory;

import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

/**
 * 机器引用解析。
 *
 * <p>命令和 Tab 补全共用这一套：<b>只回答「找没找到」，不发消息</b>。补全时按 Tab 不该被灌错误
 * 消息，所以解析必须是静默的；要不要提示由调用方决定。</p>
 *
 * <p>P1 删除产线后这里只剩机器：机器只有短 id 一种引用方式。</p>
 */
public final class FactoryRefs {
    private FactoryRefs() {
        // 纯静态工具类
    }

    /**
     * 按短 id 找机器身份。
     *
     * @param factory 工厂，可为 {@code null}
     * @param ref     短 id，可为 {@code null}
     * @return 机器身份；找不到或前缀有歧义时为空
     */
    public static Optional<MachineRef> machine(@Nullable Factory factory, @Nullable String ref) {
        if (factory == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        return factory.config().machines().byShortId(ref).map(Machine::ref);
    }
}
