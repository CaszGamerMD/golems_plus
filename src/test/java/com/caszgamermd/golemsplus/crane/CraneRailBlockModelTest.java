package com.caszgamermd.golemsplus.crane;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Protect against the unconditional four-way rail models and
 * flipped wall normals reported by players in Fabric 26.2.
 */
final class CraneRailBlockModelTest {
    @Test void everyRailArmRequiresAnActualDirectionalLink(){
        var stream=getClass().getClassLoader().getResourceAsStream(
                "assets/golems_plus/blockstates/crane_rail.json");
        assertNotNull(stream);
        JsonObject model=JsonParser.parseReader(
                new InputStreamReader(stream,StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray parts=model.getAsJsonArray("multipart");
        assertEquals(30,parts.size(),"six mount faces x centre+four arms");
        Map<String,Integer> counts=new HashMap<>();
        Map<String,Integer> expectedRotation=Map.of(
                "face_north",90,"face_south",270);
        Map<String,Integer> expectedYaw=Map.of(
                "face_east",270,"face_west",90);
        for(var part:parts){
            JsonObject obj=part.getAsJsonObject();
            String face,modelName=obj.getAsJsonObject("apply")
                    .get("model").getAsString();
            JsonObject when=obj.getAsJsonObject("when");
            if(modelName.contains("arm_")){
                JsonArray and=when.getAsJsonArray("AND");
                assertNotNull(and,"arm must require mounted face AND real rail link");
                assertEquals(2,and.size());
                face=and.get(0).getAsJsonObject().keySet().iterator().next();
                String link=and.get(1).getAsJsonObject().keySet().iterator().next();
                assertTrue(face.startsWith("face_"));
                assertTrue(link.startsWith("link_"));
            }else{
                assertNull(when.get("AND"),"centre does not need neighboring rails");
                face=when.keySet().iterator().next();
            }
            counts.merge(face,1,Integer::sum);
            JsonObject apply=obj.getAsJsonObject("apply");
            if(expectedRotation.containsKey(face))
                assertEquals(expectedRotation.get(face).intValue(),
                        apply.get("x").getAsInt(),"wrong north/south wall side");
            if(expectedYaw.containsKey(face))
                assertEquals(expectedYaw.get(face).intValue(),
                        apply.get("y").getAsInt(),"wrong east/west wall side");
        }
        for(String name:new String[]{"face_up","face_down","face_north",
                "face_south","face_east","face_west"})
            assertEquals(5,counts.getOrDefault(name,0),"arm count "+name);
    }
}
