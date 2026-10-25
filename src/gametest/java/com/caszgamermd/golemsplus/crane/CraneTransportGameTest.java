package com.caszgamermd.golemsplus.crane;

import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.warehouse.WarehouseAssignment;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.caszgamermd.golemsplus.world.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Actual entity ticks and passenger transitions; no manual tick or teleport. */
public final class CraneTransportGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=3000,skyAccess=true)
    public void fourBuildersRideUpAndBackDown(GameTestHelper h){
        var world=h.getLevel();
        for(int x=1;x<46;x++)for(int z=1;z<46;z++)h.setBlock(x,1,z,Blocks.STONE);
        for(int x=18;x<=22;x++)for(int z=18;z<=22;z++)h.setBlock(x,12,z,Blocks.STONE);
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        BlockPos home=h.absolutePos(new BlockPos(4,2,4));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(home);
        var owner=h.makeMockServerPlayerInLevel();
        table.claimIfUnowned(owner.getUUID());table.setCrewTarget(owner.getUUID(),5);
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<5;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            BlockPos start=h.absolutePos(i==4?new BlockPos(10,2,21):new BlockPos(13+i%2,2,19+i/2));
            worker.setPos(Vec3.atBottomCenterOf(start));worker.assign(home,owner.getUUID());
            world.addFreshEntity(worker);table.registerBuilderWorker(worker.getUUID());crew.add(worker);
        }
        table.setPlacedBuilderPlan(owner.getUUID(),"placed-crane-transport.litematic","Transport fixture",home,0,0,1,0);
        table.setWarehouse(owner.getUUID(),new WarehouseAssignment(world.dimension().identifier().toString(),home.getX(),home.getY(),home.getZ()));
        if(!table.startPlacedBuilder(owner.getUUID())){h.fail("Cannot start transport job");return;}
        // Keep a real linked job, but isolate movement from construction.
        table.setPhase(WorkerPhase.PAUSED);
        var crane=new BuildingCrane(CraneContent.CRANE,world);
        BlockPos ground=h.absolutePos(new BlockPos(13,2,20));
        BlockPos upper=h.absolutePos(new BlockPos(20,13,20));
        crane.place(owner.getUUID(),h.absolutePos(new BlockPos(10,2,20)),Vec3.atBottomCenterOf(ground));
        world.addFreshEntity(crane);CraneService.track(world,crane);
        if(!crane.link(table)||!crane.assignOperator(crew.removeLast())){h.fail("Cannot assign crane operator");return;}
        if(table.builderWorkerIds().size()!=4){h.fail("Operator still occupies a construction zone");return;}
        owner.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CraneContent.BUILDER_BOX));crane.interact(owner,InteractionHand.MAIN_HAND);
        for(var worker:crew)if(!crane.request(worker,upper)){h.fail("Ride rejected");return;}
        Set<UUID> boardedUp=new HashSet<>(),arrivedUp=new HashSet<>(),boardedDown=new HashSet<>(),arrivedDown=new HashSet<>();
        boolean[] descending={false};
        h.onEachTick(()->{
            if(h.getTick()%200==0)System.out.println("[CRANE ROUNDTRIP] "+h.getTick()+" "+crane.status()+" at="+crane.position());
            for(var worker:crew){
                if(worker.getVehicle()==crane){
                    (descending[0]?boardedDown:boardedUp).add(worker.getUUID());
                }else if(!descending[0]&&boardedUp.contains(worker.getUUID())&&worker.getY()>=upper.getY()-.1){
                    arrivedUp.add(worker.getUUID());
                }else if(descending[0]&&boardedDown.contains(worker.getUUID())&&worker.getY()<ground.getY()+1){
                    arrivedDown.add(worker.getUUID());
                }
            }
            if(!descending[0]&&arrivedUp.size()==4){
                descending[0]=true;
                for(var worker:crew)if(!crane.request(worker,ground)){h.fail("Downward request rejected");return;}
            }
            if(arrivedDown.size()==4){
                System.out.println("[CRANE ROUNDTRIP] PASS four real builders boarded, delivered up, boarded and returned down");h.succeed();
            }
            if(h.getTick()==2900)h.fail("Round trip stalled: "+crane.status()+" up="+arrivedUp.size()+" down="+arrivedDown.size());
        });
    }
}
