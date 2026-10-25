package com.caszgamermd.golemsplus.builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small forward-looking batches avoid reserving an entire large structure. */
public final class BuilderMaterialDemand {
    private BuilderMaterialDemand(){}
    /** Reserve for the next three vertical Y-levels, bottom-up.
     * Request all needed stock for the selected material types, capped at
     * the warehouse's 4096-item per-line contract. The old 64-item cap
     * forced a fresh one-stack shulker on every trip and kept delivery
     * golems crossing the warehouse for tiny repeated batches. Staged
     * OnSite and worker pouch stock is deducted before placing an order.
     */
    /** Warehouse manifest is calculated for the whole unfinished job,
     * NOT just the next placement or three layers. Prioritize lines with
     * real pickable rack stock, while keeping zero-stock shortages visible.
     * One persistent warehouse request can thus pack shulkers continuously
     * while Builders work independently.
     */
    public static Map<String,Integer> outstanding(
            List<BuilderConstructionPlan.Step> steps,int cursor,
            Map<String,Integer> onsite,Map<String,Integer> warehouseStock){
        if(cursor<0||cursor>=steps.size())return Map.of();
        Map<String,Integer> needed=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size();i++){
            String id=steps.get(i).requiredItem();
            if(id!=null&&!id.isBlank())needed.merge(id,1,Integer::sum);
        }
        needed.replaceAll((id,count)->Math.min(4096,
                Math.max(0,count-Math.max(0,onsite.getOrDefault(id,0)))));
        needed.values().removeIf(v->v<=0);
        var types=new java.util.ArrayList<>(needed.entrySet());
        // The Shulker Station manifest currently supports 16 material
        // types per order. Pick in-stock items first, but never use the
        // warehouse snapshot to *erase* shortages from the request.
        types.sort((a,b)->{
            boolean left=warehouseStock.getOrDefault(a.getKey(),0)>0;
            boolean right=warehouseStock.getOrDefault(b.getKey(),0)>0;
            if(left!=right)return left?-1:1;
            return Integer.compare(b.getValue(),a.getValue());
        });
        Map<String,Integer> manifest=new LinkedHashMap<>();
        for(var e:types){
            if(manifest.size()>=16)break;
            manifest.put(e.getKey(),e.getValue());
        }
        return manifest;
    }

    /** Outstanding stock against the actual job ledger. Unlike the
     * global cursor, this excludes cells finished out-of-order in other
     * X/Z zones and cannot request their materials twice.
     */
    public static Map<String,Integer> outstandingRemaining(
            Map<String,Long> remaining,Map<String,Integer> onsite,
            Map<String,Integer> warehouseStock){
        Map<String,Integer> needed=new LinkedHashMap<>();
        for(var row:remaining.entrySet()){
            if(row.getValue()==null||row.getValue()<=0)continue;
            long shortage=row.getValue()-
                    Math.max(0,onsite.getOrDefault(row.getKey(),0));
            if(shortage>0)
                needed.put(row.getKey(),(int)Math.min(4096,shortage));
        }
        var ranked=new java.util.ArrayList<>(needed.entrySet());
        ranked.sort((a,b)->{
            boolean aStock=warehouseStock.getOrDefault(a.getKey(),0)>0;
            boolean bStock=warehouseStock.getOrDefault(b.getKey(),0)>0;
            if(aStock!=bStock)return aStock?-1:1;
            return Integer.compare(b.getValue(),a.getValue());
        });
        Map<String,Integer> manifest=new LinkedHashMap<>();
        for(var entry:ranked){
            if(manifest.size()>=16)break;
            manifest.put(entry.getKey(),entry.getValue());
        }
        return manifest;
    }

    public static Map<String,Integer> batch(List<BuilderConstructionPlan.Step> steps,
            int cursor,Map<String,Integer> stocked){
        if(cursor<0||cursor>=steps.size())return Map.of();
        int firstY=steps.get(cursor).y();
        Map<String,Integer> needed=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size();i++){
            var step=steps.get(i);
            if(step.y()>=firstY+3)break;
            String item=step.requiredItem();
            if(item==null||item.isBlank())continue;
            if(!needed.containsKey(item)&&needed.size()>=16)continue;
            needed.merge(item,1,Integer::sum);
        }
        Map<String,Integer> entireJob=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size();i++){
            String id=steps.get(i).requiredItem();
            if(needed.containsKey(id))
                entireJob.merge(id,1,Integer::sum);
        }
        needed.replaceAll((k,v)->{
            int shortage=Math.max(0,entireJob.getOrDefault(k,0)
                    -Math.max(0,stocked.getOrDefault(k,0)));
            return shortage==0?0:Math.min(4096,shortage);
        });
        needed.values().removeIf(v->v<=0);
        return Map.copyOf(needed);
    }
}
