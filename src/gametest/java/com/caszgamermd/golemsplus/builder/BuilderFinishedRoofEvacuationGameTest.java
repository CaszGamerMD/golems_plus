package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A finished Builder atop a real tracked scaffold must climb down,
 * dismount and walk to OnSite before it is eligible for another claim.
 * This is actual entity physics, never teleporting the golem to storage.
 */
public final class BuilderFinishedRoofEvacuationGameTest {
    @GameTest(maxTicks=850,skyAccess=true)
    public void finishedBuilderLeavesScaffoldAndReachesOnSite(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<9;x++)for(int z=0;z<9;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=h.absolutePos(new BlockPos(0,2,0));
        if(!(world.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            h.fail("No Builder OnSite Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            h.fail("Cannot claim Planner");return;
        }
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos base=h.absolutePos(new BlockPos(4,2,4));
        mob.setPos(base.getX()+.5,base.getY()+6,base.getZ()+.5);
        world.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            h.fail("Could not register Builder");return;
        }
        for(int y=base.getY();y<base.getY()+6;y++){
            BlockPos scaffold=base.atY(y);
            world.setBlock(scaffold,Blocks.SCAFFOLDING.defaultBlockState(),3);
            if(!table.trackScaffold(mob.getUUID(),scaffold)){
                h.fail("Could not track paid scaffold "+scaffold);return;
            }
        }
        double[] last={mob.getX(),mob.getY(),mob.getZ()};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.getY()<base.getY()-.3){
                h.fail("Retiring Builder fell or vanished "+mob.position());return;
            }
            double change=Math.sqrt(Math.pow(mob.getX()-last[0],2)
                    +Math.pow(mob.getY()-last[1],2)
                    +Math.pow(mob.getZ()-last[2],2));
            if(change>1.1){
                h.fail("Return path teleported the worker d="+change);return;
            }
            last[0]=mob.getX();last[1]=mob.getY();last[2]=mob.getZ();
            if(h.getTick()%5!=0)return;
            if(BuilderBuildService.parkAtOnSite(world,mob,table)){
                if(mob.getY()>home.getY()+1.5
                        ||BuilderScaffoldPlanA.hasPlan(mob)
                        ||BuilderGrappleAccess.active(mob)
                        ||world.getBlockState(mob.blockPosition().below())
                            .is(Blocks.SCAFFOLDING)){
                    h.fail("OnSite arrival accepted while still on roof/scaffold");
                    return;
                }
                System.out.println("[FINISHED ROOF EVACUATION] real Builder"
                        +" left 6-level tracked tower and arrived at OnSite"
                        +" tick="+h.getTick()+" feet="+mob.position());
                h.succeed();return;
            }
            if(h.getTick()>780)
                h.fail("Finished Builder camped on build: "+mob.position()
                        +" scaffold="+BuilderScaffolding.debugClimb(mob)
                        +" planA="+BuilderScaffoldPlanA.status(mob)
                        +" grapple="+BuilderGrappleAccess.debug(mob));
        });
    }
}
