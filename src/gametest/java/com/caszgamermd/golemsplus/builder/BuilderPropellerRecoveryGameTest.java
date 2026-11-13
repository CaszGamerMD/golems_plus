package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Isolated recovery fixture: no neighboring long test builds. */
public final class BuilderPropellerRecoveryGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=200,skyAccess=true)
    public void poweredExitPreservesScaffoldAndReachesSolidLanding(GameTestHelper h){
        var level=h.getLevel();
        for(int x=6;x<=16;x++)for(int z=6;z<=16;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=11;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int y=2;y<=6;y++)h.setBlock(10,y,10,Blocks.SCAFFOLDING);
        for(int x=12;x<=15;x++)for(int z=9;z<=12;z++)h.setBlock(x,6,z,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(10,7,10));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        Vec3[] anchor={null};
        h.onEachTick(()->{
            if(h.getTick()==5){
                anchor[0]=worker.position();
                if(!BuilderFlight.exitStalledScaffold(level,worker)){
                    h.fail("No physical powered exit from scaffold");return;
                }
            }
            if(h.getTick()<=5||anchor[0]==null)return;
            double dx=worker.getX()-anchor[0].x,dz=worker.getZ()-anchor[0].z;
            if(dx*dx+dz*dz>4.001){h.fail("Recovery exceeded 2m flight envelope");return;}
            if(!BuilderFlight.active(worker)&&worker.onGround()){
                if(!level.getBlockState(worker.blockPosition().below()).is(Blocks.STONE)
                        ||!level.getBlockState(start.below()).is(Blocks.SCAFFOLDING)){
                    h.fail("Recovery did not land on real stone or destroyed scaffold");return;
                }
                System.out.println("[POWERED SCAFFOLD EXIT PASS] tick="+h.getTick()+" landing="+worker.blockPosition());
                h.succeed();
            }
        });
    }
}
