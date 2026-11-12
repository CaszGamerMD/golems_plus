package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Physical rail-pocket rescue, including a sealed-shaft safety guard. */
public final class BuilderRailPocketEscapeGameTest {
    private static void ground(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
    }
    private static BuilderGolem worker(GameTestHelper h,BlockPos feet){
        ServerLevel level=h.getLevel();
        BuilderGolem g=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        g.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
        g.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        level.addFreshEntity(g);
        return g;
    }
    private static void glassPocket(GameTestHelper h){
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        h.setBlock(3,2,3,Blocks.RAIL);
        for(var dir:net.minecraft.core.Direction.Plane.HORIZONTAL){
            BlockPos adjacent=start.relative(dir);
            h.getLevel().setBlock(adjacent,Blocks.GLASS.defaultBlockState(),3);
            h.getLevel().setBlock(adjacent.above(),Blocks.GLASS.defaultBlockState(),3);
        }
    }
    @GameTest(maxTicks=300,skyAccess=true)
    public void trappedOnRailClimbsRealGlassWallWithoutBreakingRail(GameTestHelper h){
        ground(h);
        glassPocket(h);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        ServerLevel level=h.getLevel();
        h.setBlock(1,2,1,Blocks.CHEST);
        h.setBlock(1,2,2,WorksiteContent.BUILDER_TABLE);
        var owner=h.makeMockServerPlayerInLevel();
        if(!(level.getBlockEntity(h.absolutePos(new BlockPos(1,2,2)))
                    instanceof WorkerStationBlockEntity table)
                ||!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),"placed-rail-pocket.gpb",
                        "Rail pocket return",start,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                        h.absolutePos(new BlockPos(1,2,1)))){
            h.fail("Could not link OnSite Storage for rail-pocket return");
            return;
        }
        BuilderGolem g=worker(h,start);
        final boolean[] started={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(!g.isAlive()){
                h.fail("Rail-pocket Builder died");return;
            }
            if(h.getTick()%5==0&&!started[0])
                started[0]=BuilderGrappleAccess.rappelForTravel(level,g,table);
            if(started[0]&&!BuilderGrappleAccess.active(g)
                    &&g.onGround()&&g.getY()>=start.getY()+2){
                if(!level.getBlockState(start).is(Blocks.RAIL)){
                    h.fail("Rail pocket escape damaged original track");return;
                }
                System.out.println("[RAIL POCKET ESCAPE] PASS physical climb "
                        +g.blockPosition());
                h.succeed();return;
            }
            if(h.getTick()>=260)
                h.fail("Rail pocket escape stalled attached="+started[0]
                        +" feet="+g.position()+" rope="
                        +BuilderGrappleAccess.debug(g));
        });
    }
    @GameTest(maxTicks=300,skyAccess=true)
    public void waterloggedRootsFloorAlsoEscapesOpenGlassPocket(GameTestHelper h){
        ground(h);
        glassPocket(h);
        h.setBlock(3,1,3,Blocks.MANGROVE_ROOTS.defaultBlockState()
                .setValue(BlockStateProperties.WATERLOGGED,true));
        h.setBlock(3,2,3,Blocks.AIR);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        var g=worker(h,start);
        final boolean[] attached={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(!g.isAlive()){
                h.fail("Waterlogged-root pocket Builder died");return;
            }
            if(h.getTick()%5==0&&!attached[0])
                attached[0]=BuilderGrappleAccess.escapeRailPocket(
                        h.getLevel(),g,h.absolutePos(new BlockPos(1,2,1)));
            if(attached[0]&&!BuilderGrappleAccess.active(g)
                    &&g.onGround()&&g.getY()>=start.getY()+2){
                if(!h.getLevel().getBlockState(start.below())
                        .is(Blocks.MANGROVE_ROOTS)){
                    h.fail("Pocket escape changed waterlogged roots");return;
                }
                System.out.println("[ROOT POCKET ESCAPE] PASS "
                        +g.blockPosition());
                h.succeed();return;
            }
            if(h.getTick()>=260)
                h.fail("Waterlogged root pocket exit stalled attached="
                        +attached[0]+" feet="+g.position()+" rope="
                        +BuilderGrappleAccess.debug(g));
        });
    }
    @GameTest(maxTicks=400,skyAccess=true)
    public void bobbingAboveSoulSandEscapesOpenWaterPocket(GameTestHelper h){
        ground(h);
        glassPocket(h);
        h.setBlock(3,1,3,Blocks.SOUL_SAND);
        h.setBlock(3,2,3,Blocks.WATER);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        var g=worker(h,start);
        final boolean[] attached={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(h.getTick()%5==0&&!attached[0])
                attached[0]=BuilderGrappleAccess.escapeRailPocket(
                        h.getLevel(),g,h.absolutePos(new BlockPos(1,2,1)));
            if(attached[0]&&!BuilderGrappleAccess.active(g)
                    &&g.onGround()&&g.getY()>=start.getY()+2){
                if(!h.getLevel().getBlockState(start.below()).is(Blocks.SOUL_SAND)
                        ||h.getLevel().getFluidState(start).isEmpty()){
                    h.fail("Water escape changed the schematic");return;
                }
                System.out.println("[WATER POCKET ESCAPE] PASS "+g.position());
                h.succeed();return;
            }
            if(h.getTick()>=360)h.fail("Water pocket exit stalled attached="
                    +attached[0]+" feet="+g.position()+" rope="
                    +BuilderGrappleAccess.debug(g));
        });
    }
    @GameTest(maxTicks=400,skyAccess=true)
    public void bobbingAboveSoulSandEscapesOneBlockRim(GameTestHelper h){
        ground(h);
        glassPocket(h);
        for(var side:net.minecraft.core.Direction.Plane.HORIZONTAL)
            h.getLevel().setBlock(h.absolutePos(new BlockPos(3,3,3)).relative(side),
                    Blocks.AIR.defaultBlockState(),3);
        h.setBlock(3,1,3,Blocks.SOUL_SAND);
        h.setBlock(3,2,3,Blocks.WATER);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        var g=worker(h,start);
        final boolean[] attached={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(h.getTick()%5==0&&!attached[0])
                attached[0]=BuilderGrappleAccess.escapeRailPocket(
                        h.getLevel(),g,h.absolutePos(new BlockPos(1,2,1)));
            if(attached[0]&&!BuilderGrappleAccess.active(g)
                    &&g.onGround()&&g.getY()>=start.getY()+1){
                if(!h.getLevel().getBlockState(start.below()).is(Blocks.SOUL_SAND)
                        ||h.getLevel().getFluidState(start).isEmpty()){
                    h.fail("Water escape changed the schematic");return;
                }
                System.out.println("[WATER POCKET ESCAPE] PASS "+g.position());
                h.succeed();return;
            }
            if(h.getTick()>=360)h.fail("Water pocket exit stalled attached="
                    +attached[0]+" feet="+g.position()+" rope="
                    +BuilderGrappleAccess.debug(g));
        });
    }
    @GameTest(maxTicks=180,skyAccess=true)
    public void roofedRailPocketNeverGrapplesThroughCeiling(GameTestHelper h){
        ground(h);
        glassPocket(h);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        h.setBlock(3,4,3,Blocks.GLASS);
        BuilderGolem g=worker(h,start);
        h.onEachTick(()->{
            if(h.getTick()<10||h.getTick()%5!=0)return;
            if(BuilderGrappleAccess.escapeRailPocket(h.getLevel(),g,start)
                    ||BuilderGrappleAccess.active(g)){
                h.fail("Grapple went through closed glass ceiling");return;
            }
            if(h.getTick()>=140)h.succeed();
        });
    }
}
