package com.caszgamermd.golemsplus.builder;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A Builder may borrow a teammate's real tower and supported bridge,
 * while the original receipts remain owned by the placing Builder. */
final class BuilderSharedScaffoldRouteTest {
    @Test void borrowedReturnTowerMustBeNearAndActuallyElevated(){
        BlockPos depot=new BlockPos(0,2,0);
        BlockPos roof=new BlockPos(6,7,5);
        assertTrue(BuilderScaffolding.borrowableReturnColumn(
                roof,new BlockPos(7,2,6),depot));
        assertFalse(BuilderScaffolding.borrowableReturnColumn(
                roof,new BlockPos(25,2,6),depot));
        assertFalse(BuilderScaffolding.borrowableReturnColumn(
                roof,new BlockPos(7,2,25),depot));
        assertFalse(BuilderScaffolding.borrowableReturnColumn(
                roof,new BlockPos(7,10,6),depot));
        assertFalse(BuilderScaffolding.borrowableReturnColumn(
                new BlockPos(6,3,5),new BlockPos(7,2,6),depot));
        assertFalse(BuilderScaffolding.borrowableReturnColumn(
                roof,new BlockPos(7,2,6),null));
    }

    @Test void protectsEntireBorrowedTowerAndBridge(){
        BlockPos base=new BlockPos(10,2,10);
        List<BlockPos> bridge=List.of(new BlockPos(11,5,10),
                new BlockPos(12,5,10));
        for(int y=2;y<=5;y++)
            assertTrue(BuilderScaffoldPlanA.routeUsesScaffold(
                    base.atY(y),base,5,bridge));
        for(BlockPos p:bridge)
            assertTrue(BuilderScaffoldPlanA.routeUsesScaffold(
                    p,base,5,bridge));
        assertFalse(BuilderScaffoldPlanA.routeUsesScaffold(
                new BlockPos(10,6,10),base,5,bridge));
        assertFalse(BuilderScaffoldPlanA.routeUsesScaffold(
                new BlockPos(13,5,10),base,5,bridge));
        assertFalse(BuilderScaffoldPlanA.routeUsesScaffold(
                new BlockPos(11,4,10),base,5,bridge));
    }
}
