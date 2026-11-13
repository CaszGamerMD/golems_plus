package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Keep the long physical assessment in its own test server: short flight,
 * rail, and movement fixtures must not build into its empty 64x72 site. */
public final class BuilderVillagerMatrixAssessmentGameTest {
    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:matrix_arena",
            maxTicks=1_000_000_000,skyAccess=true,required=false)
    public void buildsOriginalMatrixWithFourRealBuilders(GameTestHelper helper){
        new BuilderVillagerMatrixFullGameTest().constructsMatrixUntilFourBuildersStall(helper);
    }
}
