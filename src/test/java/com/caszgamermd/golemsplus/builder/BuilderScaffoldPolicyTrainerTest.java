package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderScaffoldPolicyTrainerTest {
    private static BuilderScaffoldBridgePlanner.Weights w(double i){
        return new BuilderScaffoldBridgePlanner.Weights(i,1,.3);
    }
    @Test void keepsTopThreeElitesAndMakesTwoClonesEach(){
        var trials=new ArrayList<BuilderScaffoldPolicyTrainer.Trial>();
        for(int i=1;i<=9;i++){
            boolean passed=i==2||i==5||i==8;
            trials.add(new BuilderScaffoldPolicyTrainer.Trial(w(i),
                    passed,passed,3,0,0,passed?100-i:10));
        }
        var next=BuilderScaffoldPolicyTrainer.nextGeneration(trials,12L);
        assertEquals(9,next.size());
        assertEquals(w(8),next.get(0));
        assertEquals(w(5),next.get(3));
        assertEquals(w(2),next.get(6));
        assertEquals(next,
                BuilderScaffoldPolicyTrainer.nextGeneration(trials,12L));
        assertNotEquals(next.get(1),next.get(0));
        assertNotEquals(next.get(2),next.get(0));
    }
    @Test void neverRewardsFastUnsafeCourseOverSlowerSafeCourse(){
        var trials=new ArrayList<BuilderScaffoldPolicyTrainer.Trial>();
        trials.add(new BuilderScaffoldPolicyTrainer.Trial(w(1),true,true,4,0,0,420));
        for(int i=2;i<=9;i++)
            trials.add(new BuilderScaffoldPolicyTrainer.Trial(w(i),true,
                    true,2,0,1,3));
        var next=BuilderScaffoldPolicyTrainer.nextGeneration(trials,0);
        assertEquals(w(1),next.get(0));
    }
    @Test void emptyHandedCourseFinishCannotBeCountedAsPassing(){
        var fake=new BuilderScaffoldPolicyTrainer.CourseTrial(w(1),
                5,3,0,0,5,true,true,0,50);
        assertFalse(fake.passed());
        var unreturned=new BuilderScaffoldPolicyTrainer.CourseTrial(w(1),
                5,3,2,5,5,false,true,0,50);
        assertFalse(unreturned.passed());
        var success=new BuilderScaffoldPolicyTrainer.CourseTrial(w(1),
                5,3,3,5,5,true,true,0,420);
        assertTrue(success.passed());
        var duped=new BuilderScaffoldPolicyTrainer.CourseTrial(w(1),
                5,3,4,6,5,true,true,0,90);
        assertFalse(duped.passed());
    }
    @Test void singleBuilderCourseEvolutionPreservesSafeElite(){
        var results=new ArrayList<BuilderScaffoldPolicyTrainer.CourseTrial>();
        for(int i=1;i<=9;i++){
            boolean passed=i==4||i==8||i==9;
            results.add(new BuilderScaffoldPolicyTrainer.CourseTrial(w(i),
                    5,3,passed?3:0,passed?5:0,5,
                    true,true,0,200-i));
        }
        var ranked=BuilderScaffoldPolicyTrainer.nextGenerationFromCourse(
                results,4321L);
        assertEquals(9,ranked.size());
        assertEquals(w(9),ranked.get(0));
        assertEquals(w(8),ranked.get(3));
        assertEquals(w(4),ranked.get(6));
    }

    @Test void weightsMayChangeButSafetyNeverChanges(){
        for(int i=0;i<200;i++){
            var trials=new ArrayList<BuilderScaffoldPolicyTrainer.Trial>();
            for(int j=0;j<9;j++)
                trials.add(new BuilderScaffoldPolicyTrainer.Trial(
                        new BuilderScaffoldBridgePlanner.Weights(5,1,.3),
                        j==0,j==0,3,2,j==0?0:2,90));
            var next=BuilderScaffoldPolicyTrainer.nextGeneration(trials,i);
            assertEquals(9,next.size());
            for(var policy:next){
                assertTrue(policy.itemCost()>=0);
                assertTrue(policy.turnCost()>=0);
                assertTrue(policy.endGapCost()>=0);
                assertFalse(BuilderScaffoldBridgePlanner.supportedSpan(7));
            }
        }
        assertThrows(IllegalArgumentException.class,
                ()->new BuilderScaffoldBridgePlanner.Weights(-1,1,.2));
    }
}
