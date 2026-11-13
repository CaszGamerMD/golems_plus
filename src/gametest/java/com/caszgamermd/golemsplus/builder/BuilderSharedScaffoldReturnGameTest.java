package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** A Builder who used another Builder's paid scaffold must get home using
 * that physical tower. A real 4-block descent is required: no teleport,
 * flight, item transfer, or scaffold ownership change is permitted. */
public final class BuilderSharedScaffoldReturnGameTest {
    @GameTest(maxTicks=600,skyAccess=true)
    public void borrowerDescendsTeammatesTowerAndWalksToOnSite(
            GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(1,2,1,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=h.absolutePos(new BlockPos(1,2,1));
        BlockPos depot=h.absolutePos(new BlockPos(6,2,3));
        BlockPos base=h.absolutePos(new BlockPos(3,2,3));
        if(!(world.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            h.fail("No Builder Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),2)){
            h.fail("Cannot prepare two-worker Builder test");return;
        }
        UUID builderWhoBuiltTower=UUID.randomUUID();
        if(!table.registerBuilderWorker(builderWhoBuiltTower)){
            h.fail("Failed to register tower's owner");return;
        }
        // Real scaffold blocks with genuine contact to a stone floor.
        // The receipt is owned by the OTHER builder, not this borrower.
        for(int y=2;y<=5;y++){
            BlockPos cell=base.atY(h.absolutePos(new BlockPos(3,y,3)).getY());
            world.setBlock(cell,Blocks.SCAFFOLDING.defaultBlockState(),3);
            if(!table.trackScaffold(builderWhoBuiltTower,cell)){
                h.fail("Failed to track a physical borrowed tower");return;
            }
        }
        var borrower=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        // Exercise the legacy movement controller independently of powered flight.
        borrower.setPropellerEnabled(false);
        BlockPos top=h.absolutePos(new BlockPos(3,6,3));
        borrower.setPos(top.getX()+.5,top.getY(),top.getZ()+.5);
        world.addFreshEntity(borrower);
        if(!table.registerBuilderWorker(borrower.getUUID())){
            h.fail("Failed to register borrowing Builder");return;
        }
        final double[] oldY={borrower.getY()};
        final boolean[] descended={false};
        h.onEachTick(()->{
            if(!borrower.isAlive()){h.fail("Borrower vanished");return;}
            double dy=oldY[0]-borrower.getY();
            oldY[0]=borrower.getY();
            if(dy>.65){h.fail("Unsafe uncontrolled fall during return: "+dy);return;}
            if(borrower.getY()<top.getY()-2.5)descended[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0){
                if(!BuilderScaffolding.descendForTravel(world,borrower,table))
                    BuilderReturnService.approach(world,borrower,depot,.83);
            }
            if(descended[0]&&borrower.onGround()
                    &&borrower.distanceToSqr(depot.getX()+.5,depot.getY(),
                            depot.getZ()+.5)<=10){
                if(table.scaffoldPositions(builderWhoBuiltTower).size()!=4
                        ||!table.scaffoldPositions(borrower.getUUID()).isEmpty()){
                    h.fail("Borrowed scaffold receipts were transferred or lost");
                    return;
                }
                System.out.println("[SHARED SCAFFOLD RETURN PASS] borrowed "
                        +"4-high owned tower, descended then reached OnSite "
                        +"at tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>520){
                h.fail("Borrowed scaffold return stalled: pos="+borrower.position()
                        +" climb="+BuilderScaffolding.debugClimb(borrower)
                        +" nav="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .debugStatus(borrower));
            }
        });
    }
}
