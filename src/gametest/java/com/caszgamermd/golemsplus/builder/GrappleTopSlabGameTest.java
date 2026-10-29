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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * A real, walking Builder must physically hook onto an upper-half slab
 * roof, climb onto its flush Y+1 top, and rappel to the original ground.
 * A LOWER slab at the same Y is intentionally not a legal Y+1 anchor.
 * No teleports, levitation, creative flight, or fake successful movements.
 */
public final class GrappleTopSlabGameTest {
    private static final Path CSV=Path.of("logs/grapple_top_slab.csv");
    private static final Path SUMMARY=Path.of("logs/grapple_top_slab_summary.txt");

    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=4000,skyAccess=true)
    public void realBuilderClimbsAndRappelsOnTopHalfSlab(GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=10;x<=28;x++)for(int z=10;z<=16;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        // The upper slab occupies the top half of its block, making a
        // full-height top surface at local Y=7. The outside rope column
        // at X=16 has open air and real grass-supported ground at Y=2.
        for(int y=2;y<=5;y++){
            h.setBlock(17,y,13,Blocks.STONE);
            h.setBlock(25,y,13,Blocks.STONE);
        }
        BlockPos topAnchor=h.absolutePos(new BlockPos(17,6,13));
        BlockPos lowerAnchor=h.absolutePos(new BlockPos(25,6,13));
        world.setBlock(topAnchor,Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE,SlabType.TOP),2);
        world.setBlock(lowerAnchor,Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE,SlabType.BOTTOM),2);
        h.setBlock(11,2,13,WorksiteContent.BUILDER_TABLE);
        WorkerStationBlockEntity table=(WorkerStationBlockEntity)
                world.getBlockEntity(h.absolutePos(new BlockPos(11,2,13)));
        if(table==null){h.fail("No real Builder station");return;}

        BlockPos start=h.absolutePos(new BlockPos(14,2,13));
        BlockPos upperFeet=topAnchor.above();
        BlockPos lowerFeet=lowerAnchor.above();
        var forceChunks=new LinkedHashSet<ChunkPos>();
        for(BlockPos p:new BlockPos[]{start,topAnchor,lowerAnchor}){
            ChunkPos chunk=new ChunkPos(p);
            if(forceChunks.add(chunk)){
                world.setChunkForced(chunk.x(),chunk.z(),true);
                BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            }
        }
        Runnable release=()->{
            for(ChunkPos chunk:forceChunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
                world.setChunkForced(chunk.x(),chunk.z(),false);
            }
        };

        BuilderGolem golem=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        golem.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        golem.setItem(8,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(golem);
        WorkerNavigation.setReverseDemolitionTraining(golem,true);

        final StringBuilder csv=new StringBuilder(
                "tick,phase,x,y,z,rope,grounded,deltaY,nav,grapple\n");
        final int[] phase={0}; // 0 climb; 1 rappel; 2 completed
        final double[] previousY={golem.getY()};
        final double[] riseOnRope={0},descentOnRope={0};
        final long[] lastEntityTick={0};
        final boolean[] startedUp={false},startedDown={false};
        final boolean[] attachedUp={false},attachedDown={false};
        final String[] lastEvent={"created"};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%40==0)for(ChunkPos chunk:forceChunks)
                BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            lastEntityTick[0]=Math.max(lastEntityTick[0],golem.tickCount);
            boolean rope=BuilderGrappleAccess.active(golem);
            double delta=golem.getY()-previousY[0];
            if(rope){
                if(phase[0]==0){
                    attachedUp[0]=true;
                    if(delta>.025)riseOnRope[0]+=delta;
                }else if(phase[0]==1){
                    attachedDown[0]=true;
                    if(delta<-.025)descentOnRope[0]-=delta;
                }
            }
            if(tick%5==0){
                csv.append(tick).append(',').append(phase[0]).append(',')
                        .append(String.format(Locale.ROOT,"%.3f",golem.getX()))
                        .append(',')
                        .append(String.format(Locale.ROOT,"%.3f",golem.getY()))
                        .append(',')
                        .append(String.format(Locale.ROOT,"%.3f",golem.getZ()))
                        .append(',').append(rope).append(',')
                        .append(golem.onGround()).append(',')
                        .append(String.format(Locale.ROOT,"%.3f",delta))
                        .append(',').append(WorkerNavigation.debugStatus(golem)
                                .replace(',',';'))
                        .append(',').append(BuilderGrappleAccess.debug(golem)
                                .replace(',',';')).append('\n');
            }
            previousY[0]=golem.getY();

            String failure=null;
            if(!golem.isAlive())failure="Real Builder died";
            else if(tick>200 && lastEntityTick[0]<10)
                failure="Builder entity is not ticking";
            else if(tick>3200)
                failure="Vertical motion timed out; stage="+phase[0]
                        +" y="+golem.getY()+" rope="+
                        BuilderGrappleAccess.debug(golem);
            if(failure!=null){
                write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                        lastEvent[0],"FAIL "+failure);
                release.run();h.fail(failure);return;
            }
            if(golem.tickCount<10||tick%5!=0)return;
            if(phase[0]==0){
                if(!startedUp[0]){
                    // Prevent a false success from accepting a bottom slab
                    // that is physically half a block below the apex.
                    if(BuilderGrappleAccess.canPlanAscent(
                            world,golem,lowerFeet)){
                        String error="Bottom-half slab incorrectly qualified "
                                +"as a full-height grapple anchor";
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    if(!BuilderGrappleAccess.canPlanAscent(
                            world,golem,upperFeet)){
                        String error="Top-half slab failed ascent preflight";
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    var action=BuilderGrappleAccess.reach(
                            world,golem,table,upperFeet);
                    if(action!=BuilderGrappleAccess.Result.MOVING){
                        String error="Cannot start top-slab ascent: "+action;
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    startedUp[0]=true;
                    lastEvent[0]="ascent requested";
                    System.out.println("[TOP SLAB GRAPPLE] climbing "
                            +topAnchor+" via real grapple");
                }else if(golem.onGround()
                        &&golem.blockPosition().getY()==upperFeet.getY()
                        &&golem.blockPosition().getX()==upperFeet.getX()
                        &&golem.blockPosition().getZ()==upperFeet.getZ()){
                    if(!attachedUp[0]||riseOnRope[0]<2.0){
                        String error="Reached ledge without a proven "
                                +"physical grapple ascent";
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    phase[0]=1;
                    lastEvent[0]="summit reached; descent begins";
                    System.out.println("[TOP SLAB GRAPPLE] landed on top slab "
                            +"height="+(golem.getY()-start.getY()));
                    WorkerNavigation.stop(golem);
                }
            }else if(phase[0]==1){
                if(!startedDown[0]){
                    if(!BuilderGrappleAccess.rappelToward(
                            world,golem,table,start)){
                        String error="Top-slab rappel could not start";
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    startedDown[0]=true;
                    lastEvent[0]="rappel requested";
                }else if(golem.onGround()
                        &&golem.blockPosition().getY()==start.getY()
                        &&!BuilderGrappleAccess.active(golem)){
                    if(!attachedDown[0]||descentOnRope[0]<2.0){
                        String error="Returned without real rope descent";
                        write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                                lastEvent[0],"FAIL "+error);
                        release.run();h.fail(error);return;
                    }
                    phase[0]=2;
                    lastEvent[0]="grounded return";
                    write(csv,phase[0],tick,riseOnRope[0],descentOnRope[0],
                            lastEvent[0],"PASS real top-slab climb and rappel");
                    System.out.println("[TOP SLAB GRAPPLE] PASS ascent="
                            +riseOnRope[0]+" descent="+descentOnRope[0]
                            +" tick="+tick);
                    release.run();h.succeed();
                }
            }
        });
    }

    private static void write(StringBuilder csv,int phase,long tick,
            double up,double down,String lastEvent,String result){
        try{
            Files.createDirectories(CSV.getParent());
            Files.writeString(CSV,csv.toString(),StandardCharsets.UTF_8);
            Files.writeString(SUMMARY,
                    result+"\nphase="+phase+" tick="+tick
                    +" upwardRopeMeters="+up+" downwardRopeMeters="+down
                    +" last="+lastEvent+"\n",StandardCharsets.UTF_8);
        }catch(Exception error){
            System.err.println("[TOP SLAB GRAPPLE] failed to save trace "
                    +error);
        }
    }
}
