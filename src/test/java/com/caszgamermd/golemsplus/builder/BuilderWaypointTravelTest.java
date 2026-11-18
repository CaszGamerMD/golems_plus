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
    @Test void roofGatewayCanLegitimatelyLeadAwayFromTargetFirst(){
        BlockPos start=new BlockPos(0,10,0),roofAbove=new BlockPos(0,34,0);
        BlockPos underCeiling=new BlockPos(5,10,0);
        BlockPos exterior=new BlockPos(-13,10,0);
        assertEquals(exterior,BuilderWaypointTravel.chooseExposedGateway(
                start,roofAbove,Set.of(start,underCeiling,exterior),
                List.of(),p->p.equals(exterior)),
                "A connected open-sky exterior pose must outrank the blocked direct route");
        assertNull(BuilderWaypointTravel.chooseExposedGateway(
                start,roofAbove,Set.of(start,underCeiling),
                List.of(),p->false));
        assertNull(BuilderWaypointTravel.chooseExposedGateway(
                start,roofAbove,Set.of(start,underCeiling,exterior),
                List.of(exterior),p->p.equals(exterior)),
                "Do not repeat a known exhausted exterior gateway");
    }
    @Test void roofGatewayCannotInventUnsupportedOrDistantStandingPositions(){
        BlockPos start=new BlockPos(0,10,0),roofAbove=new BlockPos(1,30,0);
        BlockPos distant=new BlockPos(45,10,0);
        BlockPos high=new BlockPos(-8,16,0);
        assertNull(BuilderWaypointTravel.chooseExposedGateway(
                start,roofAbove,Set.of(start,distant,high),
                List.of(),p->true));
    }
    @Test void blockedRoofCanRetreatToARealEdgeRatherThanStallingUnderTarget(){
        BlockPos start=new BlockPos(0,10,0),goal=new BlockPos(0,34,0);
        BlockPos interior=start.offset(5,0,0);
        BlockPos lowRoofEdge=start.offset(-12,0,0);
        BlockPos exterior=start.offset(16,0,0);
        Set<BlockPos> connected=Set.of(start,interior,lowRoofEdge,exterior);
        assertEquals(exterior,BuilderWaypointTravel.chooseRoofRetreat(
                start,goal,connected,List.of(),
                p->p.equals(interior)?24:p.equals(lowRoofEdge)?12:36,
                p->p.equals(exterior)),
                "Reachable sky-exposed ground is a legal outward detour");
        assertEquals(lowRoofEdge,BuilderWaypointTravel.chooseRoofRetreat(
                start,goal,connected,List.of(exterior),
                p->p.equals(interior)?24:p.equals(lowRoofEdge)?12:36,
                p->false),
                "A shorter real roof edge outranks a blocked interior column");
        assertNull(BuilderWaypointTravel.chooseRoofRetreat(
                start,goal,connected,List.of(interior,lowRoofEdge,exterior),
                p->15,p->true),
                "Visited or absent ground stances must never be fabricated");
    }

    @Test void emergencyRoofDescentOnlyOccursOnStalledSmallIslands(){
        assertTrue(BuilderWaypointTravel.shouldEscapeRoofIsland(
                170,5,9,1,true,false));
        assertTrue(BuilderWaypointTravel.shouldEscapeRoofIsland(
                240,0,3,0,true,false),
                "A fractional perch with no centered ground cells must be recoverable");
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                169,5,9,1,true,false));
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                220,25,9,1,true,false),
                "Do not abandon a substantial traversable roof component");
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                220,5,20,1,true,false));
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                220,5,9,5,true,false));
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                220,5,9,1,false,false));
        assertFalse(BuilderWaypointTravel.shouldEscapeRoofIsland(
                220,5,9,1,true,true),
                "Never interrupt a collision-certified powered flight");
    }

    @Test void rejectBadPoweredLegForWholeAttemptWithoutPoisoningTransientTraffic(){
        assertEquals(850,BuilderMultiLegTravel.rejectionWindow(
                "repeated_centered_launch_reject",true));
        assertEquals(850,BuilderMultiLegTravel.rejectionWindow(
                "stopped_hover_landing_recovery",true));
        assertEquals(850,BuilderMultiLegTravel.rejectionWindow(
                "flight_ended_short",true));
        assertEquals(240,BuilderMultiLegTravel.rejectionWindow(
                "timeout_or_blocked",true));
        assertEquals(240,BuilderMultiLegTravel.rejectionWindow(
                "repeated_centered_launch_reject",false));
    }

    @Test void strandedFlightGetsOnlyOneSafeEmergencyEnvelope(){
        assertTrue(BuilderFlight.canReanchorStoppedHover(true,false,false,false),
                "A stopped powered hover may rescue within a new physical envelope");
        assertFalse(BuilderFlight.canReanchorStoppedHover(true,true,false,false),
                "Never interrupt an in-progress collision-certified flight");
        assertFalse(BuilderFlight.canReanchorStoppedHover(true,false,true,false),
                "Do not add horizontal distance after genuine ground contact");
        assertFalse(BuilderFlight.canReanchorStoppedHover(false,false,false,false),
                "Only an existing powered flight can use the emergency recovery");
        assertFalse(BuilderFlight.canReanchorStoppedHover(true,false,false,true),
                "No chained airborne hops without landing");
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
