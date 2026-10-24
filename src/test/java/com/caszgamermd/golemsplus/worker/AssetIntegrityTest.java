package com.caszgamermd.golemsplus.worker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * In-pack model, texture and UV smoke checks. They protect against the
 * missing-purple asset bugs that ordinary Gradle compilation cannot see.
 */
class AssetIntegrityTest {
    private static final String ROOT="/assets/golems_plus/";
    private static final String[] BLOCKS={
        "builder_table","beekeeper_station","beekeeper_toolbox",
        "cooking_station","cooking_kiosk_top","fishing_spot",
        "fishing_station","meal_counter","trader_counter"
    };
    private static final String[] MODELS={
        "block/builder_table","block/beekeeper_station",
        "block/beekeeper_toolbox","block/cooking_station",
        "block/cooking_kiosk_top","block/fishing_spot_buoy",
        "block/fishing_spot_shore","block/fishing_station",
        "block/fishing_station_wink","block/meal_counter",
        "block/trader_counter","item/builder_worker",
        "item/beekeeper_worker","item/chef_worker",
        "item/fisher_worker","item/trader_worker",
        "item/fishing_bait","item/fishing_spot_marker"
    };
    private static final String[] ITEMS={
        "builder_table","beekeeper_station","beekeeper_toolbox",
        "cooking_station","fishing_station","meal_counter",
        "trader_counter","builder_worker","beekeeper_worker",
        "chef_worker","fisher_worker","trader_worker",
        "fishing_bait","fishing_spot_marker"
    };

