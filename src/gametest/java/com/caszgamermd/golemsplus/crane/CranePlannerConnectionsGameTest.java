package com.caszgamermd.golemsplus.crane;

import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Links a crane through the actual Planner flow BEFORE pressing Start. */
public final class CranePlannerConnectionsGameTest {
    @GameTest(maxTicks=300,skyAccess=true)
    public void plannerConnectsCraneAndReservesOperatorBeforeStart(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=8;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(1,2,1,WorksiteContent.BUILDER_TABLE);
        var pos=h.absolutePos(new BlockPos(1,2,1));
        if(!(world.getBlockEntity(pos) instanceof WorkerStationBlockEntity table)){
            h.fail("Planner not placed");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            h.fail("Planner owner mismatch");return;
        }
        if(!table.setPlacedBuilderPlan(owner.getUUID(),
                "placed-connections-fixture.litematic","Crane Connections",
                h.absolutePos(new BlockPos(2,2,2)),0,0,50,0)){
            h.fail("Cannot save selected build placement");return;
        }
        // Default Planner crew capacity is one. Explicitly provision two
        // real workers: one driver plus at least one construction Builder.
        if(!table.setCrewTarget(owner.getUUID(),2)){
            h.fail("Could not set two-Builder crew capacity");return;
        }
        BuilderGolem driver=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        for(BuilderGolem builder:new BuilderGolem[]{driver,worker}){
            builder.setPos(pos.getX()+2.5,pos.getY(),pos.getZ()+2.5);
            builder.assign(pos,owner.getUUID());
            world.addFreshEntity(builder);
            if(!table.registerBuilderWorker(builder.getUUID())){
                h.fail("Could not register two crafted Builders");return;
            }
        }
        BlockPos rail=h.absolutePos(new BlockPos(3,2,5));
        world.setBlock(rail,CraneContent.RAIL.defaultBlockState(),3);
        BuildingCrane crane=new BuildingCrane(CraneContent.CRANE,world);
        Vec3 anchor=CraneMovement.anchorPoint(world,rail);
        crane.place(owner.getUUID(),rail,anchor.add(2,0,0));
        world.addFreshEntity(crane);
        CraneService.track(world,crane);
        if(table.activeOrder()!=null){
            h.fail("Fixture must link before job start");return;
        }
        CraneService.selectJob(owner,table);
        CraneService.selectCrane(owner,crane);
        if(!crane.getUUID().equals(table.builderCraneId())
                ||crane.operator()==null
                ||table.builderWorkerIds().size()!=1
                ||!table.builderWorkerIds().contains(
                    driver.getUUID().equals(crane.operator())
                            ?worker.getUUID():driver.getUUID())){
            h.fail("Planner did not persist crane and exclusively reserve one Builder operator");
            return;
        }
        h.succeed();
    }
}