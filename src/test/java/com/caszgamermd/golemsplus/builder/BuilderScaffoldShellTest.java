package com.caszgamermd.golemsplus.builder;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Protect the two-block empty clearance around future Dome solids. */
final class BuilderScaffoldShellTest {
    private static Set<BlockPos> modelWithHigherOverhang(){
        Set<BlockPos> model=new HashSet<>();
        for(int x=10;x<=16;x++)for(int z=10;z<=14;z++)
            model.add(new BlockPos(x,64,z));
        // At higher Y the rim protrudes beyond the ground-level edge.
        model.add(new BlockPos(18,72,12));
        return model;
    }

    @Test void cardinalTowersStandOutsideTwoEmptyAirColumns(){
        var blueprint=BuilderScaffoldShell.fromSolids(modelWithHigherOverhang());
        Map<String,BlockPos> bases=blueprint.towers().stream()
                .collect(Collectors.toMap(BuilderScaffoldShell.TowerAnchor::side,
                        BuilderScaffoldShell.TowerAnchor::base));
        assertEquals(4,bases.size());
        assertEquals(21,bases.get("X+").getX(),
                "X+ foundation uses the future elevated overhang, not only base floor");
        assertEquals(7,bases.get("X-").getX());
        assertEquals(17,bases.get("Z+").getZ());
        assertEquals(7,bases.get("Z-").getZ());
        for(BlockPos base:bases.values()){
            assertEquals(64,base.getY());
            assertTrue(blueprint.towerHasTwoBlockGap(base));
            for(BlockPos structure:blueprint.solids()){
                int dx=Math.abs(base.getX()-structure.getX());
                int dz=Math.abs(base.getZ()-structure.getZ());
                assertTrue(Math.max(dx,dz)>=3,
                        "Foundation at "+base+" breaches two-column gap near "
                                +structure);
            }
        }
    }

    @Test void forbidsLocalTowersRightAgainstWallsOrOverhangs(){
        var blueprint=BuilderScaffoldShell.fromSolids(modelWithHigherOverhang());
        for(int dx=0;dx<=2;dx++)
            assertFalse(blueprint.towerHasTwoBlockGap(
                    new BlockPos(18+dx,64,12)),
                    "No fallback pillar in a reserved bridge gap");
        assertTrue(blueprint.towerHasTwoBlockGap(new BlockPos(21,64,12)));
        // The empty footprint check must also work against Y+ roof cells.
        assertFalse(blueprint.towerHasTwoBlockGap(new BlockPos(18,64,12)));
    }

    @Test void existingShellIncludesThirdOuterLaneForBridging(){
        Set<BlockPos> square=new HashSet<>();
        for(int x=0;x<=4;x++)for(int z=0;z<=4;z++)
            square.add(new BlockPos(x,70,z));
        var blueprint=BuilderScaffoldShell.fromSolids(square);
        assertTrue(blueprint.isShell(new BlockPos(7,69,2)),
                "The three-wide virtual outer shell must include the new tower lane");
        assertTrue(blueprint.isShell(new BlockPos(5,69,2)),
                "Bridge lane one must remain available");
        assertTrue(blueprint.isShell(new BlockPos(6,69,2)),
                "Bridge lane two must remain available");
        assertEquals(7,blueprint.towers().stream()
                .filter(t->t.side().equals("X+")).findFirst().orElseThrow()
                .base().getX());
    }
}
