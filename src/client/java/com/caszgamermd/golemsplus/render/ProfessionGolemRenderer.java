package com.caszgamermd.golemsplus.render;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.trader.TraderGolem;
import com.caszgamermd.golemsplus.cooking.ChefGolem;
import com.caszgamermd.golemsplus.fishing.FisherGolem;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.PathfinderMob;

/**
 * Native small-profession renderer, replacing the earlier Iron Golem
 * placeholder and its mismatched 2.7-block collision/rendering bounds.
 */
public final class ProfessionGolemRenderer<T extends PathfinderMob>
        extends MobRenderer<T,ProfessionGolemRenderState,ProfessionGolemModel> {
    public static final ModelLayerLocation BUILDER_LAYER=
            new ModelLayerLocation(GolemsPlus.id("builder_worker"),"main");
    public static final ModelLayerLocation TRADER_LAYER=
            new ModelLayerLocation(GolemsPlus.id("trader_worker"),"main");
    public static final ModelLayerLocation CHEF_LAYER=
            new ModelLayerLocation(GolemsPlus.id("chef_worker"),"main");
    public static final ModelLayerLocation FISHER_LAYER=
            new ModelLayerLocation(GolemsPlus.id("fisher_worker"),"main");
    private final Identifier uniformTexture;

    public ProfessionGolemRenderer(EntityRendererProvider.Context context,
            ModelLayerLocation layer,ProfessionGolemModel.Profession kind){
        super(context,new ProfessionGolemModel(context.bakeLayer(layer),kind),.35f);
        uniformTexture=GolemsPlus.id("textures/entity/"+
                kind.name().toLowerCase(java.util.Locale.ROOT)+"_golem.png");
    }

    @Override public ProfessionGolemRenderState createRenderState(){
        return new ProfessionGolemRenderState();
    }
    @Override public Identifier getTextureLocation(ProfessionGolemRenderState state){
        return uniformTexture;
    }
    @Override public void extractRenderState(T entity,
            ProfessionGolemRenderState state,float partialTick){
        super.extractRenderState(entity,state,partialTick);
        state.carrying=(entity instanceof BuilderGolem builder&&builder.hasSupplyBox())
                || (entity instanceof TraderGolem trader&&trader.busy());
        state.working=(entity instanceof BuilderGolem
                &&!entity.getNavigation().isDone())
                ||(entity instanceof ChefGolem chef&&chef.preparing());
        state.presenting=entity instanceof TraderGolem trader&&trader.presenting();
        state.hasRod=entity instanceof FisherGolem fisher&&fisher.hasRod();
        state.carryingFish=entity instanceof FisherGolem fisher&&fisher.hasCatch();
        // ChefGolem uses the same shared animation but has no recipe AI yet.
    }
}
