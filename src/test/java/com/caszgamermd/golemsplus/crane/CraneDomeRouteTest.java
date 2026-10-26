package com.caszgamermd.golemsplus.crane;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone path/dispatch regression using the same 83-segment outside rail
 * detour as the exact Dome Minecraft GameTest. These checks never claim to
 * execute Minecraft collisions or verify completed block placements.
 */
final class CraneDomeRouteTest {
    private static CranePath.Cell cell(int x,int y,int z){
        return new CranePath.Cell(x,y,z);
    }
    private static HashSet<CranePath.Cell> outerRails(){
        var rails=new HashSet<CranePath.Cell>();
        for(int z=3;z<=24;z++){
            rails.add(cell(3,2,z));
            rails.add(cell(44,2,z));
        }
        for(int x=3;x<=44;x++)rails.add(cell(x,2,3));
        return rails;
    }
    @Test void reachesTheOppositeDomeSideUsingSupportedPerimeterRails(){
        var rails=outerRails();
        var start=cell(3,2,24);
        var goal=cell(44,2,24);
        var route=CranePath.find(start,goal::equals,p->p.distance(goal),
                rails::contains,(a,b)->true,400);
        assertNotNull(route);
        assertEquals(83,route.size());
        assertEquals(goal,route.getLast());
        assertTrue(route.stream().allMatch(rails::contains));
        assertTrue(route.stream().allMatch(p->
                p.x()<=3||p.x()>=44||p.z()<=3),
                "A route must never cut through the real Dome footprint");
    }
    @Test void interruptedRailCannotBeTreatedAsAnInvisibleBridge(){
        var rails=outerRails();
        rails.remove(cell(22,2,3));
        var start=cell(3,2,24);
        var goal=cell(44,2,24);
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),
                rails::contains,(a,b)->true,400));
    }
    @Test void obstructedSweptMotionFailsClosed(){
        var rails=outerRails();
        var start=cell(3,2,24);
        var goal=cell(44,2,24);
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),
                rails::contains,(a,b)->false,400));
        assertNull(CranePath.find(start,goal::equals,p->p.distance(goal),
                rails::contains,(a,b)->true,1));
    }
    @Test void fourRiderLimitSurvivesDuplicateRequests(){
        var dispatch=new CraneDispatch();
        UUID oldest=UUID.randomUUID();
        dispatch.request(oldest,2,17,0);
        dispatch.request(oldest,2,17,100);
        for(int i=0;i<6;i++)
            dispatch.request(UUID.randomUUID(),2,17,i+1);
        assertEquals(7,dispatch.size());
        assertEquals(4,dispatch.batch().size());
        assertEquals(oldest,dispatch.batch().getFirst());
    }
    @Test void blockedRiderDoesNotStarveOneWithAnActualDock(){
        var distances=new LinkedHashMap<UUID,Double>();
        var blocked=UUID.randomUUID();
        var reachable=UUID.randomUUID();
        distances.put(blocked,Double.POSITIVE_INFINITY);
        distances.put(reachable,12.0);
        assertEquals(reachable,CraneDispatch.nearestReachable(distances));
        assertEquals(90,CraneDispatch.reach(2));
    }
}
