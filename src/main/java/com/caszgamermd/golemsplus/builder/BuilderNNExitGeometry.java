package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Prospective collision only: never places a block or fakes real footing.
 * The original one-cube estimate hid safe exits beside wall posts, stairs
 * and bottom slabs. Future collision must instead match Minecraft's shape.
 * Existing world collision is checked independently on each motion sample. */
public final class BuilderNNExitGeometry {
    private BuilderNNExitGeometry(){}
    static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.shapeExit",
                System.getenv().getOrDefault("GOLEMS_NN_SHAPE_EXIT","false")));
    }
    public static List<AABB> futureCollision(BlockGetter world,BlockPos target,BlockState state){
        if(state==null)return List.of(new AABB(target).inflate(.10,0,.10));
        var boxes=new ArrayList<AABB>();
        for(var local:state.getCollisionShape(world,target).toAabbs()){
            // Slight clearance for numerical/center error. Never approximate
            // a wall as air or allow movement through its actual post.
            boxes.add(local.move(target.getX(),target.getY(),target.getZ())
                    .inflate(.04,0,.04));
        }
        return List.copyOf(boxes);
    }
    public static boolean intersects(AABB body,List<AABB> future){
        for(var part:future)if(body.intersects(part))return true;
        return false;
    }
}
