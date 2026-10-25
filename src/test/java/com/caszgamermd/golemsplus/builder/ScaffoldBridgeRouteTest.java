package com.caszgamermd.golemsplus.builder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ScaffoldBridgeRouteTest {
    private static BlockPos at(int x,int z){return new BlockPos(x,70,z);}

    @Test void buysExactPhysicalScaffoldCellsToReachSolidLanding(){
        BlockPos start=at(0,0),landing=at(4,0);
        Set<BlockPos> open=Set.of(at(1,0),at(2,0),at(3,0));
        var route=ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,open::contains,landing::equals);
        assertNotNull(route);
        assertEquals(3,route.required());
        assertEquals(List.of(at(1,0),at(2,0),at(3,0),landing),
                route.waypoints());
    }

    @Test void detoursHorizontallyAroundBlockedBridgeCell(){
        BlockPos start=at(0,0),landing=at(4,0),blocked=at(2,0);
        Set<BlockPos> open=Set.of(at(1,0),at(1,1),at(2,1),
                at(3,1),at(4,1));
        var route=ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,open::contains,landing::equals);
        assertNotNull(route);
        assertEquals(5,route.required());
        assertEquals(landing,route.waypoints().getLast());
        assertFalse(route.waypoints().contains(blocked));
        BlockPos previous=start;
        for(BlockPos p:route.waypoints()){
            assertEquals(1,Math.abs(p.getX()-previous.getX())
                    +Math.abs(p.getZ()-previous.getZ()),
                    "Bridge cannot cut diagonally or jump across gaps");
            previous=p;
        }
    }

    @Test void canPlaceTheLastScaffoldAsAnElevatedWorkPlatform(){
        BlockPos start=at(0,0),end=at(3,0);
        Set<BlockPos> empty=Set.of(at(1,0),at(2,0),end);
        var bridge=ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,empty::contains,
                p->false,end::equals);
        assertNotNull(bridge);
        assertEquals(3,bridge.required(),
                "Final aerial work platform must also cost an item");
        assertEquals(end,bridge.waypoints().getLast());
    }

    @Test void rejectsUnsupportedLongSpanAndUnavailableLanding(){
        BlockPos start=at(0,0),far=at(9,0);
        Set<BlockPos> empty=new HashSet<>();
        for(int x=1;x<9;x++)empty.add(at(x,0));
        assertNull(ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,empty::contains,far::equals));
        assertNull(ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,p->false,far::equals));
    }

    @Test void avoidsWastingScaffoldWhenExistingFloorIsAdjacent(){
        BlockPos start=at(0,0),landing=at(1,0);
        assertNull(ScaffoldBridgeRoute.plan(start,
                Set.of(start)::contains,p->false,landing::equals));
    }

    @Test void rejectsRoutesWithoutOwnedStartingAnchor(){
        BlockPos start=at(0,0),landing=at(3,0);
        assertNull(ScaffoldBridgeRoute.plan(start,
                p->false,p->true,landing::equals));
    }
}
