package com.caszgamermd.golemsplus.builder;

/** Immutable arithmetic for a reusable multi-stage vertical scaffold route.
 * World collision, inventory and navigation are handled by BuilderScaffolding.
 * Every stage extends the SAME physical tower, never a different column.
 */
public final class ScaffoldVerticalRoute {
    public static final int EXTENSION=16;
    public enum Phase { APPROACH, ASCEND, RETURN, DESCEND, DISMOUNT, DONE }
    private ScaffoldVerticalRoute(){}

    /** Inclusive top block Y for this material-constrained stage.
     * A tall tower is extended in EXTENSION-block batches rather than
     * requiring every scaffolding item for the full build at once.
     */
    public static int stageTop(int baseY,int existingTop,int wantedTop){
        if(wantedTop<baseY)return wantedTop;
        long next=Math.max((long)baseY+EXTENSION-1,
                (long)existingTop+EXTENSION);
        return (int)Math.min(wantedTop,Math.min(Integer.MAX_VALUE,next));
    }
    public static boolean reached(double feetY,double stopY,boolean down){
        return down?feetY<=stopY+.10:feetY>=stopY-.10;
    }
    /** Recognize feet on the bottom scaffold at baseY+1 as still
     * needing a dismount even though no vertical descent remains.
     */
    public static boolean onBottomScaffold(double feetY,int baseY,
                                            double dx,double dz){
        return feetY>=baseY+.65&&feetY<=baseY+1.2
                &&dx*dx+dz*dz<=.32;
    }
    public static boolean needsDescent(double feetY,int baseY){
        return feetY>baseY+1.16;
    }
    public static boolean needsReturn(double dx,double dz){
        return dx*dx+dz*dz>1.35;
    }
}
