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

/** An unsupported four-block horizontal rope entry must be travelled
 * physically while attached, then the golem must climb onto a real
 * TOP slab. The gap has no walkable floor and the golem must not fly,
 * teleport, add blocks, or detach from the hook over unsupported air.
 */
public final class GrappleSwingGapGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=800,skyAccess=true)
    public void builderSwingsOverUnsupportedGapBeforeClimbingTopSlab(
            GameTestHelper h){
        ServerLevel level=h.getLevel();
        for(int x=2;x<=13;x++)for(int z=2;z<=6;z++){
            h.setBlock(x,1,z,Blocks.AIR);
            for(int y=2;y<=10;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=2;x<=5;x++)for(int z=2;z<=6;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(2,2,2,WorksiteContent.BUILDER_TABLE);
        BlockPos station=h.absolutePos(new BlockPos(2,2,2));
        BlockPos start=h.absolutePos(new BlockPos(5,2,4));
        BlockPos anchor=h.absolutePos(new BlockPos(10,6,4));
        BlockPos top=anchor.above();
        BlockPos hanging=h.absolutePos(new BlockPos(9,2,4));
        level.setBlock(anchor,Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE,SlabType.TOP),2);
        if(!(level.getBlockEntity(station) instanceof
                WorkerStationBlockEntity table)){
            h.fail("Missing builder table");return;
        }
        if(!level.getBlockState(hanging.below()).isAir()){
            h.fail("Swing gap fixture unexpectedly has floor");return;
        }
        var chunks=new LinkedHashSet<ChunkPos>();
        for(BlockPos p:new BlockPos[]{start,anchor,station}){
            ChunkPos chunk=new ChunkPos(p.getX()>>4,p.getZ()>>4);
            if(chunks.add(chunk)){
                level.setChunkForced(chunk.x(),chunk.z(),true);
                BuilderChunkTickets.holdPhysicalGameTestChunk(level,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            }
        }
        Runnable cleanup=()->{
            for(ChunkPos chunk:chunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(level,
                        new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
                level.setChunkForced(chunk.x(),chunk.z(),false);
            }
        };
        BuilderGolem golem=new BuilderGolem(
                WorksiteContent.BUILDER_GOLEM,level);
        golem.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        golem.setItem(8,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        level.addFreshEntity(golem);
        WorkerNavigation.setReverseDemolitionTraining(golem,true);
        final boolean[] commanded={false},crossed={false},rose={false};
        final StringBuilder csv=new StringBuilder(
                "tick,x,y,z,grounded,rope,nav,grapple\n");
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%40==0)
                for(ChunkPos chunk:chunks)
                    BuilderChunkTickets.holdPhysicalGameTestChunk(level,
                            new BlockPos(chunk.x()<<4,start.getY(),chunk.z()<<4));
            boolean rope=BuilderGrappleAccess.active(golem);
            if(rope&&golem.getX()>hanging.getX()-.45
                    &&golem.getY()<start.getY()+.55)crossed[0]=true;
            if(rope&&golem.getY()>start.getY()+1.0)rose[0]=true;
            if(tick%5==0)csv.append(tick).append(',')
                    .append(String.format(Locale.ROOT,"%.3f",golem.getX()))
                    .append(',')
                    .append(String.format(Locale.ROOT,"%.3f",golem.getY()))
                    .append(',')
                    .append(String.format(Locale.ROOT,"%.3f",golem.getZ()))
                    .append(',').append(golem.onGround())
                    .append(',').append(rope)
                    .append(',').append(WorkerNavigation.debugStatus(golem)
                            .replace(',',';'))
                    .append(',').append(BuilderGrappleAccess.debug(golem)
                            .replace(',',';')).append('\n');
            String failure=null;
            if(!golem.isAlive())failure="Golem died";
            else if(tick>650)failure="Hanging entry / top-slab climb stalled";
            if(failure!=null){
                report(csv,"FAIL "+failure,tick,crossed[0],rose[0],golem);
                cleanup.run();
                h.fail(failure+" at "+golem.position()
                        +" "+BuilderGrappleAccess.debug(golem));return;
            }
            if(tick%5!=0||golem.tickCount<10)return;
            if(!commanded[0]){
                if(!BuilderGrappleAccess.canPlanAscent(level,golem,top)){
                    report(csv,"FAIL no anchor for hanging rope gap",
                            tick,crossed[0],rose[0],golem);
                    cleanup.run();
                    h.fail("Four-block hanging rope swing was rejected");return;
                }
                var result=BuilderGrappleAccess.reach(
                        level,golem,table,top);
                if(result!=BuilderGrappleAccess.Result.MOVING
                        ||!BuilderGrappleAccess.active(golem)
                        ||!BuilderGrappleAccess.debug(golem)
                                .contains("SWING_TO_LINE")){
                    report(csv,"FAIL swing not selected",
                            tick,crossed[0],rose[0],golem);
                    cleanup.run();
                    h.fail("No physical rope swing started: "+result+" "
                            +BuilderGrappleAccess.debug(golem));return;
                }
                commanded[0]=true;
                System.out.println("[SWING GAP] real hanging-line entry started");
            }else if(golem.onGround()
                    &&golem.blockPosition().equals(top)
                    &&!BuilderGrappleAccess.active(golem)){
                if(!crossed[0]||!rose[0]
                        ||golem.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1
                        ||!BuilderGrappleAccess.solidTop(level,anchor)){
                    report(csv,"FAIL arrived without verified rope swing",
                            tick,crossed[0],rose[0],golem);
                    cleanup.run();
                    h.fail("Invalid swing accounting / lost hook");return;
                }
                report(csv,"PASS real unsupported swing and top-slab ascent",
                        tick,crossed[0],rose[0],golem);
                System.out.println("[SWING GAP] PASS: horizontal entry over "
                        +"air, then actual top slab ascent at tick "+tick);
                cleanup.run();
                h.succeed();
            }
        });
    }
    private static void report(StringBuilder csv,String outcome,long tick,
            boolean crossed,boolean rose,BuilderGolem mob){
        try{
            Path root=Path.of("logs");
            Files.createDirectories(root);
            Files.writeString(root.resolve("grapple_swing_gap.csv"),
                    csv.toString(),StandardCharsets.UTF_8);
            Files.writeString(root.resolve("grapple_swing_gap_summary.txt"),
                    outcome+" tick="+tick+" crossed="+crossed
                    +" rose="+rose+" pos="+mob.position()+"\n",
                    StandardCharsets.UTF_8);
        }catch(Exception error){
            System.err.println("[SWING GAP] report failed "+error);
        }
    }
}
