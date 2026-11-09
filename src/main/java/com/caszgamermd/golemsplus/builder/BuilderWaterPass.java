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
                ||encoded!=null&&encoded.startsWith("minecraft:water[")
                ||isBubbleColumn(encoded);
    }
    /** A non-source water state is a VANILLA fluid simulation result,
     * not an item that a Builder can place. Wait for a nearby real source
     * to propagate; never directly write a flowing water level to world.
     */
    public static boolean isNaturalFlowingWater(String encoded){
        if(encoded==null||!encoded.startsWith("minecraft:water["))return false;
        int at=encoded.indexOf("level=");
        if(at<0)return false;
        at+="level=".length();
        int end=at;
        while(end<encoded.length()
                &&Character.isDigit(encoded.charAt(end)))end++;
        if(end==at)return false;
        try {
            int level=Integer.parseInt(encoded.substring(at,end));
            return level>0&&level<=15;
        }catch(NumberFormatException ignored){
            return false;
        }
    }
    /** Preserve the dry phase, then provide actual source fluids, then
     * accept simulated flowing cells after sources have had time to spread.
     * The Planner still independently verifies each exact world state.
     */
    public static int layerOrder(String encoded){
        return isNaturalFlowingWater(encoded)?2:isWaterStep(encoded)?1:0;
    }
    public static boolean isBubbleColumn(String encoded){
        return "minecraft:bubble_column".equals(encoded)
                ||encoded!=null&&encoded.startsWith("minecraft:bubble_column[");
    }
    public static boolean samePhase(String a,String b){
        return isWaterStep(a)==isWaterStep(b);
    }
}
