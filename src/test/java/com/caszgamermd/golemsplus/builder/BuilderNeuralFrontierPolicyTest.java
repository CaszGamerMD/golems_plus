package com.caszgamermd.golemsplus.builder;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Learned task priority has no authority to widen safe build eligibility. */
class BuilderNeuralFrontierPolicyTest {
    private static BuilderNeuralFrontierPolicy.Candidate candidate(
            int index,int stock,int tier,double cost){
        var pos=new BlockPos(index,10,0);
        return new BuilderNeuralFrontierPolicy.Candidate(
                new BuilderConstructionPlanner.Choice(index,pos,false,true,3),
                pos,stock,tier,cost,new double[20]);
    }
    @Test void cannotChangeStockPriorityStructuralTierOrUnboundedTravel(){
        var a=candidate(1,0,0,10);
        assertTrue(BuilderNeuralFrontierPolicy.eligible(a,candidate(2,0,0,74)));
        assertFalse(BuilderNeuralFrontierPolicy.eligible(a,candidate(3,0,0,74.01)));
        assertFalse(BuilderNeuralFrontierPolicy.eligible(a,candidate(4,1,0,11)));
        assertFalse(BuilderNeuralFrontierPolicy.eligible(a,candidate(5,0,1,11)));
        assertTrue(BuilderNeuralFrontierPolicy.eligible(a,a));
        assertFalse(BuilderNeuralFrontierPolicy.eligible(a,null));
    }
    @Test void networkActuallyLearnsFromPositiveAndNegativePhysicalOutcome(){
        var net=BuilderNeuralFrontierPolicy.Network.initial();
        double[] a={.3,.2,.1,.8,.1,1,1,0,0,0,.1,.1,.75,.25,.0,.0,.0,.0,.0,.0};
        double[] b={.8,.6,.5,0,.8,1,0,1,0,0,.8,.6,.0,.8,.5,.5,.5,.8,1,.0};
        double initialA=net.score(a),initialB=net.score(b);
        for(int i=0;i<100;i++){
            net.learn(a,1.0);
            net.learn(b,0.0);
        }
        assertTrue(net.score(a)>initialA,"successful context should become more favored");
        assertTrue(net.score(b)<initialB,"explicit failed context should be penalized");
        assertTrue(net.score(a)>net.score(b),"network must distinguish outcomes");
        var copy=BuilderNeuralFrontierPolicy.Network.initial();
        assertTrue(copy.restore(net.snapshot(200,100,100)));
        assertEquals(net.score(a),copy.score(a),1e-13);
        assertEquals(net.score(b),copy.score(b),1e-13);
    }
    @Test void oldDomeCheckpointMigratesWithoutLosingPreviousKnowledge(){
        var old=BuilderNeuralFrontierPolicy.Network.initial();
        var snapshot=old.snapshot(1680,1677,3);
        double[][] legacy=new double[8][12];
        for(int j=0;j<8;j++)System.arraycopy(snapshot.w()[j],0,legacy[j],0,12);
        var earlier=new BuilderNeuralFrontierPolicy.Saved(1,1680,1677,3,
                legacy,snapshot.b(),snapshot.v(),snapshot.c());
        var restored=BuilderNeuralFrontierPolicy.Network.initial();
        assertTrue(restored.restore(earlier));
        double[] feature=new double[20];
        feature[0]=.5;feature[1]=.25;feature[11]=.3;
        assertEquals(old.score(feature),restored.score(feature),1e-13);
        double legacyPrediction=restored.score(feature);
        feature[14]=1;
        assertEquals(legacyPrediction,restored.score(feature),1e-13,
                "Migrated terrain inputs start at zero weight, without erasing training");
    }
    @Test void correlatedBadTerrainCanBeLearned(){
        var net=BuilderNeuralFrontierPolicy.Network.initial();
        double[] good=new double[20],bad=new double[20];
        good[12]=1;good[15]=.1;
        bad[12]=0;bad[15]=1;bad[18]=1;
        for(int i=0;i<120;i++){
            net.learn(good,1);
            net.learnWeighted(bad,0,4);
        }
        assertTrue(net.score(good)>net.score(bad)+.2,
                "Physical failure learning should discriminate narrow obstructed terrain");
    }
    @Test void corruptedCheckpointIsRejected(){
        var net=BuilderNeuralFrontierPolicy.Network.initial();
        var good=net.snapshot(0,0,0);
        assertFalse(net.restore(new BuilderNeuralFrontierPolicy.Saved(
                1,0,0,0,new double[2][12],good.b(),good.v(),0)));
    }
}
