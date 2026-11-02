package io.github.crafteve.idlefactory.factory;

import java.util.List;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.machine.MachineLibrary;
import io.github.crafteve.idlefactory.factory.machine.MachineLibraryView;
import io.github.crafteve.idlefactory.factory.machine.MachineRef;

/**
 * 机器库的写入口：一次操作一次落盘。
 *
 * <p>运行时的所有增删都走这里：单台操作用 {@link #add} / {@link #remove}，连锁操作用
 * {@link #begin} 开一个 {@link Batch}，整批改完调一次 {@code close()}，不会退化成 N 次存盘。
 * 读档走 {@code Factory#restoreMachine}，不走这里（读档不该触发落盘）。</p>
 *
 * <p>本类不持有运行时单例：落盘回调从构造注入。</p>
 */
final class MachineLibraryService {
    private final Runnable publish;

    /**
     * @param publish 档案变更的唯一出口（落盘 + 索引重建 + 节点对账请求）
     */
    MachineLibraryService(Runnable publish) {
        this.publish = publish;
    }

    /**
     * 加一台机器；成功后立即落盘。
     *
     * @param factory 工厂
     * @param ref     机器身份
     * @return 加入结果
     */
    MachineLibrary.AddResult add(Factory factory, MachineRef ref) {
        Batch batch = begin(factory);
        MachineLibrary.AddResult result = batch.add(ref);
        batch.close();
        return result;
    }

    /**
     * 批量加机器；整批只落盘一次。
     *
     * @param factory 工厂
     * @param refs    机器身份
     * @return 实际新增的台数
     */
    int addAll(Factory factory, List<MachineRef> refs) {
        Batch batch = begin(factory);
        int added = 0;
        for (MachineRef ref : refs) {
            if (batch.add(ref) == MachineLibrary.AddResult.ADDED) {
                added++;
            }
        }
        batch.close();
        return added;
    }

    /**
     * 删一台机器；成功后立即落盘。
     *
     * @param factory   工厂
     * @param machineId 机器 id
     * @return 是否真的移除了
     */
    boolean remove(Factory factory, UUID machineId) {
        Batch batch = begin(factory);
        boolean removed = batch.remove(machineId);
        batch.close();
        return removed;
    }

    /**
     * 开一次批量修改；调用方负责在整批结束后调 {@link Batch#close()}。
     *
     * @param factory 工厂
     * @return 批量句柄
     */
    Batch begin(Factory factory) {
        return new Batch(factory);
    }

    /**
     * 一次批量修改：只记录「改过没有」，整批结束时最多落盘一次。
     *
     * <p>不实现 {@code AutoCloseable}：调用方在异常时不会意外发布半批修改，
     * 语义与重构前「整批成功后才 markDirty」一致。</p>
     */
    final class Batch {
        private final Factory factory;
        private boolean changed;

        private Batch(Factory factory) {
            this.factory = factory;
        }

        /**
         * 加一台机器。
         *
         * @param ref 机器身份
         * @return 加入结果
         */
        MachineLibrary.AddResult add(MachineRef ref) {
            MachineLibrary.AddResult result = this.factory.config().library().add(ref);
            if (result == MachineLibrary.AddResult.ADDED) {
                this.changed = true;
            }
            return result;
        }

        /**
         * 删一台机器。
         *
         * @param machineId 机器 id
         * @return 是否真的移除了
         */
        boolean remove(UUID machineId) {
            boolean removed = this.factory.config().library().remove(machineId);
            if (removed) {
                this.changed = true;
            }
            return removed;
        }

        /** 整批结束：改过才落盘一次。 */
        void close() {
            if (this.changed) {
                publish.run();
            }
        }
    }
}
