package com.caszgamermd.golemsplus.builder;

/** Internal, second construction action at an already-built waterloggable cell.
 * These markers are never written into the source schematic. */
public final class BuilderWaterPass {
    private static final String PREFIX="golems_plus:waterlog|";
    public static final String WATER_BUCKET="golems_plus:infinite_water_bucket";
    private BuilderWaterPass(){}

    public static boolean isWaterlogged(String encoded){
        return encoded!=null&&encoded.matches(
                ".*(?:\\[|,)waterlogged=true(?:,|\\]).*");
    }
    public static String dryState(String wet){
        return isWaterlogged(wet)?wet.replace("waterlogged=true","waterlogged=false"):wet;
    }
    public static String waterlog(String wet){return PREFIX+wet;}
    public static boolean isWaterlogPass(String encoded){
        return encoded!=null&&encoded.startsWith(PREFIX);
    }
    public static String originalState(String encoded){
        return isWaterlogPass(encoded)?encoded.substring(PREFIX.length()):encoded;
    }
    public static boolean isWaterStep(String encoded){
        return isWaterlogPass(encoded)||"minecraft:water".equals(encoded)
                ||encoded!=null&&encoded.startsWith("minecraft:water[");
    }
    public static boolean samePhase(String a,String b){
        return isWaterStep(a)==isWaterStep(b);
    }
}
