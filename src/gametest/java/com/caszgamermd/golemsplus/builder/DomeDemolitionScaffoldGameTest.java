package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Isolated physical test of mixed demolition access:
 * Go from grass at Y=2 to a REAL supported roof landing at Y=11 by
 * physically carrying, placing, climbing and dismounting scaffolding.
 * The roof block cannot be touched by ordinary 3-block reach from ground;
 * no creative flight, teleport, remote placement or scaffold conjuring.
 */
public final class DomeDemolitionScaffoldGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=3500,skyAccess=true)
    public void reachRoofByBuildingRealScaffolding(GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=9;x<=23;x++)for(int z=9;z<=23;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=14;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos roof=h.absolutePos(new BlockPos(16,10,16));
        world.setBlock(roof,Blocks.STONE.defaultBlockState(),3);
        BlockPos spawn=h.absolutePos(new BlockPos(12,2,16));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        mob.setItem(7,new ItemStack(Items.SCAFFOLDING,16));
        world.addFreshEntity(mob);
        var remaining=new LinkedHashMap<BlockPos,BlockState>();
        remaining.put(roof,Blocks.STONE.defaultBlockState());
        var controller=new DomeDemolitionScaffold(world,mob,remaining);
        var chunks=new LinkedHashSet<ChunkPos>();
        for(int x=9;x<=23;x+=4)for(int z=9;z<=23;z+=4){
            BlockPos p=h.absolutePos(new BlockPos(x,2,z));
            chunks.add(new ChunkPos(p.getX()>>4,p.getZ()>>4));
        }
        for(ChunkPos chunk:chunks){
            world.setChunkForced(chunk.x(),chunk.z(),true);
            BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                    new BlockPos(chunk.x()<<4,spawn.getY(),chunk.z()<<4));
        }
        Runnable release=()->{
            for(ChunkPos chunk:chunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,spawn.getY(),chunk.z()<<4));
                world.setChunkForced(chunk.x(),chunk.z(),false);
            }
            BuilderScaffolding.cancel(mob);
        };
        final boolean[] started={false};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%20==0)for(ChunkPos chunk:chunks){
                world.setChunkForced(chunk.x(),chunk.z(),true);
                BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                        new BlockPos(chunk.x()<<4,spawn.getY(),chunk.z()<<4));
            }
            if(tick<80||tick%5!=0)return;
            if(!mob.isAlive()){
                release.run();h.fail("Physical scaffold Builder died");return;
            }
            if(!started[0]&&mob.tickCount>15&&mob.onGround()){
                started[0]=controller.tryStart(tick);
            }
            if(started[0]&&controller.active())controller.tick(tick);
            if(tick%200==0)System.out.println(
                    "[DOME SCAFFOLD TEST] tick="+tick+" mobY="+mob.getY()
                    +" entityTicks="+mob.tickCount+" "+controller.status());
            if(started[0]&&controller.dismounts()>0){
                boolean roofReached=mob.onGround()
                        &&mob.blockPosition().equals(roof.above());
                boolean intact=world.getBlockState(roof).is(Blocks.STONE);
                int used=16-mob.getItem(7).getCount();
                boolean realPaid=used==controller.placed()
                        &&used>=7&&used<=16;
                System.out.println("[DOME SCAFFOLD PHYSICAL] "
                        +"roofReached="+roofReached+" intact="+intact
                        +" paid="+realPaid+" used="+used
                        +" climbs="+controller.climbs()
                        +" dismounts="+controller.dismounts());
                release.run();
                if(!roofReached||!intact||!realPaid){
                    h.fail("Scaffold access wasn't fully physical or paid");
                    return;
                }
                h.succeed();
            }else if(tick>3000){
                release.run();
                h.fail("Could not climb and dismount scaffold: "+controller.status()
                        +" workerFeet="+mob.blockPosition()
                        +" entityTicks="+mob.tickCount);
            }
        });
    }
}
