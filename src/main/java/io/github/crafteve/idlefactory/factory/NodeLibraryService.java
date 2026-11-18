package io.github.crafteve.idlefactory.factory;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import io.github.crafteve.idlefactory.node.Node;
import io.github.crafteve.idlefactory.node.NodeLibrary;

/**
 * 节点库的写入口：一次操作一次落盘。
 *
 * <p>运行时所有节点增删都走这里：单次操作用 {@link #add} / {@link #replace} / {@link #remove}，
 * 批量操作用 {@link #begin} 开一个 {@link Batch}，整批改完调一次 {@code close()}，不会退化成
 * N 次存盘。读档走 {@code Factory#restoreNode}，不走这里（读档不该触发落盘）。</p>
 *
 * <p>节点增删改会改变绑定杖快照，所以发布回调带上工厂：落盘、索引重建、节点对账之后由
 * {@code FactoryRuntime.markDirtyLinks} 推一份新快照。</p>
 *
 * <p>config 的软上限在这里判：节点数、单节点机器 / 方块数、全工厂机器 / 方块数、每侧规则数。
 * {@link NodeLibrary} 自己只守结构不变量与硬上限。</p>
 *
 * <p>本类不持有运行时单例：落盘回调从构造注入。</p>
 */
final class NodeLibraryService {
    private final Consumer<Factory> publish;

    /**
     * @param publish 档案变更的唯一出口（落盘 + 索引重建 + 节点对账请求 + 链接快照推送）
     */
    NodeLibraryService(Consumer<Factory> publish) {
        this.publish = publish;
    }

    /**
     * 加一个节点；成功后立即落盘。
     *
     * @param factory 工厂
     * @param node    节点
     * @return 加入结果
     */
    NodeLibrary.Result add(Factory factory, Node node) {
        Batch batch = begin(factory);
        NodeLibrary.Result result = batch.add(node);
        batch.close();
        return result;
    }

    /**
     * 替换一个节点；成功后立即落盘。
     *
     * @param factory 工厂
     * @param node    新节点
     * @return 替换结果
     */
    NodeLibrary.Result replace(Factory factory, Node node) {
        Batch batch = begin(factory);
        NodeLibrary.Result result = batch.replace(node);
        batch.close();
        return result;
    }

    /**
     * 删一个节点；成功后立即落盘。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @return 是否真的移除了
     */
    boolean remove(Factory factory, UUID nodeId) {
        Batch batch = begin(factory);
        boolean removed = batch.remove(nodeId);
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
     * config 软上限校验。
     *
     * <p>只看「新增量」是否超过有效上限；config 改小不删已有数据，只阻止继续新增。</p>
     *
     * @param factory 工厂
     * @param node    待写入节点
     * @return 超限时为 {@link NodeLibrary.Result#FULL}，否则 {@code null}
     */
    private static NodeLibrary.Result checkLimits(Factory factory, Node node) {
        var nodes = factory.config().nodes();
        boolean replacing = nodes.byId(node.id()).isPresent();
        if (!replacing && nodes.size() + 1 > FactoryLimitsConfig.maxNodes()) {
            return NodeLibrary.Result.FULL;
        }
        if (node.machineCount() > FactoryLimitsConfig.maxMachinesPerNode()) {
            return NodeLibrary.Result.FULL;
        }
        Node old = nodes.byId(node.id()).orElse(null);
        int machineDelta = node.machineCount() - (old == null ? 0 : old.machineCount());
        if (nodes.totalMachineCount() + machineDelta > FactoryLimitsConfig.maxTotalMachines()) {
            return NodeLibrary.Result.FULL;
        }
        return null;
    }

    /**
     * 一次批量修改：只记录「改过没有」，整批结束时最多落盘一次。
     *
     * <p>不实现 {@code AutoCloseable}：调用方在异常时不会意外发布半批修改，
     * 语义是「整批成功后才 markDirty」。</p>
     */
    final class Batch {
        private final Factory factory;
        private boolean changed;

        private Batch(Factory factory) {
            this.factory = factory;
        }

        /**
         * 加一个节点。
         *
         * @param node 节点
         * @return 加入结果
         */
        NodeLibrary.Result add(Node node) {
            NodeLibrary.Result limit = checkLimits(this.factory, node);
            if (limit != null) {
                return limit;
            }
            NodeLibrary.Result result = this.factory.config().library().add(node);
            if (result == NodeLibrary.Result.ADDED) {
                this.changed = true;
            }
            return result;
        }

        /**
         * 替换一个节点。
         *
         * @param node 新节点
         * @return 替换结果
         */
        NodeLibrary.Result replace(Node node) {
            NodeLibrary.Result limit = checkLimits(this.factory, node);
            if (limit != null) {
                return limit;
            }
            NodeLibrary.Result result = this.factory.config().library().replace(node);
            if (result == NodeLibrary.Result.REPLACED) {
                this.changed = true;
            }
            return result;
        }

        /**
         * 删一个节点。
         *
         * @param nodeId 节点 id
         * @return 是否真的移除了
         */
        boolean remove(UUID nodeId) {
            NodeLibrary.Result result = this.factory.config().library().remove(nodeId);
            if (result == NodeLibrary.Result.REMOVED) {
                this.changed = true;
                return true;
            }
            return false;
        }

        /** 整批结束：改过才落盘一次。 */
        void close() {
            if (this.changed) {
                publish.accept(this.factory);
            }
        }
    }

    /**
     * 按节点 id 取节点；批量之外的只读查询用它省一次 import。
     *
     * @param factory 工厂
     * @param nodeId  节点 id
     * @return 节点
     */
    static Optional<Node> find(Factory factory, UUID nodeId) {
        return factory.config().nodes().byId(nodeId);
    }
}
