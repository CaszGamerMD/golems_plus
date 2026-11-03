package com.caszgamermd.golemsplus.breakroom;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import org.jspecify.annotations.Nullable;

/** Break Room furniture; the snack machine owns a persistent food inventory. */
public final class BreakRoomBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private final String kind;
    public BreakRoomBlock(Properties properties, String kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(properties -> new BreakRoomBlock(properties, kind));
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return kind.equals("golem_snack_machine") ? new SnackMachineBlockEntity(pos, state) : null;
    }
    @Override protected InteractionResult useItemOn(ItemStack held, BlockState state,
            Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!kind.equals("golem_snack_machine")) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof SnackMachineBlockEntity machine))
            return InteractionResult.PASS;
        if (!SnackMachineBlockEntity.isSnack(held)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        int inserted = machine.insert(held);
        player.sendSystemMessage(Component.literal(inserted == 0 ? "Snack machine is full."
                : "Restocked snack machine: " + inserted + " item(s). Stock: "
                + machine.totalItems() + "."));
        return InteractionResult.SUCCESS;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level,
            BlockPos pos, Player player, BlockHitResult hit) {
        if (!kind.equals("golem_snack_machine")) return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof SnackMachineBlockEntity machine))
            return InteractionResult.PASS;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown()) {
            ItemStack removed = machine.takeSnack();
            if (removed.isEmpty()) player.sendSystemMessage(Component.literal("Snack machine is empty."));
            else {
                if (!player.getInventory().add(removed))
                    Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), removed);
                player.sendSystemMessage(Component.literal("Removed one snack from the machine."));
            }
        } else {
            player.sendSystemMessage(Component.literal("Snack machine stock: " + machine.totalItems()
                    + " item(s) across " + SnackMachineBlockEntity.SLOTS
                    + " slots. Hold food and use it on the machine to restock."));
        }
        return InteractionResult.SUCCESS;
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter world,
            BlockPos pos, CollisionContext context) {
        if (kind.equals("break_room_table"))
            return Shapes.or(Block.box(0,12,0,16,16,16), Block.box(6,0,6,10,12,10));
        if (kind.equals("break_room_chair"))
            return Shapes.or(Block.box(2,6,2,14,9,14), Block.box(3,0,3,5,6,5),
                    Block.box(11,0,3,13,6,5), Block.box(3,0,11,5,6,13),
                    Block.box(11,0,11,13,6,13));
        return Block.box(1,0,1,15,16,15);
    }
    @Override public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof SnackMachineBlockEntity machine)
            Containers.dropContents(level, pos, machine);
        return super.playerWillDestroy(level, pos, state, player);
    }
}
