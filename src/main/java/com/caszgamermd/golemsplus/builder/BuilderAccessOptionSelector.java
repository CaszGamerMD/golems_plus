package com.caszgamermd.golemsplus.builder;

import java.util.EnumMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Movement tools are competing offers, not compulsory stages or proof of
 * arrival. This selector knows nothing about how to walk, rappel or build:
 * the corresponding physical controller must still validate every action.
 *
 * A backwards/sideways detour is progress if the feet actually moved. An
 * unproductive strategy cools down so another available tool can be tried.
 * No failure of one tool permanently rejects the construction target.
 */
public final class BuilderAccessOptionSelector {
    public enum Tool { WALK, GRAPPLE, SCAFFOLD }
    public record Offer(Tool tool,int cost,boolean feasible){}

    static final int STALL_TICKS=100;
    static final int RETRY_TICKS=180;
    private final EnumMap<Tool,Long> retryAt=new EnumMap<>(Tool.class);
    private final EnumMap<Tool,Integer> failures=new EnumMap<>(Tool.class);
    private BlockPos target;
    private Vec3 lastFeet;
    private long lastMovement;
    private Tool active;

    public Tool choose(BlockPos nextTarget,Vec3 feet,long now,
            List<Offer> offers){
        if(nextTarget==null||feet==null)return null;
        if(!nextTarget.equals(target)){
            target=nextTarget.immutable();
            retryAt.clear();
            failures.clear();
            active=null;
            lastFeet=feet;
            lastMovement=now;
        }
        // Moving away from the block can be the correct first leg of a
        // route. Track actual displacement instead of goal-distance loss.
        if(lastFeet==null||feet.distanceToSqr(lastFeet)>=.04){
            lastFeet=feet;
            lastMovement=now;
        }
        if(active!=null&&now-lastMovement>=STALL_TICKS)
            reject(active,now);
        if(active!=null)
            for(Offer offer:offers)
                if(offer.tool()==active&&offer.feasible())return active;
        active=null;
        Offer best=null;
        double score=Double.POSITIVE_INFINITY;
        for(Offer offer:offers){
            if(offer==null||offer.tool()==null||!offer.feasible()
                    ||now<retryAt.getOrDefault(offer.tool(),0L))continue;
            double cost=Math.max(0,offer.cost())
                    +90.0*failures.getOrDefault(offer.tool(),0);
            if(cost<score){score=cost;best=offer;}
        }
        if(best==null)return null;
        active=best.tool();
        lastFeet=feet;
        lastMovement=now;
        return active;
    }

    public void reject(Tool tool,long now){
        if(tool==null)return;
        retryAt.put(tool,now+RETRY_TICKS);
        failures.merge(tool,1,Integer::sum);
        if(active==tool)active=null;
    }

    public Tool active(){return active;}
    public String status(){
        return "target="+target+" tool="+active+" retries="+retryAt
                +" failed="+failures;
    }
}
