package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderWaypointTravelTest {
    @Test void prefersRealConnectedProgressOverUnreachableDirectLine(){
        BlockPos from=new BlockPos(0,10,0),goal=new BlockPos(25,30,0);
        BlockPos side=new BlockPos(12,10,3),forward=new BlockPos(9,10,0);
        assertEquals(side,BuilderWaypointTravel.chooseStage(from,goal,
                Set.of(from,side,forward),List.of()));
        assertEquals(forward,BuilderWaypointTravel.chooseStage(from,goal,
                Set.of(from,side,forward),List.of(side)));
    }
    @Test void acceptsSafeSidewaysDetourButRejectsLoopsAndUnsupportedComponents(){
        BlockPos from=new BlockPos(10,20,10),goal=new BlockPos(14,32,18);
        BlockPos sideways=from.offset(-1,0,8),near=from.offset(0,0,1);
        assertEquals(sideways,BuilderWaypointTravel.chooseStage(from,goal,
                Set.of(from,sideways,near),List.of()));
        assertNull(BuilderWaypointTravel.chooseStage(from,goal,
                Set.of(from,sideways,near),List.of(sideways)));
        assertNull(BuilderWaypointTravel.chooseStage(from,goal,
                Set.of(from,new BlockPos(150,20,150)),List.of()));
    }
    @Test void groundDestinationsShouldNotNeedHorizontalFlight(){
        BlockPos ground=new BlockPos(0,10,0);
        assertTrue(BuilderWaypointTravel.shouldApproachOnFoot(ground,
                new BlockPos(60,10,12)));
        assertTrue(BuilderWaypointTravel.needsElevation(ground,
                new BlockPos(6,30,0)));
        assertFalse(BuilderWaypointTravel.shouldApproachOnFoot(ground,
                new BlockPos(6,30,0)));
        assertTrue(BuilderWaypointTravel.shouldApproachOnFoot(ground,
                new BlockPos(38,30,0)));
    }
}
