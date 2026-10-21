package com.caszgamermd.golemsplus.worker;

import java.io.InputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the three independently rendered uniforms shipped in the JAR. */
class ProfessionUniformAssetTest {
    @Test void texturesArePackedAndHaveTransparentAtlasSpace() throws Exception {
        int[] colors=new int[3];
        String[] professions={"builder","trader","chef"};
        for(int i=0;i<professions.length;i++){
            String path="/assets/golems_plus/textures/entity/"+
                    professions[i]+"_golem.png";
            try(InputStream stream=getClass().getResourceAsStream(path)){
                assertNotNull(stream,"Missing uniform: "+path);
                BufferedImage image=ImageIO.read(stream);
                assertNotNull(image,"Invalid PNG: "+path);
                assertEquals(256,image.getWidth());
                assertEquals(128,image.getHeight());
                assertEquals(0,(image.getRGB(255,127)>>>24)&255,
                        "Transparent space expected on atlas: "+path);
                colors[i]=image.getRGB(50,46);
            }
        }
        assertNotEquals(colors[0],colors[1],"Builder and Trader jacket colors");
        assertNotEquals(colors[1],colors[2],"Trader and Chef jacket colors");
    }
}
