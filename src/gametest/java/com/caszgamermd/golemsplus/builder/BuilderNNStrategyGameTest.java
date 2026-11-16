package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Compare original full schematics in the actual production zone layout.
 * No simulated placement is labeled a physical result. */
public final class BuilderNNStrategyGameTest {
    @GameTest(maxTicks=100,skyAccess=true)
    public void compareZoneOrders(GameTestHelper h){
        try{
            var json=new Gson();
            for(var course:BuilderNNCurriculumFixture.COURSES){
                var source=BuilderNNCurriculumFixture.read(course);
                var plan=BuilderConstructionPlan.create(source,
                        new BuildSite(0,64,0,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
                var steps=plan.steps();
                var layout=BuilderZones.footprint(steps);
                var zones=new ArrayList<List<Integer>>();
                for(int z=0;z<4;z++)zones.add(new ArrayList<>());
                for(int i=0;i<steps.size();i++){
                    var s=steps.get(i);
                    zones.get(layout.zone(s.x(),s.z(),4)).add(i);
                }
                int counted=0;
                for(int z=0;z<4;z++){
                    var indices=zones.get(z);counted+=indices.size();
                    for(var axis:BuilderStrategyPreview.Axis.values()){
                        var result=BuilderStrategyPreview.analyze(steps,indices,axis,32);
                        if(result.steps()!=indices.size()
                                ||result.current().indices().stream().anyMatch(result.next().indices()::contains))
                            throw new IllegalStateException("Invalid task preview");
                        System.out.println("[NN STRATEGY_PREVIEW] "+json.toJson(new Row(course.id(),z,result)));
                    }
                }
                if(counted!=steps.size())throw new IllegalStateException("Missing source tasks");
            }
            System.out.println("[NN STRATEGY PASS] five courses, four zones, three axes");
            h.succeed();
        }catch(Exception e){h.fail("Strategy survey failed: "+e);}
    }
    private record Row(String course,int zone,BuilderStrategyPreview.Candidate result){}
}
