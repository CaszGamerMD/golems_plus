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

    @Test void stagnantWaypointWalkToCertifiedLaunchMustReplanQuickly(){
        assertEquals(115,BuilderMultiLegTravel.launchProgressTimeout(true,true));
        assertEquals(400,BuilderMultiLegTravel.launchProgressTimeout(false,true));
        assertEquals(400,BuilderMultiLegTravel.launchProgressTimeout(true,false));
        assertEquals(850,BuilderMultiLegTravel.rejectionWindow(
                "stalled_launch_approach",true));
        assertEquals(240,BuilderMultiLegTravel.rejectionWindow(
                "stalled_launch_approach",false));
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

    @Test void emergencyRecoveryOnlyChoosesRealGoalProgress(){
        assertTrue(BuilderFlight.worthwhileHoverProgress(30,25));
        assertFalse(BuilderFlight.worthwhileHoverProgress(30,29));
        assertFalse(BuilderFlight.worthwhileHoverProgress(30,30));
        assertFalse(BuilderFlight.worthwhileHoverProgress(Double.POSITIVE_INFINITY,1));
        assertFalse(BuilderFlight.worthwhileHoverProgress(1,Double.NaN));
    }

    @Test void disconnectedMediumRoofPocketCanRetreatRatherThanLoop(){
        assertTrue(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                220,56,8,2,true,false,true));
        assertFalse(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                220,56,8,2,true,false,false),
                "Connected roof goals must not retreat");
        assertFalse(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                220,260,8,2,true,false,true),
                "Do not abandon a large physically connected roof");
        assertFalse(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                169,56,8,2,true,false,true));
        assertFalse(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                220,56,8,2,false,false,true));
        assertFalse(BuilderWaypointTravel.shouldEscapeDisconnectedRoof(
                220,56,8,2,true,true,true));
    }

    @Test void staleGroundFlagRequiresExactStationaryPhysicalContact(){
        assertTrue(BuilderFlight.validGroundContact(
                true,false,false,.001,.12,-.15,.001));
        assertFalse(BuilderFlight.validGroundContact(
                false,false,false,0,0,0,0));
        assertFalse(BuilderFlight.validGroundContact(
                true,true,false,0,0,0,0));
        assertFalse(BuilderFlight.validGroundContact(
                true,false,true,0,0,0,0));
        assertFalse(BuilderFlight.validGroundContact(
                true,false,false,.09,0,0,0),
                "Near a roof is not physical contact");
        assertFalse(BuilderFlight.validGroundContact(
                true,false,false,0,.25,0,0),
                "An unsupported edge cannot be treated as grounded");
        assertFalse(BuilderFlight.validGroundContact(
                true,false,false,0,0,0,.2),
                "Do not interfere with falling or moving golems");
    }

    @Test void progressiveRoofHopRequiresActualLateralGainAndBoundedElevation(){
        BlockPos from=new BlockPos(1057,-28,1047);
        BlockPos goal=new BlockPos(1046,-23,1048);
        assertTrue(BuilderWaypointTravel.usefulRoofHop(from,goal,
                new BlockPos(1053,-26,1047)));
        assertFalse(BuilderWaypointTravel.usefulRoofHop(from,goal,
                new BlockPos(1057,-25,1047)),
                "Climbing without passing a roof seam does not solve a stalled walk");
        assertFalse(BuilderWaypointTravel.usefulRoofHop(from,goal,
                new BlockPos(1060,-25,1047)),
                "Do not send the worker farther into an unhelpful roof pocket");
        assertFalse(BuilderWaypointTravel.usefulRoofHop(from,goal,
                new BlockPos(1052,-22,1047)),
                "No new unsupported high station");
        assertFalse(BuilderWaypointTravel.usefulRoofHop(from,goal,
                new BlockPos(1052,-31,1047)),
                "Do not hop downward off the roof to make horizontal progress");
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
    @Test void experimentalRoofSeamKeepsShortBoundedFlightsOnly(){
        assertEquals(2.0,BuilderFlight.roofHopLimit(false,false));
        assertEquals(2.0,BuilderFlight.roofHopLimit(false,true));
        assertEquals(3.5,BuilderFlight.roofHopLimit(true,false));
        assertEquals(4.5,BuilderFlight.roofHopLimit(true,true));
    }
    @Test void waypointGrappleRejectsFrozenApproachesWithoutPunishingMotion(){
        assertFalse(BuilderGrappleAccess.stationaryApproachExpired(44,true));
        assertTrue(BuilderGrappleAccess.stationaryApproachExpired(45,true));
        assertFalse(BuilderGrappleAccess.stationaryApproachExpired(200,false));
        assertEquals(900,BuilderGrappleAccess.rejectionTicks(true));
        assertEquals(300,BuilderGrappleAccess.rejectionTicks(false));
    }
    @Test void recoveryPrefersUntriedSupportedLandingButRetainsFallback(){
        BlockPos repeated=new BlockPos(4,20,5);
        BlockPos alternate=new BlockPos(5,20,6);
        BlockPos lastResort=new BlockPos(6,20,6);
        List<BlockPos> safeCandidates=List.of(repeated,alternate,lastResort);
        assertEquals(List.of(alternate,repeated,lastResort),
                BuilderFlight.prioritizeRecoveryLandings(safeCandidates,
                        Set.of(repeated,lastResort)),
                "A certified alternative gets first chance before cycling");
        assertEquals(safeCandidates,
                BuilderFlight.prioritizeRecoveryLandings(safeCandidates,
                        Set.copyOf(safeCandidates)),
                "Never discard all possible safe recovery landings");
        assertEquals(safeCandidates,
                BuilderFlight.prioritizeRecoveryLandings(safeCandidates,Set.of()));
        assertEquals(List.of(),
                BuilderFlight.prioritizeRecoveryLandings(List.of(),Set.of(repeated)));
    }
    @Test void exteriorCollisionClearGatewayBeatsSkyVisibleBlockedRoof(){
        BlockPos goal=new BlockPos(0,36,0);
        BlockPos directlyUnder=new BlockPos(0,10,0);
        BlockPos underGlassRoof=new BlockPos(3,10,0);
        BlockPos clearExterior=new BlockPos(17,10,0);
        List<BlockPos> ranked=BuilderWaypointTravel.rankAscentGatewayColumns(goal,
                List.of(underGlassRoof,directlyUnder,clearExterior),
                p->p.equals(clearExterior)?36:p.equals(underGlassRoof)?24:22);
        assertEquals(List.of(clearExterior,underGlassRoof,directlyUnder),ranked,
                "Closer x/z is not a good ascent when a collision roof obstructs lift");
    }
    @Test void slopedLaunchSurveyCannotSkipPhysicalStandingValidation(){
        BlockPos start=new BlockPos(10,30,10);
        List<BlockPos> original=BuilderMultiLegTravel.candidateLaunchCells(start,false);
        assertEquals(13,original.size(),
                "Ordinary builders retain all twelve original same-Y approaches");
        assertTrue(original.stream().allMatch(p->p.getY()==start.getY()));
        List<BlockPos> slopes=BuilderMultiLegTravel.candidateLaunchCells(start,true);
        assertTrue(slopes.contains(start.offset(1,1,0)));
        assertTrue(slopes.contains(start.offset(2,-1,1*2)));
        assertTrue(slopes.contains(start.offset(-3,2,-3)));
        assertFalse(slopes.contains(start.offset(4,0,0)),
                "Every candidate remains in the bounded local launch survey");
    }
}
