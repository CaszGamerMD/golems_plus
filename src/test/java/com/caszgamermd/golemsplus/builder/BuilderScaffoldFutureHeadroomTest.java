package com.caszgamermd.golemsplus.builder;

import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Future block placements cannot seal a paid tower's physical lip
 * while its Builder still needs the return shaft.
 */
class BuilderScaffoldFutureHeadroomTest {
    @Test void futureSchematicBlocksAboveTowerMakeItUnsafe(){
        BlockPos base=new BlockPos(10,4,30);
        int top=8;
        assertFalse(BuilderScaffoldPlanA.futureHeadroomClear(
                base,top,Set.of(base.atY(9))));
        assertFalse(BuilderScaffoldPlanA.futureHeadroomClear(
                base,top,Set.of(base.atY(10))));
    }
    @Test void normalShaftAndRemoteBlocksRemainEligible(){
        BlockPos base=new BlockPos(10,4,30);
        assertTrue(BuilderScaffoldPlanA.futureHeadroomClear(
                base,8,Set.of(base.atY(8),base.east().atY(9))));
    }
}
