package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;

/** Physical 26.2 multiple-worker traffic test. No virtual movement, no
 * teleporting after spawn, no doorway shortcut: all four bodies must
 * enter the exact one-wide threshold and leave it on the far side.
 */
public final class BuilderDoorCrowdGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=2200,skyAccess=true)
    public void fourWorkersCrossOneOakDoor(GameTestHelper helper){
        run(helper,false);
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=2200,skyAccess=true)
    public void fourWorkersCrossOneFenceGate(GameTestHelper helper){
        run(helper,true);
    }

    private static void run(GameTestHelper helper,boolean fence){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int z=0;z<8;z++)for(int y=2;y<=4;y++)
            helper.setBlock(3,y,z,Blocks.COBBLESTONE);
        helper.setBlock(3,2,3,Blocks.AIR);
        helper.setBlock(3,3,3,Blocks.AIR);
        if(fence){
            BlockState gate=Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING,Direction.EAST)
                    .setValue(FenceGateBlock.OPEN,false);
            helper.setBlock(3,2,3,gate);
        }else{
            BlockState door=Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING,Direction.EAST)
                    .setValue(DoorBlock.OPEN,false);
            helper.setBlock(3,2,3,door.setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER));
            helper.setBlock(3,3,3,door.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
        }
        BlockPos threshold=helper.absolutePos(new BlockPos(3,2,3));
        List<BuilderGolem> crew=new ArrayList<>();
        List<BlockPos> destinations=new ArrayList<>();
        boolean[] entered=new boolean[4],finished=new boolean[4];
        for(int i=0;i<4;i++){
            int z=1+i;
            BlockPos start=helper.absolutePos(new BlockPos(1,2,z));
            BlockPos target=helper.absolutePos(new BlockPos(6,2,z));
            BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            level.addFreshEntity(mob);
            crew.add(mob);destinations.add(target);
        }
        final int[] lastCompleted={0};
        final long[] lastProgress={0};
        final double[] lastPositions=new double[8];
        for(int i=0;i<4;i++){
            lastPositions[2*i]=crew.get(i).getX();
            lastPositions[2*i+1]=crew.get(i).getZ();
        }
        helper.onEachTick(()->{
            int complete=0;
            boolean moving=false;
            for(int i=0;i<4;i++){
                BuilderGolem mob=crew.get(i);
                if(!mob.isAlive()){
                    helper.fail("Crowded door lost physical Builder "+i);return;
                }
                if(mob.getBoundingBox().intersects(new AABB(threshold)))
                    entered[i]=true;
                BlockPos goal=destinations.get(i);
                if(entered[i]&&mob.onGround()
                        &&mob.distanceToSqr(goal.getX()+.5,
                                goal.getY(),goal.getZ()+.5)<.9)
                    finished[i]=true;
                if(finished[i])complete++;
                if(Math.hypot(mob.getX()-lastPositions[2*i],
                        mob.getZ()-lastPositions[2*i+1])>.11)
                    moving=true;
                lastPositions[2*i]=mob.getX();
                lastPositions[2*i+1]=mob.getZ();
                if(helper.getTick()%5==0&&!finished[i])
                    WorkerNavigation.routeTo(level,mob,goal,1.0);
            }
            if(complete==4){
                System.out.println("[DOME CROWD] PASS 4/4 real workers door="+
                        (fence?"fence-gate":"oak-door")+" ticks="+helper.getTick());
                helper.succeed();return;
            }
            if(moving||complete>lastCompleted[0]){
                lastProgress[0]=helper.getTick();lastCompleted[0]=complete;
            }
            if(helper.getTick()-lastProgress[0]>320){
                StringBuilder s=new StringBuilder("Crowd deadlocked "+
                        (fence?"fence gate":"oak door")+" at tick="+helper.getTick()
                        +" completed="+complete+"/4 ");
                for(int i=0;i<4;i++)
                    s.append(" worker").append(i).append(" ")
                        .append(crew.get(i).position())
                        .append(" entered=").append(entered[i])
                        .append(" ").append(WorkerNavigation.debugStatus(crew.get(i)));
                helper.fail(s.toString());
            }
        });
    }
}
