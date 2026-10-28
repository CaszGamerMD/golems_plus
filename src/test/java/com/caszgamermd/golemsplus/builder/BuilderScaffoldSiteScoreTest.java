package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderScaffoldSiteScoreTest {
    private static BlockPos at(int x,int y,int z){
        return new BlockPos(x,y,z);
    }
    @Test void neverSelectsTowerOutsideRealPlacementRange(){
        BlockPos work=at(0,69,0);
        assertTrue(BuilderScaffoldSiteScore.withinReach(at(3,64,2),work));
        assertFalse(BuilderScaffoldSiteScore.withinReach(at(4,64,0),work));
        assertFalse(BuilderScaffoldSiteScore.withinReach(at(0,70,0),work));
    }
    @Test void reusesExistingTowerRatherThanClutteringBuild(){
        BlockPos work=at(0,70,0),worker=at(1,64,0);
        double owned=BuilderScaffoldSiteScore.score(
                at(1,64,0),worker,work,0,2,0,true);
        double fresh=BuilderScaffoldSiteScore.score(
                at(1,64,1),worker,work,6,2,0,false);
        assertTrue(owned<fresh);
    }
    @Test void prefersSharedFutureWorkWhenSafeAndComparable(){
        BlockPos target=at(0,70,0),worker=at(0,64,-2);
        BlockPos a=at(1,64,1),b=at(-1,64,1);
        assertTrue(BuilderScaffoldSiteScore.score(a,worker,target,
                        6,9,0,false)
                <BuilderScaffoldSiteScore.score(b,worker,target,
                        6,0,0,false));
    }
    @Test void clashesWithFutureSchematicAlwaysLose(){
        BlockPos target=at(0,70,0),worker=at(1,64,0);
        assertTrue(BuilderScaffoldSiteScore.score(at(2,64,0),worker,
                        target,5,12,1,false)
                >BuilderScaffoldSiteScore.score(at(2,64,1),worker,
                        target,7,0,0,false));
    }
}
