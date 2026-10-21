package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.GolemsPlus;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

/** Small worker with a purpose-built beekeeper suit UV texture. */
public final class BeeRenderer
        extends MobRenderer<BeekeeperGolem,BeeRenderState,BeeModel> {
    public static final ModelLayerLocation LAYER=
            new ModelLayerLocation(GolemsPlus.id("beekeeper_golem"),"main");
    private static final Identifier TEXTURE=
            GolemsPlus.id("textures/entity/beekeeper_golem.png");
    public BeeRenderer(EntityRendererProvider.Context ctx){
        super(ctx,new BeeModel(ctx.bakeLayer(LAYER)),.35f);
    }
    @Override public void extractRenderState(BeekeeperGolem entity,
            BeeRenderState render,float partialTick){
        super.extractRenderState(entity,render,partialTick);
        render.carrying=entity.job()!=0;
    }
    @Override public BeeRenderState createRenderState(){return new BeeRenderState();}
    @Override public Identifier getTextureLocation(BeeRenderState state){return TEXTURE;}
}
