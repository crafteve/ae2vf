package io.github.crafteve.ae2vf.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.key.BlockKey;
import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.node.NodeCraft;
import io.github.crafteve.ae2vf.node.NodeIo;
import io.github.crafteve.ae2vf.node.NodeLibrary;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 节点库的三张索引、重复 / 冲突校验与上限。
 *
 * <p>索引是绑定、IO 门禁与视图表的共同底座：这里只断言「写入 / 删除后索引与节点表一致」，
 * 不碰世界。</p>
 */
public final class NodeLibraryGameTest {
    /** 测试用的类型方块，只作标识，不需要真实是方块实体。 */
    private static final ResourceLocation TYPE_BLOCK =
            ResourceLocation.withDefaultNamespace("furnace");

    /**
     * 新增、重复、冲突、删除后三张索引都应一致。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void addDuplicateConflictRemove(GameTestHelper helper) {
        NodeLibrary library = new NodeLibrary();
        Node first = nodeAt("一", 1);
        Node second = nodeAt("二", 2);

        helper.assertTrue(library.add(first) == NodeLibrary.Result.ADDED, "首个节点应加入");
        helper.assertTrue(library.add(first) == NodeLibrary.Result.DUPLICATE, "同 id 应拒绝");
        helper.assertTrue(library.add(second) == NodeLibrary.Result.ADDED, "第二个节点应加入");

        Node conflict = Node.of("冲突", TYPE_BLOCK, first.machines().get(0).block());
        helper.assertTrue(library.add(conflict) == NodeLibrary.Result.CONFLICT,
                "同一方块不能属于两个节点");

        helper.assertTrue(library.byId(first.id()).isPresent(), "byId 应命中");
        helper.assertTrue(library.nodeOfMachine(first.machines().get(0).id()).orElseThrow() == first,
                "machineToNode 应命中");
        helper.assertTrue(library.machineAt(first.machines().get(0).block()).isPresent(),
                "blockToOwner 应命中");
        helper.assertTrue(library.size() == 2, "应有两个节点");
        helper.assertTrue(library.totalMachineCount() == 2, "应有两台机器");

        helper.assertTrue(library.remove(first.id()) == NodeLibrary.Result.REMOVED, "删除应成功");
        helper.assertTrue(library.byId(first.id()).isEmpty(), "byId 应清空");
        helper.assertTrue(library.machineAt(first.machines().get(0).block()).isEmpty(),
                "方块索引应清空");
        helper.assertTrue(library.remove(first.id()) == NodeLibrary.Result.NOT_FOUND,
                "重复删除应报 NOT_FOUND");
        helper.succeed();
    }

    /**
     * 单节点机器数超过硬上限时整份拒绝，索引保持空。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 40)
    public static void tooManyMachinesRejected(GameTestHelper helper) {
        List<Machine> machines = new ArrayList<>();
        for (int i = 0; i <= NodeLibrary.HARD_MAX_MACHINES_PER_NODE; i++) {
            machines.add(Machine.of(Level.OVERWORLD, new BlockPos(i, 64, 8)));
        }
        Node huge = new Node(UUID.randomUUID(), "超大", TYPE_BLOCK, new NodeIo(),
                new NodeCraft(), machines, 0L);
        NodeLibrary library = new NodeLibrary();

        helper.assertTrue(library.add(huge) == NodeLibrary.Result.FULL,
                "超过单节点机器上限应返回 FULL");
        helper.assertTrue(library.isEmpty(), "拒绝后索引应保持空");
        helper.succeed();
    }

    /**
     * 替换节点后名字更新、方块索引保留。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void replaceKeepsIndexes(GameTestHelper helper) {
        NodeLibrary library = new NodeLibrary();
        Node first = nodeAt("一", 10);
        library.add(first);

        Node renamed = first.withName("改名");
        helper.assertTrue(library.replace(renamed) == NodeLibrary.Result.REPLACED, "替换应成功");
        helper.assertTrue("改名".equals(library.byId(first.id()).orElseThrow().name()),
                "名字应更新");
        helper.assertTrue(library.machineAt(first.machines().get(0).block()).isPresent(),
                "方块索引应保留");
        helper.succeed();
    }

    /**
     * 造一个单方块节点。
     *
     * @param name 名字
     * @param x    方块 x 坐标
     * @return 新节点
     */
    private static Node nodeAt(String name, int x) {
        BlockKey key = BlockKey.of(Level.OVERWORLD, new BlockPos(x, 64, 0));
        return Node.of(name, TYPE_BLOCK, key);
    }
}
