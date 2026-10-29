package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * A physical Builder hooks onto the ACTUAL Y+.5 upper surface of a
 * bottom-half slab, becomes grounded on the half-height platform, then
 * uses the same bottom slab as an anchor to rappel back onto full ground.
 * No teleporting, creative flight, fake vertical coordinates or blocks
 * placed by the golem. The top and bottom slab cases are separate tests.
 */
public final class GrappleBottomSlabGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=2200,skyAccess=true)
    public void climbAndRappelOnBottomHalfSlab(GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=10;x<=21;x++)for(int z=10;z<=16;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int y=2;y<=5;y++)h.setBlock(17,y,13,Blocks.STONE);
        BlockPos slab=h.absolutePos(new BlockPos(17,6,13));
        world.setBlock(slab,Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE,SlabType.BOTTOM),2);
        h.setBlock(11,2,13,WorksiteContent.BUILDER_TABLE);
        BlockPos station=h.absolutePos(new BlockPos(11,2,13));
        WorkerStationBlockEntity table=(WorkerStationBlockEntity)
                world.getBlockEntity(station);
        if(table==null){h.fail("No actual Builder table");return;}
        BlockPos start=h.absolutePos(new BlockPos(14,2,13));
        LinkedHashSet<ChunkPos> chunks=new LinkedHashSet<>();
        for(BlockPos p:new BlockPos[]{start,station,slab}){
            ChunkPos chunk=new ChunkPos(p.getX()>>4,p.getZ()>>4);
            if(chunks.add(chunk)){
                world.setChunkForced(chunk.x(),chunk.z(),true);
                BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            }
        }
        Runnable cleanup=()->{
            for(ChunkPos chunk:chunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
                world.setChunkForced(chunk.x(),chunk.z(),false);
            }
        };
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(8,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        WorkerNavigation.setReverseDemolitionTraining(mob,true);

        final int[] stage={0}; // 0 ascent, 1 rappel
        final boolean[] attachedUp={false},attachedDown={false};
        final boolean[] startedUp={false},startedDown={false};
        final double[] previousY={mob.getY()},up={0},down={0};
        final StringBuilder csv=new StringBuilder(
                "tick,stage,x,y,z,onGround,rope,deltaY,grapple\n");
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%20==0)for(ChunkPos chunk:chunks){
                world.setChunkForced(chunk.x(),chunk.z(),true);
                BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            }
            boolean rope=BuilderGrappleAccess.active(mob);
            double delta=mob.getY()-previousY[0];
            if(rope){
                if(stage[0]==0){
                    attachedUp[0]=true;
                    if(delta>.025)up[0]+=delta;
                }else{
                    attachedDown[0]=true;
                    if(delta<-.025)down[0]-=delta;
                }
            }
            if(tick%5==0)
                csv.append(tick).append(',').append(stage[0]).append(',')
                        .append(String.format(Locale.ROOT,"%.3f",mob.getX()))
                        .append(',')
                        .append(String.format(Locale.ROOT,"%.3f",mob.getY()))
                        .append(',')
                        .append(String.format(Locale.ROOT,"%.3f",mob.getZ()))
                        .append(',').append(mob.onGround())
                        .append(',').append(rope).append(',')
                        .append(String.format(Locale.ROOT,"%.3f",delta))
                        .append(',').append(BuilderGrappleAccess.debug(mob)
                                .replace(',',';')).append('\n');
            previousY[0]=mob.getY();
            String problem=null;
            if(!mob.isAlive())problem="Builder died or unloaded";
            else if(tick>350&&mob.tickCount<10)
                problem="Builder never physically ticked";
            else if(tick>1800)
                problem="Timed out in phase "+stage[0]
                        +" at "+mob.position()+" rope="
                        +BuilderGrappleAccess.debug(mob);
            if(problem!=null){
                report(csv,"FAIL "+problem,tick,up[0],down[0]);
                cleanup.run();h.fail(problem);return;
            }
            if(mob.tickCount<10||tick%5!=0)return;
            if(stage[0]==0){
                if(!startedUp[0]){
                    if(BuilderGrappleAccess.solidTop(world,slab)
                            ||!BuilderGrappleAccess.bottomSlab(world,slab)
                            ||!WorkerNavigation.canStandOn(world,mob,slab)
                            ||!BuilderGrappleAccess.canPlanAscent(
                                    world,mob,slab)){
                        report(csv,"FAIL invalid bottom slab planning",
                                tick,up[0],down[0]);
                        cleanup.run();h.fail("Bottom slab anchor preflight");return;
                    }
                    var action=BuilderGrappleAccess.reach(
                            world,mob,table,slab);
                    if(action!=BuilderGrappleAccess.Result.MOVING){
                        report(csv,"FAIL ascent denied: "+action,
                                tick,up[0],down[0]);
                        cleanup.run();h.fail("Bottom slab ascent denied");return;
                    }
                    startedUp[0]=true;
                    System.out.println("[BOTTOM SLAB GRAPPLE] attached to "
                            +"real half-height platform "+slab);
                }else if(mob.onGround()
                        &&mob.blockPosition().equals(slab)
                        &&Math.abs(mob.getY()-slab.getY()-.5)<.06
                        &&!BuilderGrappleAccess.active(mob)){
                    if(!attachedUp[0]||up[0]<3.0){
                        report(csv,"FAIL ascent not rope-supported",
                                tick,up[0],down[0]);
                        cleanup.run();h.fail("False bottom slab ascent");return;
                    }
                    stage[0]=1;
                    WorkerNavigation.stop(mob);
                    System.out.println("[BOTTOM SLAB GRAPPLE] physically "
                            +"grounded at half height "+mob.getY());
                }
            }else if(!startedDown[0]){
                if(!BuilderGrappleAccess.rappelToward(world,mob,table,start)
                        ||!BuilderGrappleAccess.active(mob)){
                    report(csv,"FAIL bottom slab rappel denied",
                            tick,up[0],down[0]);
                    cleanup.run();h.fail("Bottom slab rappel not started");return;
                }
                startedDown[0]=true;
            }else if(mob.onGround()
                    &&mob.blockPosition().getY()==start.getY()
                    &&Math.abs(mob.getY()-start.getY())<.06
                    &&!BuilderGrappleAccess.active(mob)){
                if(!attachedDown[0]||down[0]<3.0){
                    report(csv,"FAIL no real rappel",tick,up[0],down[0]);
                    cleanup.run();h.fail("False bottom slab descent");return;
                }
                report(csv,"PASS bottom-half slab climb and rappel",
                        tick,up[0],down[0]);
                System.out.println("[BOTTOM SLAB GRAPPLE] PASS ascent="
                        +up[0]+" descent="+down[0]+" tick="+tick
                        +" topSurface="+(slab.getY()+.5));
                cleanup.run();h.succeed();
            }
        });
    }

    private static void report(StringBuilder csv,String outcome,long tick,
            double ascent,double descent){
        try{
            Path folder=Path.of("logs");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("grapple_bottom_slab.csv"),
                    csv.toString(),StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("grapple_bottom_slab_summary.txt"),
                    outcome+" tick="+tick+" ascent="+ascent
                    +" descent="+descent+"\n",StandardCharsets.UTF_8);
        }catch(Exception ex){
            System.err.println("[BOTTOM SLAB GRAPPLE] trace save "+ex);
        }
    }
}
