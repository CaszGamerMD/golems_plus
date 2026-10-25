package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Movement-only vertical navigation trials.
 * Unlike the schematic builder, these fixtures provide known safe scaffolds
 * so we can distinguish path execution defects from material/planning defects.
 * No teleportation, gravity bypass, or force-moving through terrain.
 */
public final class BuilderVerticalNavigationGameTest {
    private static BuilderGolem worker(GameTestHelper helper,int x,int y,int z){
        ServerLevel world=helper.getLevel();
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=helper.absolutePos(new BlockPos(x,y,z));
        builder.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        world.addFreshEntity(builder);
        return builder;
    }
    private static boolean close(BuilderGolem worker,BlockPos tile,double radius){
        double dx=worker.getX()-tile.getX()-.5;
        double dz=worker.getZ()-tile.getZ()-.5;
        return dx*dx+dz*dz<radius*radius
                &&Math.abs(worker.getY()-tile.getY())<.30;
    }
    private static void ground(GameTestHelper helper){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
    }
    /** Climb to Y+5, walk *around* a wall at the upper elevation, return,
     * physically descend, and land on real ground. Tests the handoffs that
     * normal flat-path smoke tests cannot exercise.
     */
    @GameTest(maxTicks=1300,skyAccess=true)
    public void climbsDetoursAtYPlusFiveReturnsAndDescends(GameTestHelper helper){
        ground(helper);
        for(int y=2;y<=6;y++)helper.setBlock(1,y,3,Blocks.SCAFFOLDING);
        // Elevated five-by-five perimeter, with a blocked short route.
        for(int x=2;x<=6;x++)for(int z=1;z<=5;z++)
            if(x==2||x==6||z==1||z==5)
                helper.setBlock(x,6,z,Blocks.STONE);
        helper.setBlock(4,7,1,Blocks.COBBLESTONE);
        helper.setBlock(4,8,1,Blocks.COBBLESTONE);
        BlockPos column=helper.absolutePos(new BlockPos(1,2,3));
        BlockPos enter=helper.absolutePos(new BlockPos(2,7,3));
        BlockPos farSide=helper.absolutePos(new BlockPos(6,7,3));
        BuilderGolem builder=worker(helper,1,2,2);
        final int[] phase={0},lastProgress={(int)helper.getTick()};
        final boolean[] tookDetour={false},touchedTop={false},cameDown={false};
        BuilderScaffolding.begin(helper.getLevel(),builder,column,
                helper.absolutePos(new BlockPos(1,7,3)).getY(),false);
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Y+ Builder disappeared at phase "+phase[0]);return;
            }
            int tick=(int)helper.getTick();
            if(tick-lastProgress[0]>250){
                helper.fail("Y+ obstacle trial stalled phase="+phase[0]
                        +" feet="+builder.position()
                        +" nav="+WorkerNavigation.debugStatus(builder)
                        +" climb="+BuilderScaffolding.debugClimb(builder));
                return;
            }
            if(phase[0]==0){
                if(builder.getY()>=enter.getY()-.16){
                    touchedTop[0]=true;
                    if(!BuilderScaffolding.stepToPlatform(
                            helper.getLevel(),builder,enter)){
                        helper.fail("Cannot step off column onto upper ring");return;
                    }
                    phase[0]=1;lastProgress[0]=tick;
                }
            }else if(phase[0]==1){
                if(close(builder,enter,.70)&&builder.onGround()){
                    phase[0]=2;lastProgress[0]=tick;
                }
            }else if(phase[0]==2){
                // z=1 contains the wall. Either direction around the
                // elevated ring is valid, but no route THROUGH the wall.
                if(builder.getZ()>helper.absolutePos(
                        new BlockPos(0,7,4)).getZ()+.5)
                    tookDetour[0]=true;
                if(close(builder,farSide,.75)&&builder.onGround()){
                    if(!tookDetour[0]){
                        helper.fail("No confirmed far-side detour");return;
                    }
                    phase[0]=3;lastProgress[0]=tick;
                }else if(tick%5==0){
                    WorkerNavigation.routeToExact(
                            helper.getLevel(),builder,farSide,1.0);
                }
            }else if(phase[0]==3){
                if(close(builder,enter,.65)&&builder.onGround()){
                    BuilderScaffolding.begin(helper.getLevel(),builder,column,
                            column.getY()+1.0,true);
                    phase[0]=4;lastProgress[0]=tick;
                }else if(tick%5==0){
                    WorkerNavigation.routeToExact(
                            helper.getLevel(),builder,enter,1.0);
                }
            }else if(phase[0]==4){
                if(builder.getY()<=column.getY()+1.16){
                    cameDown[0]=true;
                    if(!BuilderScaffolding.dismountAtBase(
                            helper.getLevel(),builder,column)){
                        helper.fail("No safe dismount from scaffold");return;
                    }
                    phase[0]=5;lastProgress[0]=tick;
                }
            }else if(phase[0]==5){
                BlockPos below=builder.blockPosition().below();
                double dx=builder.getX()-column.getX()-.5;
                double dz=builder.getZ()-column.getZ()-.5;
                if(touchedTop[0]&&cameDown[0]&&builder.onGround()
                        &&dx*dx+dz*dz>1.0
                        &&Math.abs(builder.getY()-column.getY())<.3
                        &&helper.getLevel().getBlockState(below).is(Blocks.STONE))
                    helper.succeed();
            }
        });
    }

    /** Bridge feasibility: actual Minecraft pathfinding and physics across
     * two preplaced temporary scaffold spans at one level. Deliberately does
     * NOT claim the builder has learned to place/recover those blocks.
     * A failure tells us a horizontal scaffold bridge needs a special
     * movement handoff rather than blindly using vanilla ground A*.
     */
    @GameTest(maxTicks=650,skyAccess=true)
    public void traversesPrebuiltTemporaryScaffoldBridge(GameTestHelper helper){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Raise the entire bridge above the ground: walking underneath
        // is not a legitimate alternative.
        for(int x=0;x<=2;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,6,z,Blocks.STONE);
        for(int x=5;x<8;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,6,z,Blocks.STONE);
        // Scaffold approach anchors, then two horizontal bridge spans.
        helper.setBlock(2,7,3,Blocks.SCAFFOLDING);
        helper.setBlock(3,7,3,Blocks.SCAFFOLDING);
        helper.setBlock(4,7,3,Blocks.SCAFFOLDING);
        // Standing on scaffold at y=8; solid landing is intentionally
        // one block lower. It must descend safely without falling.
        BlockPos destination=helper.absolutePos(new BlockPos(6,7,3));
        BuilderGolem builder=worker(helper,1,7,3);
        final boolean[] crossed={false};
        final double[] last={builder.getX()};
        final int[] stuck={0};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Bridge Builder vanished");return;
            }
            if(builder.getX()>helper.absolutePos(
                    new BlockPos(4,7,3)).getX()+.5
                    &&builder.getY()>=destination.getY()-.8)
                crossed[0]=true;
            if(close(builder,destination,.70)&&builder.onGround()
                    &&crossed[0]){
                helper.succeed();return;
            }
            if(Math.abs(builder.getX()-last[0])>.06){
                last[0]=builder.getX();stuck[0]=0;
            }else stuck[0]++;
            if(stuck[0]>180){
                helper.fail("Scaffold bridge failed pos="+builder.position()
                        +" nav="+WorkerNavigation.debugStatus(builder));
                return;
            }
            if(helper.getTick()%5==0)
                WorkerNavigation.routeToExact(
                        helper.getLevel(),builder,destination,1.0);
        });
    }
}
