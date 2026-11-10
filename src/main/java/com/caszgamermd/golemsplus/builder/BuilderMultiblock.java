package com.caszgamermd.golemsplus.builder;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.DoublePlantBlock;

/** A two-cell vanilla object is ONE physical item and ONE build task.
 * The secondary cell has its own exact schematic state, but never owns
 * a work claim, supply request, placement payment or timer.
 *
 * Only true item-backed vanilla double blocks are supported. Piston
 * heads, moving pistons, flowing water and redstone changes remain
 * physics results, not independent free items or synthetic companions.
 */
public final class BuilderMultiblock {
    private BuilderMultiblock(){}

    public record Cell(int x,int y,int z) {}

    static String blockId(String encoded){
        return encoded==null?"":encoded.split("\\[",2)[0];
    }

    private static Map<String,String> properties(String encoded){
        var result=new HashMap<String,String>();
        if(encoded==null)return result;
        int open=encoded.indexOf('[');
        if(open<0||!encoded.endsWith("]"))return result;
        for(String pair:encoded.substring(open+1,encoded.length()-1).split(",")){
            var parts=pair.split("=",2);
            if(parts.length==2)result.put(parts[0],parts[1]);
        }
        return result;
    }

    private static boolean plant(String id){
        Identifier key=Identifier.tryParse(id);
        return key!=null&&BuiltInRegistries.BLOCK.containsKey(key)
                &&BuiltInRegistries.BLOCK.getValue(key) instanceof DoublePlantBlock;
    }

    public static boolean isPrimary(String encoded){
        String id=blockId(encoded);
        Map<String,String> properties=properties(encoded);
        if(id.endsWith("_bed"))return "foot".equals(properties.get("part"));
        if(id.endsWith("_door")&&!id.endsWith("_trapdoor"))
            return "lower".equals(properties.get("half"));
        return plant(id)&&"lower".equals(properties.get("half"));
    }

    public static boolean isSecondary(String encoded){
        String id=blockId(encoded);
        Map<String,String> properties=properties(encoded);
        if(id.endsWith("_bed"))return "head".equals(properties.get("part"));
        if(id.endsWith("_door")&&!id.endsWith("_trapdoor"))
            return "upper".equals(properties.get("half"));
        return plant(id)&&"upper".equals(properties.get("half"));
    }

    /** Schematic-local partner, BEFORE Litematica rotation and mirroring.
     * A bed head sits one block in its facing direction. */
    public static Cell companionCell(LitematicFileReader.Block primary){
        if(primary==null||!isPrimary(primary.state()))return null;
        int x=primary.x(),y=primary.y(),z=primary.z();
        if(blockId(primary.state()).endsWith("_bed")){
            switch(properties(primary.state()).getOrDefault("facing","")){
                case "north" ->z--;
                case "south" ->z++;
                case "west" ->x--;
                case "east" ->x++;
                default -> {return null;}
            }
        }else y++;
        return new Cell(x,y,z);
    }

    public static boolean matchingPair(String primary,String secondary){
        if(!isPrimary(primary)||!isSecondary(secondary)
                ||!blockId(primary).equals(blockId(secondary)))return false;
        var a=properties(primary);
        var b=properties(secondary);
        a.remove("half");b.remove("half");
        a.remove("part");b.remove("part");
        // A door's two halves can have independently live power/open
        // values while temporarily operating; these are not different
        // physical items. Stable orientation and hinges must still agree.
        if(blockId(primary).endsWith("_door")){
            a.remove("powered");b.remove("powered");
            a.remove("open");b.remove("open");
        }
        return a.equals(b);
    }

    /** Raw schematic position keys distinguish adjacent structures and
     * prevent accidentally consuming an unrelated matching block. */
    public static Cell cell(LitematicFileReader.Block block){
        return new Cell(block.x(),block.y(),block.z());
    }

    public static BlockPos position(BuilderConstructionPlan.Companion companion){
        return new BlockPos(companion.x(),companion.y(),companion.z());
    }
}
