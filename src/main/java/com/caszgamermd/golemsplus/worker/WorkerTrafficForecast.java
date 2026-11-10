package com.caszgamermd.golemsplus.worker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Cooperative route-intent forecast for terrain A*. This is deliberately a
 * SOFT, finite-horizon reservation cost, not an unbreakable tile lock:
 * single-wide passages must remain routable, even when one worker is idle.
 *
 * Publication uses an already executable path; it never grants permission
 * to move, and a predicted tile never substitutes for world collision,
 * hitbox, footing, or fall-protection checks. Per-world boards avoid
 * interference between the Overworld, Nether, End or GameTest dimensions.
 *
 * A fuller WHCA* would model time in search nodes. This small first layer
 * discourages foreseeable conflicting routes without changing the proven
 * physics/terrain A* graph or increasing its branching factor.
 */
public final class WorkerTrafficForecast {
    private static final int HORIZON=6;
    private static final int TTL_TICKS=20;
    private static final Map<ServerLevel,Board> BOARDS=new WeakHashMap<>();
    private WorkerTrafficForecast(){}

    static final class Board {
        private record Intent(BlockPos feet,List<BlockPos> ahead,long expires){}
        private final Map<UUID,Intent> intents=new HashMap<>();

        void publish(UUID id,BlockPos feet,List<BlockPos> route,long tick){
            if(id==null||feet==null||route==null||route.isEmpty()){
                if(id!=null)intents.remove(id);
                return;
            }
            var upcoming=new ArrayList<BlockPos>(HORIZON);
            BlockPos previous=feet;
            for(BlockPos step:route){
                if(step==null||step.equals(previous))continue;
                upcoming.add(step.immutable());
                previous=step;
                if(upcoming.size()==HORIZON)break;
            }
            if(upcoming.isEmpty())intents.remove(id);
            else intents.put(id,new Intent(feet.immutable(),
                    List.copyOf(upcoming),tick+TTL_TICKS));
        }
        void release(UUID id){intents.remove(id);}
        Map<BlockPos,Double> snapshot(UUID requester,long now){
            Map<BlockPos,Double> costs=new LinkedHashMap<>();
            Iterator<Map.Entry<UUID,Intent>> iter=intents.entrySet().iterator();
            while(iter.hasNext()){
                var entry=iter.next();
                Intent route=entry.getValue();
                if(now>=route.expires()){iter.remove();continue;}
                if(entry.getKey().equals(requester))continue;
                // A temporary occupied tile is already reflected by
                // local crowd cost. Upcoming spots add predictive risk,
                // including opposing workers' next passage cells.
                for(int i=0;i<route.ahead().size();i++){
                    BlockPos next=route.ahead().get(i);
                    double price=i==0?8.0:Math.max(.8,6.0-i*1.0);
                    costs.merge(next,price,(a,b)->Math.min(16.0,a+b));
                }
            }
            return Map.copyOf(costs);
        }
        int active(long now){
            snapshot(null,now);
            return intents.size();
        }
    }
    private static Board board(ServerLevel level){
        return BOARDS.computeIfAbsent(level,unused->new Board());
    }
    static void publish(ServerLevel level,UUID worker,BlockPos feet,
            List<BlockPos> upcoming,long tick){
        board(level).publish(worker,feet,upcoming,tick);
    }
    static Map<BlockPos,Double> snapshot(ServerLevel level,UUID worker,long tick){
        return board(level).snapshot(worker,tick);
    }
    static void release(ServerLevel level,UUID worker){
        Board board=BOARDS.get(level);
        if(board!=null)board.release(worker);
    }
}
