package com.caszgamermd.golemsplus.breakroom;

import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.world.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import java.util.UUID;

public final class GolemBreakGameTest {
    @GameTest(environment = "golems_plus_gametest:break_room", maxTicks = 1000, skyAccess = true)
    public void idleBuilderPhysicallyVisitsArcadeAndRests(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(1, 2, 1, WorksiteContent.BUILDER_TABLE);
        h.setBlock(5, 2, 5, BreakRoomContent.ARCADE.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));

        BlockPos home = h.absolutePos(new BlockPos(1, 2, 1));
        WorkerStationBlockEntity station =
                (WorkerStationBlockEntity) h.getLevel().getBlockEntity(home);
        UUID owner = UUID.randomUUID();
        station.claimIfUnowned(owner);
        BuilderGolem worker = new BuilderGolem(WorksiteContent.BUILDER_GOLEM, h.getLevel());
        BlockPos spawn = h.absolutePos(new BlockPos(2, 2, 2));
        worker.setPos(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5);
        worker.assign(home, owner);
        station.registerBuilderWorker(worker.getUUID());
        h.getLevel().addFreshEntity(worker);

        BlockPos arcadeFront = h.absolutePos(new BlockPos(5, 2, 4));
        boolean[] playedAtArcade = {false};
        h.onEachTick(() -> {
            if (GolemBreakService.playingArcade(worker)) {
                if (!worker.blockPosition().equals(arcadeFront)) {
                    h.fail("Builder entered arcade activity without reaching the arcade front");
                    return;
                }
                playedAtArcade[0] = true;
            }
            if (GolemBreakService.rested(worker)) {
                if (!playedAtArcade[0]) {
                    h.fail("Builder rested without playing at the arcade");
                    return;
                }
                h.succeed();
            }
        });
    }

    // Movement to the dispenser and then to a real seat may require route
    // retries; completion still requires physically eating the stored apple.
    @GameTest(environment = "golems_plus_gametest:break_room", maxTicks = 3000, skyAccess = true)
    public void idleBuilderGetsSnackAndEatsSeatedAtChairAndTable(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(1, 2, 1, WorksiteContent.BUILDER_TABLE);
        BlockPos machinePos = h.absolutePos(new BlockPos(2, 2, 5));
        h.setBlock(new BlockPos(2, 2, 5), BreakRoomContent.SNACK_MACHINE.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
        if (!(h.getLevel().getBlockEntity(machinePos) instanceof SnackMachineBlockEntity machine)) {
            h.fail("Snack machine did not create its inventory"); return;
        }
        ItemStack stock = new ItemStack(net.minecraft.world.item.Items.APPLE, 5);
        if (machine.insert(stock) != 5 || !stock.isEmpty()) {
            h.fail("Snack machine failed to accept real apple stock"); return;
        }
        h.setBlock(5, 2, 5, BreakRoomContent.CHAIR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
        h.setBlock(5, 2, 4, BreakRoomContent.TABLE);

        BlockPos home = h.absolutePos(new BlockPos(1, 2, 1));
        BlockPos chair = h.absolutePos(new BlockPos(5, 2, 5));
        WorkerStationBlockEntity station =
                (WorkerStationBlockEntity) h.getLevel().getBlockEntity(home);
        UUID owner = UUID.randomUUID();
        station.claimIfUnowned(owner);
        BuilderGolem worker = new BuilderGolem(WorksiteContent.BUILDER_GOLEM, h.getLevel());
        BlockPos spawn = h.absolutePos(new BlockPos(2, 2, 2));
        worker.setPos(spawn.getX() + .5, spawn.getY(), spawn.getZ() + .5);
        worker.assign(home, owner);
        station.registerBuilderWorker(worker.getUUID());
        h.getLevel().addFreshEntity(worker);

        boolean[] gotSnack = {false};
        boolean[] ateAtSeat = {false};
        boolean[] finishedSnack = {false};
        int[] elapsedTicks = {0};
        h.onEachTick(() -> {
            elapsedTicks[0]++;
            if (GolemBreakService.hasSnack(worker)) {
                ItemStack carried = GolemBreakService.snackForRender(worker);
                if (!carried.is(net.minecraft.world.item.Items.APPLE) || carried.getCount() != 1) {
                    h.fail("Golem did not carry exactly one real apple: " + carried); return;
                }
                gotSnack[0] = true;
            }
            if (GolemBreakService.eating(worker)) {
                ateAtSeat[0] = true;
                if (!GolemBreakService.breakRoomSeated(worker)
                        || !worker.blockPosition().equals(chair)) {
                    h.fail("Snack eating began without sitting on the chair");
                    return;
                }
            }
            if (ateAtSeat[0] && GolemBreakService.breakRoomSeated(worker)
                    && !GolemBreakService.hasSnack(worker))
                finishedSnack[0] = true;
            // Another nearby leisure fixture (such as an arcade) may be
            // selected first. Keep observing until the machine -> chair ->
            // eating sequence succeeds rather than treating that valid visit
            // as a failed snack attempt.
            if (GolemBreakService.rested(worker)
                    && gotSnack[0] && ateAtSeat[0] && finishedSnack[0]) {
                h.succeed();
                return;
            }
            if (elapsedTicks[0] >= 2990) {
                h.fail("Snack machine -> chair/table eating sequence did not complete"
                        + " gotSnack=" + gotSnack[0]
                        + " ateAtSeat=" + ateAtSeat[0]
                        + " finishedSnack=" + finishedSnack[0]
                        + " seated=" + GolemBreakService.breakRoomSeated(worker)
                        + " hasSnack=" + GolemBreakService.hasSnack(worker)
                        + " eating=" + GolemBreakService.eating(worker)
                        + " worker=" + worker.blockPosition()
                        + " service={" + GolemBreakService.debugStatus(worker) + "}");
            }
        });
    }
    @GameTest(environment = "golems_plus_gametest:break_room", maxTicks = 1100, skyAccess = true)
    public void emptySnackMachineIsSkippedAndGolemUsesChair(GameTestHelper h) {
        for (int x=0;x<8;x++) for(int z=0;z<8;z++) h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(1,2,1,WorksiteContent.BUILDER_TABLE);
        h.setBlock(2,2,5,BreakRoomContent.SNACK_MACHINE.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING,Direction.EAST));
        h.setBlock(5,2,5,BreakRoomContent.CHAIR.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING,Direction.NORTH));
        h.setBlock(5,2,4,BreakRoomContent.TABLE);
        BlockPos home=h.absolutePos(new BlockPos(1,2,1));
        WorkerStationBlockEntity station=(WorkerStationBlockEntity)h.getLevel().getBlockEntity(home);
        UUID owner=UUID.randomUUID(); station.claimIfUnowned(owner);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
        BlockPos spawn=h.absolutePos(new BlockPos(2,2,2));
        worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        worker.assign(home,owner); station.registerBuilderWorker(worker.getUUID());
        h.getLevel().addFreshEntity(worker);
        h.onEachTick(()->{
            String status=GolemBreakService.debugStatus(worker);
            if(status.contains("visit=SNACK_MACHINE")){
                h.fail("Golem attempted to visit an empty snack machine: "+status); return;
            }
            if(GolemBreakService.rested(worker)){
                if(GolemBreakService.hasSnack(worker))h.fail("Golem fabricated a snack while stock was empty");
                else h.succeed();
            }
        });
    }

    @GameTest(environment = "golems_plus_gametest:break_room", maxTicks = 100, skyAccess = true)
    public void snackMachineStoresMultipleFoodsAndDispensesOneRealItem(GameTestHelper h) {
        h.setBlock(2,2,2,BreakRoomContent.SNACK_MACHINE.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING,Direction.NORTH));
        BlockPos at=h.absolutePos(new BlockPos(2,2,2));
        if(!(h.getLevel().getBlockEntity(at) instanceof SnackMachineBlockEntity machine)){
            h.fail("Snack machine inventory was not registered"); return;
        }
        ItemStack apples=new ItemStack(net.minecraft.world.item.Items.APPLE,12);
        ItemStack bread=new ItemStack(net.minecraft.world.item.Items.BREAD,7);
        ItemStack stick=new ItemStack(net.minecraft.world.item.Items.STICK,1);
        if(machine.insert(apples)!=12||machine.insert(bread)!=7){
            h.fail("Machine did not accept food stacks"); return;
        }
        if(machine.insert(stick)!=0||!machine.hasSnack()||machine.totalItems()!=19){
            h.fail("Machine accepted non-food or lost stock"); return;
        }
        ItemStack dispensed=machine.takeSnack();
        if(!dispensed.is(net.minecraft.world.item.Items.APPLE)||dispensed.getCount()!=1
                ||machine.totalItems()!=18){
            h.fail("Machine did not dispense exactly one stored apple: "+dispensed); return;
        }
        h.succeed();
    }

}
