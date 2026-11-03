package com.caszgamermd.golemsplus.breakroom;

import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.world.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import java.util.UUID;

public final class GolemBreakGameTest {
    @GameTest(maxTicks = 1000, skyAccess = true, batch = "golem_break_room_arcade")
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

    @GameTest(maxTicks = 1200, skyAccess = true, batch = "golem_break_room_snacks")
    public void idleBuilderGetsSnackAndEatsSeatedAtChairAndTable(GameTestHelper h) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++)
                h.setBlock(x, 1, z, Blocks.STONE);
        h.setBlock(1, 2, 1, WorksiteContent.BUILDER_TABLE);
        h.setBlock(2, 2, 5, BreakRoomContent.SNACK_MACHINE.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
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
        h.onEachTick(() -> {
            if (GolemBreakService.hasSnack(worker)) gotSnack[0] = true;
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
            if (GolemBreakService.rested(worker)) {
                if (!gotSnack[0] || !ateAtSeat[0] || !finishedSnack[0]) {
                    h.fail("Snack machine -> chair/table eating sequence did not complete"
                            + " gotSnack=" + gotSnack[0]
                            + " ateAtSeat=" + ateAtSeat[0]
                            + " finishedSnack=" + finishedSnack[0]
                            + " seated=" + GolemBreakService.breakRoomSeated(worker)
                            + " hasSnack=" + GolemBreakService.hasSnack(worker)
                            + " eating=" + GolemBreakService.eating(worker)
                            + " worker=" + worker.blockPosition()
                            + " service={" + GolemBreakService.debugStatus(worker) + "}");
                    return;
                }
                h.succeed();
            }
        });
    }
}
