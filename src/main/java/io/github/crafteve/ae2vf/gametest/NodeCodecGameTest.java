package io.github.crafteve.ae2vf.gametest;

import java.util.List;
import java.util.UUID;

import io.github.crafteve.ae2vf.VoidFactory;
import io.github.crafteve.ae2vf.machine.IoRuleSpec;
import io.github.crafteve.ae2vf.machine.Machine;
import io.github.crafteve.ae2vf.machine.MatchMode;
import io.github.crafteve.ae2vf.node.Node;
import io.github.crafteve.ae2vf.node.NodeCodec;
import io.github.crafteve.ae2vf.node.NodeCraft;
import io.github.crafteve.ae2vf.node.NodeIo;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 节点存档往返与旧档兼容。
 *
 * <p>存档是升级时最容易悄悄坏掉的一块：这里断言身份、类型、机器、规则与样板都能原样读回，
 * 并覆盖三个负例——缺 id、缺机器、旧档 {@code Provider} 键。</p>
 */
public final class NodeCodecGameTest {
    /** 测试节点使用的类型方块。 */
    private static final ResourceLocation TYPE_BLOCK =
            ResourceLocation.withDefaultNamespace("furnace");

    /**
     * 完整往返：身份、类型、机器、规则与样板都应一致。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void roundTrip(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        Node node = sampleNode();

        Node back = NodeCodec.load(NodeCodec.save(node, registries), registries);

        helper.assertTrue(back != null, "节点应能读回");
        helper.assertTrue(back.id().equals(node.id()), "id 应一致");
        helper.assertTrue("编解码节点".equals(back.name()), "名字应一致");
        helper.assertTrue(back.typeBlockId().equals(node.typeBlockId()), "类型方块应一致");
        helper.assertTrue(back.machines().size() == 1, "机器数应一致");
        helper.assertTrue(back.machines().get(0).block().equals(node.machines().get(0).block()),
                "方块坐标应一致");
        helper.assertTrue(back.io().inputs().size() == 1, "输入规则数应一致");
        helper.assertTrue(back.io().outputs().size() == 1, "输出规则数应一致");
        helper.assertTrue(back.io().inputs().get(0).amount() == 64L, "输入数量应一致");
        helper.assertTrue(back.io().inputs().get(0).mode() == MatchMode.EXACT,
                "输入模式应一致");
        helper.assertTrue(back.io().outputs().get(0).mode() == MatchMode.EXACT,
                "流体输出应规范为精确");
        helper.assertTrue(back.provider().patternCount() == 1, "样板数应一致");
        helper.succeed();
    }

    /**
     * 缺 id 的标签直接丢弃。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void missingIdRejected(GameTestHelper helper) {
        helper.assertTrue(NodeCodec.load(new CompoundTag(), helper.getLevel().registryAccess()) == null,
                "缺 id 应返回 null");
        helper.succeed();
    }

    /**
     * 没有机器时整份节点丢弃。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void emptyMachinesRejected(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = NodeCodec.save(sampleNode(), registries);
        tag.remove("Machines");

        helper.assertTrue(NodeCodec.load(tag, registries) == null, "没有机器应返回 null");
        helper.succeed();
    }

    /**
     * 旧档写的是 {@code Provider}，读侧仍要认。
     *
     * @param helper 测试辅助
     */
    @GameTest(template = "empty", templateNamespace = "ae2vf", timeoutTicks = 20)
    public static void legacyProviderKeyAccepted(GameTestHelper helper) {
        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = NodeCodec.save(sampleNode(), registries);
        tag.put("Provider", tag.getCompound("Craft"));
        tag.remove("Craft");

        Node back = NodeCodec.load(tag, registries);
        helper.assertTrue(back != null, "旧档 Provider 键应兼容");
        helper.assertTrue(back.provider().patternCount() == 1, "旧档样板应读回");
        helper.succeed();
    }

    /**
     * 造一个带一条输入、一条输出与一个样板的节点。
     *
     * @return 新节点
     */
    private static Node sampleNode() {
        NodeIo io = new NodeIo();
        io.addInput(new IoRuleSpec(
                AEItemKey.of(new ItemStack(Items.IRON_INGOT)), Direction.NORTH, 64L));
        io.addOutput(new IoRuleSpec(
                AEFluidKey.of(new FluidStack(Fluids.WATER, 1)), null, 1000L,
                MatchMode.IGNORE_COMPONENTS));

        NodeCraft craft = new NodeCraft();
        craft.setPattern(0, new ItemStack(Items.IRON_INGOT));

        Machine machine = Machine.of(Level.OVERWORLD, new BlockPos(3, 70, -2));
        return new Node(UUID.randomUUID(), "编解码节点", TYPE_BLOCK, io, craft,
                List.of(machine), 0L);
    }
}
