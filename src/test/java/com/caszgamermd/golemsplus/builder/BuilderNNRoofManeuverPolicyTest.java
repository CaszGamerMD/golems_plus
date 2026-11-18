package com.caszgamermd.golemsplus.builder;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physically approved routes only. A neural score never authorizes flight. */
class BuilderNNRoofManeuverPolicyTest {
    private static BuilderMultiLegTravel.Leg leg(int ax,int ay,int az,
            int bx,int by,int bz){
        return new BuilderMultiLegTravel.Leg(new BlockPos(ax,ay,az),
                new BlockPos(bx,by,bz));
    }
    @Test void climbAboveRoofThenDescendIsExplicitLearningInput(){
        BlockPos start=new BlockPos(0,10,0),goal=new BlockPos(5,12,0);
        var route=List.of(leg(0,10,0,1,17,0),leg(2,17,0,4,13,0));
        double[] x=BuilderNNRoofManeuverPolicy.features(start,goal,route,true,2);
        assertEquals(14,x.length);
        assertEquals(1,x[8],1e-12,"above-roof flag");
        assertTrue(x[4]>0,"height above target");
        assertTrue(x[5]==0,"route does not go below target");
        assertTrue(x[10]>0,"multi-leg cost signal");
        assertEquals(1,x[13],1e-12,"verified complete work pose");
    }
    @Test void goingPastXAndZIsLegalFeatureNotAutomaticFailure(){
        var x=BuilderNNRoofManeuverPolicy.features(
                new BlockPos(0,10,0),new BlockPos(5,12,5),
                List.of(leg(0,10,0,7,14,7)),false,1);
        assertEquals(1,x[6],1e-12);
        assertEquals(1,x[7],1e-12);
        assertEquals(0,x[13],1e-12);
    }
    @Test void trainableRouteNetworkCanLearnGoodAndBadExecutedRoutes(){
        var net=BuilderNNRoofManeuverPolicy.Network.initial();
        double[] good=BuilderNNRoofManeuverPolicy.features(
                new BlockPos(0,8,0),new BlockPos(5,10,0),
                List.of(leg(0,8,0,1,15,0),leg(1,15,0,5,11,0)),true,3);
        double[] bad=BuilderNNRoofManeuverPolicy.features(
                new BlockPos(0,8,0),new BlockPos(5,10,0),
                List.of(leg(0,8,0,1,10,0)),false,0);
        double beforeGood=net.predict(good),beforeBad=net.predict(bad);
        for(int i=0;i<100;i++){
            net.learn(good,1,1);
            net.learn(bad,0,2.5);
        }
        assertTrue(net.predict(good)>beforeGood);
        assertTrue(net.predict(bad)<beforeBad);
        assertTrue(net.predict(good)>net.predict(bad));
        var restored=BuilderNNRoofManeuverPolicy.Network.initial();
        assertTrue(restored.restore(net.snapshot(200,100,100,0)));
        assertEquals(net.predict(good),restored.predict(good),1e-13);
    }
    @Test void candidateSelectionCanExploreButAlwaysReturnsExistingIndex(){
        var model=BuilderNNRoofManeuverPolicy.Network.initial();
        double[] a=new double[14],b=new double[14],c=new double[14];
        b[8]=1;c[6]=1;
        var options=List.of(a,b,c);
        assertEquals(1,BuilderNNRoofManeuverPolicy.chooseIndex(model,options,0,0));
        for(int i=0;i<200;i++){
            int selected=BuilderNNRoofManeuverPolicy.chooseIndex(model,options,0,i);
            assertTrue(selected>=0&&selected<options.size());
        }
        assertEquals(0,BuilderNNRoofManeuverPolicy.chooseIndex(model,List.of(a),0,0));
    }
}