    private static InputStream asset(String name) {
        return AssetIntegrityTest.class.getResourceAsStream(ROOT+name);
    }
    private static JsonObject json(String name) throws Exception {
        try(InputStream input=asset(name)){
            assertNotNull(input,"Resource missing: "+name);
            return JsonParser.parseReader(new InputStreamReader(
                    input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static String path(String id){
        return id.startsWith("golems_plus:")?id.substring("golems_plus:".length()):id;
    }

    @Test void everyBlockstateAndItemModelResolves() throws Exception {
        Set<String> known=new HashSet<>();
        for(String name:MODELS)known.add(name);
        for(String name:BLOCKS){
            JsonObject variants=json("blockstates/"+name+".json")
                    .getAsJsonObject("variants");
            assertNotNull(variants,name+" missing blockstate variants");
            for(var entry:variants.entrySet()){
                JsonElement data=entry.getValue();
                JsonObject option=data.isJsonArray()?data.getAsJsonArray().get(0)
                        .getAsJsonObject():data.getAsJsonObject();
                String ref=option.get("model").getAsString();
                if(ref.startsWith("golems_plus:"))
                    assertTrue(known.contains(path(ref)),
                            "Unresolved blockstate model "+name+": "+ref);
            }
        }
        for(String name:ITEMS){
            JsonObject selector=json("items/"+name+".json")
                    .getAsJsonObject("model");
            assertNotNull(selector,"Item definition missing model: "+name);
            String ref=selector.get("model").getAsString();
            if(ref.startsWith("golems_plus:"))
                assertTrue(known.contains(path(ref)),
                        "Unresolved item model "+name+": "+ref);
        }
    }

    @Test void localTexturesExistAndModelFacesReferenceDeclaredKeys() throws Exception {
        for(String model:MODELS){
            JsonObject data=json("models/"+model+".json");
            JsonObject textures=data.getAsJsonObject("textures");
            if(textures==null)continue;
            for(var entry:textures.entrySet()){
                String texture=entry.getValue().getAsString();
                assertNotEquals("minecraft:block/leather",texture,
                        "Nonexistent vanilla texture: "+model);
                if(texture.startsWith("golems_plus:")){
                    String resource="textures/"+path(texture)+".png";
                    try(InputStream in=asset(resource)){
                        assertNotNull(in,"Missing local PNG "+resource+" in "+model);
                        assertNotNull(ImageIO.read(in),"Invalid PNG "+resource);
                    }
                }
            }
            JsonArray elements=data.getAsJsonArray("elements");
            if(elements==null)continue;
            for(JsonElement e:elements){
                JsonObject faces=e.getAsJsonObject().getAsJsonObject("faces");
                if(faces==null)continue;
                for(var face:faces.entrySet()){
                    JsonObject props=face.getValue().getAsJsonObject();
                    if(props.has("texture")){
                        String ref=props.get("texture").getAsString();
                        if(ref.startsWith("#"))
                            assertTrue(textures.has(ref.substring(1)),
                                    "Unknown face texture "+ref+" in "+model);
                    }
                    if(props.has("uv")){
                        JsonArray uv=props.getAsJsonArray("uv");
                        for(JsonElement coordinate:uv){
                            double value=coordinate.getAsDouble();
                            assertTrue(value>=0&&value<=16,
                                    "UV outside 0..16 in "+model);
                        }
                    }
                }
            }
        }
    }

    @Test void everyProfessionTextureMatchesItsRendererAtlas() throws Exception {
        for(String type:new String[]{"builder","trader","chef","fisher","beekeeper"}){
            try(InputStream in=asset("textures/entity/"+type+"_golem.png")){
                assertNotNull(in,"Missing golem atlas "+type);
                BufferedImage image=ImageIO.read(in);
                assertNotNull(image,"Unrecognized PNG "+type);
                assertEquals(256,image.getWidth(),"Texture width "+type);
                assertEquals(128,image.getHeight(),"Texture height "+type);
                // Fish atlas reserves its corner for patterned tackle.
                // The three original uniform tests and new Beekeeper atlas
                // reserve transparent corners, but transparency is not a
                // general requirement of a correctly-sized Minecraft atlas.
                if(!type.equals("fisher"))
                    assertEquals(0,(image.getRGB(255,127)>>>24)&255,
                            "Unused atlas corner should be clear: "+type);
            }
        }
        try(InputStream in=asset("textures/item/fishing_bait.png")){
            assertNotNull(in);
            BufferedImage frames=ImageIO.read(in);
            assertEquals(32,frames.getWidth());
            assertEquals(256,frames.getHeight());
        }
        assertNotNull(asset("textures/item/fishing_bait.png.mcmeta"));
    }
    /** Same-direction, exactly coplanar outer faces shimmer as the camera
     * moves. Opposite-facing joining faces are intentional and excluded.
     * Rotated boxes need world-space geometry and are checked in-game.
     */
    @Test void blockModelsHaveNoCoplanarDepthFightingFaces() throws Exception {
        String[] models={
            "block/builder_table","block/beekeeper_station","block/cooking_station",
            "block/cooking_kiosk_top","block/fishing_station","block/fishing_station_wink",
            "block/meal_counter","block/trader_counter","block/fishing_spot_buoy",
            "block/fishing_spot_shore","item/beekeeper_worker","item/builder_worker",
            "item/chef_worker","item/fisher_worker","item/trader_worker"
        };
        String[] sides={"north","south","east","west","up","down"};
        int[] axis={2,2,0,0,1,1};
        boolean[] high={false,true,true,false,true,false};
        int[] across1={0,0,1,1,0,0};
        int[] across2={1,1,2,2,2,2};
        for(String model:models){
            JsonArray elements=json("models/"+model+".json")
                    .getAsJsonArray("elements");
            for(int i=0;i<elements.size();i++){
                JsonObject a=elements.get(i).getAsJsonObject();
                if(a.has("rotation")||!a.has("faces"))continue;
                double[] af=new double[3],at=new double[3];
                for(int k=0;k<3;k++){
                    af[k]=a.getAsJsonArray("from").get(k).getAsDouble();
                    at[k]=a.getAsJsonArray("to").get(k).getAsDouble();
                }
                for(int j=i+1;j<elements.size();j++){
                    JsonObject b=elements.get(j).getAsJsonObject();
                    if(b.has("rotation")||!b.has("faces"))continue;
                    double[] bf=new double[3],bt=new double[3];
                    for(int k=0;k<3;k++){
                        bf[k]=b.getAsJsonArray("from").get(k).getAsDouble();
                        bt[k]=b.getAsJsonArray("to").get(k).getAsDouble();
                    }
                    JsonObject facesA=a.getAsJsonObject("faces");
                    JsonObject facesB=b.getAsJsonObject("faces");
                    for(int f=0;f<sides.length;f++){
                        if(!facesA.has(sides[f])||!facesB.has(sides[f]))continue;
                        int k=axis[f],u=across1[f],v=across2[f];
                        double pa=high[f]?at[k]:af[k];
                        double pb=high[f]?bt[k]:bf[k];
                        if(Math.abs(pa-pb)>.0001)continue;
                        double du=Math.max(0,Math.min(at[u],bt[u])-Math.max(af[u],bf[u]));
                        double dv=Math.max(0,Math.min(at[v],bt[v])-Math.max(af[v],bf[v]));
                        assertTrue(du*dv<=.07,
                                "Z-fighting same-plane "+sides[f]+" faces "
                                +i+" and "+j+" in "+model);
                    }
                }
            }
        }
    }

    @Test void twelveChairColorVariantsArePackaged() throws Exception {
        for(String color:new String[]{"red","green","blue","purple",
                "orange","white","black","grey","aqua","gold","pink","brown"}){
            String path="textures/entity/fisher_chair_"+color+".png";
            try(InputStream in=asset(path)){
                assertNotNull(in,"Fisher chair color not packed: "+color);
                BufferedImage atlas=ImageIO.read(in);
                assertNotNull(atlas,"Invalid Fisher chair PNG: "+color);
                assertEquals(256,atlas.getWidth());
                assertEquals(128,atlas.getHeight());
            }
        }
    }

}
