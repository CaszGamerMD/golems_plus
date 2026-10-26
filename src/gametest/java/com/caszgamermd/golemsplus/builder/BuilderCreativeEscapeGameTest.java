package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/** A Builder trapped inside a ROOFED room can plan a collision-valid escape
 * through the existing closed ground-floor door without destroying blocks.
 */
public final class BuilderCreativeEscapeGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=160,skyAccess=true)
    public void creativeBuilderFindsExitThroughClosedGroundDoor(
            GameTestHelper helper){
        var level=helper.getLevel();
        // Fully enclosed stone box, split into two compartments by a
        // solid wall. There is precisely one non-solid way to the depot:
        // a two-block-tall wooden door at ground level.
        for(int x=2;x<=10;x++)for(int z=2;z<=10;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++){
                boolean wall=x==2||x==10||z==2||z==10||x==6||y==5;
                helper.setBlock(x,y,z,wall?Blocks.STONE:Blocks.AIR);
            }
        }
        BlockPos door=helper.absolutePos(new BlockPos(6,2,6));
        helper.setBlock(6,2,6,Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING,Direction.EAST)
                .setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN,false));
        helper.setBlock(6,3,6,Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING,Direction.EAST)
                .setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER)
                .setValue(DoorBlock.OPEN,false));
        BlockPos depot=helper.absolutePos(new BlockPos(9,2,6));
        BlockPos inside=helper.absolutePos(new BlockPos(4,2,6));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        builder.setPos(inside.getX()+.5,inside.getY(),inside.getZ()+.5);
        builder.setPersistenceRequired();
        level.addFreshEntity(builder);
        if(!level.getBlockState(door).is(Blocks.OAK_DOOR)
                ||level.getBlockState(door).getValue(DoorBlock.OPEN)){
            helper.fail("Door fixture did not remain CLOSED");return;
        }
        List<Vec3> route=BuilderCreativeEscapeRoute.toDepot(
                level,builder,depot);
        boolean crossesDoor=route.stream().anyMatch(p->
                BlockPos.containing(p).getX()==door.getX()
                &&BlockPos.containing(p).getZ()==door.getZ());
        if(route.isEmpty()||!crossesDoor){
            helper.fail("Trapped Builder failed to plan a genuine door exit: "
                    +route);return;
        }
        if(level.getBlockState(door).getValue(DoorBlock.OPEN)){
            helper.fail("Search must not mutate doorway during planning");return;
        }
        if(!BuilderCreativeEscapeRoute.openDoor(level,door)
                ||!level.getBlockState(door).getValue(DoorBlock.OPEN)){
            helper.fail("Builder could not OPEN existing exit door");return;
        }
        BuilderCreativeEscapeRoute.closeDoor(level,door);
        if(level.getBlockState(door).getValue(DoorBlock.OPEN)
                ||!level.getBlockState(door).is(Blocks.OAK_DOOR)){
            helper.fail("Builder did not restore the unchanged door");return;
        }
        System.out.println("[CREATIVE ESCAPE] PASS roofed room through closed "
                +"door, route="+route.size()+" physical air cells");
        helper.succeed();
    }
}
