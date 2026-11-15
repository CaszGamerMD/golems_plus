package com.caszgamermd.golemsplus.gametest.mixin;

import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only in the test mod; never shipped in the playable jar. */
@Mixin(WorldOptions.class)
public abstract class MatrixWorldSeedMixin {
    @Inject(method="testWorldWithRandomSeed",at=@At("RETURN"),cancellable=true,require=0)
    private static void fixedMatrixSeed(CallbackInfoReturnable<WorldOptions> cir){
        if("controlled".equals(System.getenv("MATRIX_TEST_MODE")))
            cir.setReturnValue(new WorldOptions(260215376L,false,false));
    }
}
