package com.caszgamermd.golemsplus.builder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BuilderAccessOptionSelectorTest {
    private static final BlockPos TARGET=new BlockPos(9,5,9);
    private static final List<BuilderAccessOptionSelector.Offer> OPTIONS=List.of(
            new BuilderAccessOptionSelector.Offer(
                    BuilderAccessOptionSelector.Tool.WALK,10,true),
            new BuilderAccessOptionSelector.Offer(
                    BuilderAccessOptionSelector.Tool.GRAPPLE,30,true),
            new BuilderAccessOptionSelector.Offer(
                    BuilderAccessOptionSelector.Tool.SCAFFOLD,50,true));

    @Test void cheapWalkWinsWhileItIsMakingProgress(){
        var selector=new BuilderAccessOptionSelector();
        var p=new Vec3(0.5,5,0.5);
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET,p,10,OPTIONS));
        // Distance to the goal grew; going backwards is still movement.
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET,new Vec3(-0.5,5,0.5),100,OPTIONS));
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET,new Vec3(-0.5,5,0.5),170,OPTIONS));
    }

    @Test void stationaryToolYieldsToTheNextViableOffer(){
        var selector=new BuilderAccessOptionSelector();
        var p=new Vec3(0.5,5,0.5);
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET,p,0,OPTIONS));
        assertEquals(BuilderAccessOptionSelector.Tool.GRAPPLE,
                selector.choose(TARGET,p,101,OPTIONS));
        assertEquals(BuilderAccessOptionSelector.Tool.SCAFFOLD,
                selector.choose(TARGET,p,202,OPTIONS));
    }

    @Test void noHookCanOfferScaffoldingWithoutFabricatingAccess(){
        var selector=new BuilderAccessOptionSelector();
        var p=new Vec3(0,5,0);
        var offers=List.of(
                new BuilderAccessOptionSelector.Offer(
                        BuilderAccessOptionSelector.Tool.WALK,10,false),
                new BuilderAccessOptionSelector.Offer(
                        BuilderAccessOptionSelector.Tool.GRAPPLE,30,false),
                new BuilderAccessOptionSelector.Offer(
                        BuilderAccessOptionSelector.Tool.SCAFFOLD,60,true));
        assertEquals(BuilderAccessOptionSelector.Tool.SCAFFOLD,
                selector.choose(TARGET,p,0,offers));
        selector.reject(BuilderAccessOptionSelector.Tool.SCAFFOLD,1);
        assertNull(selector.choose(TARGET,p,2,offers));
    }

    @Test void differentTargetResetsOldToolFailures(){
        var selector=new BuilderAccessOptionSelector();
        var p=new Vec3(0,5,0);
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET,p,0,OPTIONS));
        selector.reject(BuilderAccessOptionSelector.Tool.WALK,1);
        assertEquals(BuilderAccessOptionSelector.Tool.GRAPPLE,
                selector.choose(TARGET,p,2,OPTIONS));
        assertEquals(BuilderAccessOptionSelector.Tool.WALK,
                selector.choose(TARGET.east(),p,3,OPTIONS));
    }
}
