package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Ignored crops are not building assignments and never delay completion. */
public final class BuilderIgnoredPlantProgressGameTest {
    private record Fixture(WorkerStationBlockEntity table,
            java.util.UUID owner,java.util.UUID worker,BlockPos at){}

    private static Fixture setup(GameTestHelper h,int count){
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        h.setBlock(1,2,0,Blocks.CHEST);
        BlockPos at=h.absolutePos(new BlockPos(0,2,0));
        if(!(h.getLevel().getBlockEntity(at)
                instanceof WorkerStationBlockEntity table)){
            h.fail("No Builder Planner");
            return null;
        }
        var owner=h.makeMockServerPlayerInLevel();
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
        worker.setPos(at.getX()+2.5,at.getY(),at.getZ()+2.5);
        h.getLevel().addFreshEntity(worker);
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-crop-accounting.litematic","Crop accounting",
                        at,0,0,count,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                        h.absolutePos(new BlockPos(1,2,0)))
                ||!table.registerBuilderWorker(worker.getUUID())
                ||!table.setBuilderIgnorePlants(owner.getUUID(),true)
                ||!table.startPlacedBuilder(owner.getUUID())){
            h.fail("Could not start crop accounting test");
            return null;
        }
        return new Fixture(table,owner.getUUID(),worker.getUUID(),at);
    }
    private static BuilderConstructionPlan.Step block(BlockPos at,int x,boolean ignored){
        return new BuilderConstructionPlan.Step(at.getX()+x,at.getY(),
                at.getZ()+3,ignored?"minecraft:wheat":"minecraft:stone",
                ignored?null:"minecraft:stone",ignored);
    }
    @GameTest(maxTicks=100,skyAccess=true)
    public void ignoredCropsDoNotCountOrBlockCompletion(GameTestHelper h){
        var f=setup(h,4);
        if(f==null)return;
        var steps=List.of(block(f.at,3,false),block(f.at,4,true),
                block(f.at,5,false),block(f.at,6,true));
        f.table.reconcileBuilderWork(steps);
        if(f.table.builderWorkTotal()!=2||f.table.builderWorkDone()!=0
                ||f.table.builderCursor()!=0
                ||!f.table.builderZoneDone(List.of(1,3))){
            h.fail("Ignored plants entered initial progress/work queue");return;
        }
        if(f.table.claimBuilderZoneStep(f.worker,List.of(0,1,2,3),steps)!=0
                ||!f.table.completeBuilderStep(f.worker,0)){
            h.fail("Could not complete first REAL block");return;
        }
        f.table.reconcileBuilderWork(steps);
        if(f.table.builderWorkDone()!=1||f.table.builderWorkTotal()!=2
                ||f.table.builderCursor()!=2
                ||f.table.phase()==WorkerPhase.COMPLETE){
            h.fail("Crop incorrectly inflated required count or ended early");return;
        }
        if(f.table.claimBuilderZoneStep(f.worker,List.of(0,1,2,3),steps)!=2
                ||!f.table.completeBuilderStep(f.worker,2)
                ||f.table.phase()!=WorkerPhase.COMPLETE
                ||f.table.builderWorkDone()!=2
                ||f.table.builderCursor()!=4){
            h.fail("Crop-only tail prevented 2/2 real-work completion");return;
        }
        h.succeed();
    }
    @GameTest(maxTicks=100,skyAccess=true)
    public void allIgnoredCropsFinishWithZeroRequiredPlacements(GameTestHelper h){
        var f=setup(h,3);
        if(f==null)return;
        var steps=List.of(block(f.at,3,true),block(f.at,4,true),
                block(f.at,5,true));
        f.table.reconcileBuilderWork(steps);
        if(f.table.builderWorkTotal()!=0||f.table.builderWorkDone()!=0
                ||f.table.builderCursor()!=3||f.table.activeOrder()!=null
                ||f.table.phase()!=WorkerPhase.COMPLETE){
            h.fail("Three ignored crops should end 0/0 without building");return;
        }
        h.succeed();
    }
    @GameTest(maxTicks=100,skyAccess=true)
    public void disablingIgnoreReopensOnlyUnbuiltCrops(GameTestHelper h){
        var f=setup(h,4);
        if(f==null)return;
        var steps=List.of(block(f.at,3,false),block(f.at,4,true),
                block(f.at,5,false),block(f.at,6,true));
        f.table.reconcileBuilderWork(steps);
        if(f.table.claimBuilderZoneStep(f.worker,List.of(0,1,2,3),steps)!=0
                ||!f.table.completeBuilderStep(f.worker,0)){
            h.fail("Could not preserve previously finished stone");return;
        }
        if(!f.table.setBuilderIgnorePlants(f.owner,false)){
            h.fail("Could not disable ignore flag mid-build");return;
        }
        var expanded=List.of(block(f.at,3,false),block(f.at,4,false),
                block(f.at,5,false),block(f.at,6,false));
        f.table.reconcileBuilderWork(expanded);
        if(f.table.builderCursor()!=1
                ||f.table.builderWorkTotal()!=4
                ||f.table.builderWorkDone()!=1
                ||f.table.builderZoneDone(List.of(1,2,3))){
            h.fail("Toggling off did not reopen crops/preserve real stone");return;
        }
        h.succeed();
    }
}
