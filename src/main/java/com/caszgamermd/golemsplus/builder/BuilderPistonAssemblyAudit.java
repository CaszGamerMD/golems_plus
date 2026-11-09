package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only audit of a Litematica piston snapshot. A real piston head
 * appears one cell IN FRONT of an extended piston, in the piston facing
 * direction. A head behind the base is not an executable Minecraft
 * extension, even if its palette facing/type happen to match.
 *
 * This does not rotate, repair, discard, or place any schematic block.
 * Report positions in the imported schematic's ORIGINAL local frame.
 */
public final class BuilderPistonAssemblyAudit {
    public record Position(int x,int y,int z) {
        Position move(int dx,int dy,int dz) {
            return new Position(x+dx,y+dy,z+dz);
        }
    }
    public record Report(int extended,int validForward,int reversed,
                         int unmatched,int totalHeads,List<String> examples) {
        public Report {
            examples=List.copyOf(examples);
        }
        public boolean physicallyConsistent() {
            return reversed==0&&unmatched==0&&validForward==totalHeads;
        }
    }
    private BuilderPistonAssemblyAudit(){}

    public static Report inspect(LitematicFileReader.Schematic schematic) {
        Map<Position,String> states=new HashMap<>();
        for(var block:schematic.blocks())
            states.put(new Position(block.x(),block.y(),block.z()),
                    block.state());
        int total=0,good=0,backwards=0,missing=0,heads=0;
        List<String> examples=new ArrayList<>();
        for(var entry:states.entrySet()){
            String state=entry.getValue();
            if(state.startsWith("minecraft:piston_head["))heads++;
            boolean sticky=state.startsWith("minecraft:sticky_piston[");
            if(!sticky&&!state.startsWith("minecraft:piston["))continue;
            if(!"true".equals(property(state,"extended")))continue;
            total++;
            String facing=property(state,"facing");
            int[] direction=offset(facing);
            if(direction==null){
                missing++;
                if(examples.size()<16)
                    examples.add("unknown piston facing at "+entry.getKey()
                            +" state="+state);
                continue;
            }
            Position at=entry.getKey();
            Position forward=at.move(direction[0],direction[1],direction[2]);
            Position reverse=at.move(-direction[0],-direction[1],-direction[2]);
            String ahead=states.get(forward);
            String behind=states.get(reverse);
            if(headMatches(ahead,facing,sticky)){
                good++;
            }else if(headMatches(behind,facing,sticky)){
                backwards++;
                if(examples.size()<16)
                    examples.add("reverse head: base="+at+" facing="+facing
                            +" head="+reverse+" forward="+forward
                            +" forwardCell="+ahead);
            }else{
                missing++;
                if(examples.size()<16)
                    examples.add("unmatched head: base="+at+" facing="+facing
                            +" forward="+forward+" forwardCell="+ahead
                            +" reverseCell="+behind);
            }
        }
        return new Report(total,good,backwards,missing,heads,examples);
    }

    private static boolean headMatches(String state,String facing,boolean sticky) {
        return state!=null&&state.startsWith("minecraft:piston_head[")
                &&facing.equals(property(state,"facing"))
                &&(sticky?"sticky":"normal").equals(property(state,"type"));
    }
    private static String property(String state,String name) {
        if(state==null)return "";
        int start=state.indexOf("["+name+"=");
        if(start>=0)start+=name.length()+2;
        else{
            start=state.indexOf(","+name+"=");
            if(start<0)return "";
            start+=name.length()+2;
        }
        int end=start;
        while(end<state.length()&&state.charAt(end)!=','
                &&state.charAt(end)!=']')end++;
        return state.substring(start,end);
    }
    private static int[] offset(String face) {
        return switch(face){
            case "up"->new int[]{0,1,0};
            case "down"->new int[]{0,-1,0};
            case "north"->new int[]{0,0,-1};
            case "south"->new int[]{0,0,1};
            case "west"->new int[]{-1,0,0};
            case "east"->new int[]{1,0,0};
            default->null;
        };
    }
}
