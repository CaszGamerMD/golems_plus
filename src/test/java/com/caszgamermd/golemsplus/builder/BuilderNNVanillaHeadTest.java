package com.caszgamermd.golemsplus.builder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for original Matrix source's two ordinary paid head items. */
final class BuilderNNVanillaHeadTest {
    @BeforeAll static void bootstrapMinecraftRegistries(){
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void playerAndZombieHeadsArePaidAndSourceComparable(){
        assertHead("minecraft:player_head[powered=false,rotation=4]", Items.PLAYER_HEAD);
        assertHead("minecraft:zombie_head[powered=false,rotation=4]", Items.ZOMBIE_HEAD);
    }

    private static void assertHead(String source,net.minecraft.world.item.Item item){
        var physical=BuilderBlockResolver.resolve(
                source,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var expected=BuilderBlockResolver.expectedState(
                source,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        assertNotNull(physical,"The head should be a real item-backed placement");
        assertNotNull(expected,"The head should be included in exact-world scoring");
        assertSame(item,physical.item());
        assertEquals(expected,physical.state());
    }

    @Test void unrelatedBlockEntitiesAreNotAllowedAsImplicitFreeItems(){
        assertNull(BuilderBlockResolver.resolve(
                "minecraft:beacon",BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
    }
}
