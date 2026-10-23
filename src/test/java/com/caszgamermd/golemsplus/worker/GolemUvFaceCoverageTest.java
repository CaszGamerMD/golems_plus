package com.caszgamermd.golemsplus.worker;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Check the actual opaque texels used by entity model cube faces, not just
 * whether an atlas exists. A partially transparent UV island produces the
 * "missing pixels" visible when the worker turns or raises its arms.
 */
class GolemUvFaceCoverageTest {
    private static final Pattern CUBE=Pattern.compile(
            "\\.texOffs\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)\\s*\\.addBox\\(\\s*([^)]*)\\)");
    private static final Path CLIENT=Path.of("src/client/java/com/caszgamermd/golemsplus/");
    private static final int MAX_EXAMPLES=30;

    private static void check(BufferedImage img,String source,String profession,List<String> bad) {
        Matcher match=CUBE.matcher(source);
        int count=0;
        while(match.find()){
            String[] args=match.group(3).replace("f","").split(",");
            if(args.length<6)continue;
            try{
                double dx=Math.abs(Double.parseDouble(args[3].trim()));
                double dy=Math.abs(Double.parseDouble(args[4].trim()));
                double dz=Math.abs(Double.parseDouble(args[5].trim()));
                double u=Double.parseDouble(match.group(1));
                double v=Double.parseDouble(match.group(2));
                double[][] rects={
                    {u+dz,v,u+dz+dx,v+dz},
                    {u+dz+dx,v,u+dz+2*dx,v+dz},
                    {u,v+dz,u+dz,v+dz+dy},
                    {u+dz,v+dz,u+dz+dx,v+dz+dy},
                    {u+dz+dx,v+dz,u+2*dz+dx,v+dz+dy},
                    {u+2*dz+dx,v+dz,u+2*(dz+dx),v+dz+dy}
                };
                for(int face=0;face<rects.length;face++){
                    double[] box=rects[face];
                    for(int y=(int)Math.floor(box[1]);y<(int)Math.ceil(box[3]);y++)
                        for(int x=(int)Math.floor(box[0]);x<(int)Math.ceil(box[2]);x++){
                            if(x<0||y<0||x>=img.getWidth()||y>=img.getHeight()
                                    ||((img.getRGB(x,y)>>>24)&255)==0){
                                count++;
                                if(bad.size()<MAX_EXAMPLES)
                                    bad.add(profession+" face "+face+" UV ("+match.group(1)+","
                                            +match.group(2)+") "+x+","+y+" (cube "
                                            +match.group(3).replace("\n"," ").trim()+")");
                            }
                        }
                }
            }catch(NumberFormatException ignored){
                // Non-literal cube parameters are evaluated at runtime.
            }
        }
        if(count>0)bad.add(profession+": "+count+" transparent or off-atlas face pixels");
    }

    @Test void solidUniformCuboidsNeverSampleTransparentTexels() throws Exception {
        String bee=Files.readString(CLIENT.resolve("beekeeper/BeeModel.java"));
        String professions=Files.readString(CLIENT.resolve("render/ProfessionGolemModel.java"));
        String shared=professions.substring(0,professions.indexOf("switch(type){"));
        List<String> bad=new ArrayList<>();
        for(String profession:new String[]{"beekeeper","builder","trader","chef","fisher"}){
            String segment=bee;
            if(!profession.equals("beekeeper")){
                String marker="case "+profession.toUpperCase()+" -> {";
                int begin=professions.indexOf(marker),end=professions.indexOf("\n            case ",begin+marker.length());
                if(begin<0)throw new IllegalStateException("Missing "+marker);
                if(end<0)end=professions.indexOf("\n        }\n        return LayerDefinition",begin);
                segment=shared+professions.substring(begin,end);
            }
            String texture="/assets/golems_plus/textures/entity/"+profession+"_golem.png";
            try(InputStream stream=getClass().getResourceAsStream(texture)){
                assertNotNull(stream,"Missing "+texture);
                BufferedImage img=ImageIO.read(stream);
                assertNotNull(img,"Unrecognized PNG "+texture);
                check(img,segment,profession,bad);
            }
        }
        assertTrue(bad.isEmpty(),"Missing opaque pixels in model UVs:\n"+String.join("\n",bad));
    }
}
