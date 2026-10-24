package com.caszgamermd.golemsplus.worker;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;

/** Server-only one-time personal name assignment for specialist Golems. */
public final class ProfessionGolemNames {
    private ProfessionGolemNames(){}

    public static void ensureNamed(PathfinderMob golem,
                                   ProfessionGolemNamePool.Profession profession){
        // Only the logical server may choose and synchronize the name.
        if(!(golem.level() instanceof ServerLevel))return;
        Component current=golem.getCustomName();
        if(current!=null&&!current.getString().equals(
                ProfessionGolemNamePool.legacyName(profession)))return;
        String chosen=ProfessionGolemNamePool.select(profession,
                golem.getRandom().nextInt(
                        ProfessionGolemNamePool.names(profession).size()));
        golem.setCustomName(Component.literal(chosen));
        golem.setCustomNameVisible(true);
    }
}
