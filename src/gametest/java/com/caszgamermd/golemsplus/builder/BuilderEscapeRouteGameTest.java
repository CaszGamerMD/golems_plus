package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.LinkedHashSet;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/**
 * Read-only future-terrain proof on four real, grounded Builders:
 * - adjacent block can be mined while an independent floor corridor exists
 * - a sole support directly beneath a Builder CANNOT be mined
 * - a nearby new wall block is safe if a second way out remains
 * - placing a block that seals the only one-wide exit is forbidden
 *
 * No block is placed/broken as part of a preview, no fake path/teleport.
 */
public final class BuilderEscapeRouteGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1800,skyAccess=true)
    public void proveTargetIndependentExits(GameTestHelper h){
        ServerLevel level=h.getLevel();
        // Four distinct 7x7 pads inside the separate fixture. Clear only
        // the local test volume; no other GameTests share this server.
        for(int x=7;x<=36;x++)for(int z=7;z<=32;z++){
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos safeBreak=h.absolutePos(new BlockPos(12,2,11));
        BlockPos support=h.absolutePos(new BlockPos(18,1,11));
        BlockPos safePlace=h.absolutePos(new BlockPos(12,2,23));
        BlockPos plug=h.absolutePos(new BlockPos(29,2,23));
        level.setBlock(safeBreak,Blocks.STONE.defaultBlockState(),2);
        level.setBlock(support,Blocks.STONE.defaultBlockState(),2);
        // Construct a genuinely enclosed narrow lane. The Builder at
        // x28 has a 1-cell exit to x29, and a stone wall behind x27.
        for(int x=27;x<=33;x++)for(int y=2;y<=4;y++){
            h.setBlock(x,y,22,Blocks.STONE);
            h.setBlock(x,y,24,Blocks.STONE);
        }
        for(int y=2;y<=4;y++)h.setBlock(27,y,23,Blocks.STONE);

        BuilderGolem a=spawn(level,h.absolutePos(new BlockPos(11,2,11)));
        BuilderGolem b=spawn(level,h.absolutePos(new BlockPos(18,2,11)));
        BuilderGolem c=spawn(level,h.absolutePos(new BlockPos(11,2,23)));
        BuilderGolem d=spawn(level,h.absolutePos(new BlockPos(28,2,23)));

        LinkedHashSet<ChunkPos> chunks=new LinkedHashSet<>();
        for(BuilderGolem mob:new BuilderGolem[]{a,b,c,d}){
            BlockPos p=mob.blockPosition();
            chunks.add(new ChunkPos(p.getX()>>4,p.getZ()>>4));
        }
        for(ChunkPos chunk:chunks){
            level.setChunkForced(chunk.x(),chunk.z(),true);
            BuilderChunkTickets.holdPhysicalGameTestChunk(level,
                    new BlockPos(chunk.x()<<4,a.blockPosition().getY(),
                            chunk.z()<<4));
        }
        Runnable release=()->{
            for(ChunkPos chunk:chunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(level,
                        new BlockPos(chunk.x()<<4,a.blockPosition().getY(),
                                chunk.z()<<4));
                level.setChunkForced(chunk.x(),chunk.z(),false);
            }
        };
        final boolean[] checked={false};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%20==0)
                for(ChunkPos chunk:chunks){
                    level.setChunkForced(chunk.x(),chunk.z(),true);
                    BuilderChunkTickets.holdPhysicalGameTestChunk(level,
                            new BlockPos(chunk.x()<<4,a.blockPosition().getY(),
                                    chunk.z()<<4));
                }
            if(checked[0]||tick<80)return;
            if(tick>1450){
                release.run();h.fail("Physical Builder entities failed to tick: "
                        +"ticks="+a.tickCount+","+b.tickCount+","
                        +c.tickCount+","+d.tickCount
                        +" grounded="+a.onGround()+","+b.onGround()+","
                        +c.onGround()+","+d.onGround());
                return;
            }
            if(!a.onGround()||!b.onGround()||!c.onGround()
                    ||!d.onGround()||a.tickCount<10||b.tickCount<10
                    ||c.tickCount<10||d.tickCount<10)return;
            checked[0]=true;
            boolean okBreak=BuilderEscapeRoute.preview(level,a,
                    safeBreak,BuilderEscapeRoute.Action.BREAK)!=null;
            boolean rejectSupport=BuilderEscapeRoute.preview(level,b,
                    support,BuilderEscapeRoute.Action.BREAK)==null;
            boolean okPlace=BuilderEscapeRoute.preview(level,c,
                    safePlace,BuilderEscapeRoute.Action.PLACE)!=null;
            boolean rejectPlug=BuilderEscapeRoute.preview(level,d,
                    plug,BuilderEscapeRoute.Action.PLACE)==null;
            boolean untouched=level.getBlockState(safeBreak).is(Blocks.STONE)
                    &&level.getBlockState(support).is(Blocks.STONE)
                    &&level.getBlockState(safePlace).isAir()
                    &&level.getBlockState(plug).isAir();
            System.out.println("[BUILDER ESCAPE REGRESSION]"
                    +" safeBreak="+okBreak+" supportDenied="+rejectSupport
                    +" safePlace="+okPlace+" sealedExitDenied="+rejectPlug
                    +" worldUntouched="+untouched);
            release.run();
            if(!okBreak||!rejectSupport||!okPlace||!rejectPlug||!untouched){
                h.fail("Target-independent escape invariant failed");
                return;
            }
            h.succeed();
        });
    }
    private static BuilderGolem spawn(ServerLevel level,BlockPos feet){
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
        level.addFreshEntity(mob);
        return mob;
    }
}
