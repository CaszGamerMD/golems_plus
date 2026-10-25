package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** One Builder buys a real shared tower. All four Builders recognize it
 * as a common physical access route; no duplicate tracking or free items.
 */
public final class BuilderSharedTowerGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=250,skyAccess=true)
    public void fourBuildersShareOneTrackedTower(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=5;x<=16;x++)for(int z=5;z<=16;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(6,2,6,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(6,2,6));
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            helper.fail("Shared tower Planner absent");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),4)){
            helper.fail("Four-builder Planner setup failed");return;
        }
        BuilderGolem[] crew=new BuilderGolem[4];
        for(int i=0;i<4;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos spawn=helper.absolutePos(new BlockPos(8+i,2,9));
            worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
            worker.assign(planner,owner.getUUID());
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){
                helper.fail("Builder "+i+" was not registered");return;
            }
            crew[i]=worker;
        }
        BlockPos tower=helper.absolutePos(new BlockPos(8,2,10));
        for(int y=tower.getY();y<=tower.getY()+3;y++){
            BlockPos cell=new BlockPos(tower.getX(),y,tower.getZ());
            if(!level.setBlock(cell,Blocks.SCAFFOLDING.defaultBlockState(),3)
                    ||!table.trackScaffold(crew[0].getUUID(),cell)){
                helper.fail("Owned tower setup failed");return;
            }
        }
        if(table.sharedScaffoldPositions().size()!=4){
            helper.fail("Crew-wide tower graph should have four real blocks");return;
        }
        for(int i=1;i<4;i++){
            if(!table.scaffoldPositions(crew[i].getUUID()).isEmpty()){
                helper.fail("Shared scaffold was duplicated into worker "+i);return;
            }
            // All followers should consider the real scaffold tower when
            // asked to approach an upper placement within its work radius.
            BlockPos target=tower.offset(2,5,0);
            var result=BuilderScaffolding.reach(level,crew[i],table,owner,
                    target,List.of(new BuilderConstructionPlan.Step(
                            target.getX(),target.getY(),target.getZ(),
                            "minecraft:stone","minecraft:stone")));
            if(result==BuilderScaffolding.Result.NO_SAFE_TOWER){
                helper.fail("Builder "+i+" refused a colleague's access tower");
                return;
            }
        }
        if(table.sharedScaffoldPositions().size()!=4
                ||table.scaffoldPositions(crew[0].getUUID()).size()!=4){
            helper.fail("Shared route changed ownership or fabricated scaffold");
            return;
        }
        helper.succeed();
    }
}
