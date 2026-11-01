package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.GrappleLineVisuals;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** The grapple rope is real replicated display geometry, not CRIT particles. */
public final class GrappleLineVisualGameTest {
    @GameTest(maxTicks=100,skyAccess=true)
    public void deployedGrappleDisplaysTopHookBottomCoilAndContinuousLine(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos anchor=helper.absolutePos(new BlockPos(3,6,3));
        BlockPos column=anchor.east();
        level.setBlock(anchor,Blocks.STONE.defaultBlockState(),3);
        BlockPos bottom=column.atY(helper.absolutePos(new BlockPos(4,2,3)).getY());
        for(int y=bottom.getY();y<=anchor.getY()+1;y++)
            level.setBlock(column.atY(y),Blocks.AIR.defaultBlockState(),3);
        var line=GrappleLineVisuals.attach(level,UUID.randomUUID(),
                anchor,column,bottom.getY(),anchor.getY()+1.0);
        int expectedSegments=anchor.getY()+1-bottom.getY();
        if(line==null||line.pieceCount()!=expectedSegments+3){
            helper.fail("Missing hook/coil/vertical pieces: "
                    +(line==null?"null":line.pieceCount()));return;
        }
        AABB region=new AABB(bottom).inflate(8,14,8);
        helper.onEachTick(()->{
            if(helper.getTick()<10)return;
            var displays=level.getEntitiesOfClass(Display.ItemDisplay.class,region);
            if(count(displays,WorksiteContent.GRAPPLE_HOOK)!=1
                    ||count(displays,WorksiteContent.GRAPPLE_ROPE_COIL)!=1
                    ||count(displays,WorksiteContent.GRAPPLE_ROPE_SEGMENT)
                            !=expectedSegments
                    ||count(displays,WorksiteContent.GRAPPLE_ROPE_LINK)!=1){
                helper.fail("Grapple lacks visible hook, coil or complete rope "
                        +"displays="+displays.size());return;
            }
            // These are non-colliding visuals. A real Builder can still
            // use the same clear air column validated by grapple physics.
            line.close();
            if(!level.getEntitiesOfClass(Display.ItemDisplay.class,region).isEmpty()){
                helper.fail("Grapple release left visible equipment behind");return;
            }
            System.out.println("[GRAPPLE VISUALS] PASS hook, coil, rope="+
                    expectedSegments+" segments, release cleanup");
            helper.succeed();
        });
    }
    private static long count(java.util.List<Display.ItemDisplay> displays,Item item){
        return displays.stream().filter(e->e.getSlot(0).get().is(item)).count();
    }
}
