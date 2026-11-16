package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderNeuralAdvisorTest {
    @Test void recommendationRequiresGainAndBoundedDistance(){
        assertEquals(0,BuilderNeuralAdvisor.recommend(new double[]{.8,.85},new double[]{4,4}));
        assertEquals(0,BuilderNeuralAdvisor.recommend(new double[]{.5,.99},new double[]{4,8}));
        assertEquals(2,BuilderNeuralAdvisor.recommend(new double[]{.5,.9,.8},new double[]{4,8,4}));
        assertEquals(0,BuilderNeuralAdvisor.recommend(new double[]{.5},new double[]{4}));
    }
    @Test void forwardMatchesTanhSigmoid(){
        double[][] w=new double[8][24];
        double[] b=new double[8],v=new double[8],x=new double[24];
        w[0][0]=2; x[0]=.5; v[0]=3;
        var m=new BuilderNeuralAdvisor.Model(java.util.List.of(),.5,w,b,v,-.2);
        assertEquals(1/(1+Math.exp(-(3*Math.tanh(1)-.2))),m.score(x),1e-12);
        assertThrows(IllegalArgumentException.class,()->m.score(new double[7]));
    }
    @Test void invariantModelRotatesGeometryAndCollisionProbes(){
        double[][] w=new double[8][24];
        double[] b=new double[8],v=new double[8],x=new double[24];
        for(int i=0;i<24;i++){w[0][i]=(i+1)*.1;x[i]=i/24.;}
        v[0]=2;
        var m=new BuilderNeuralAdvisor.Model(java.util.List.of(),.5,w,b,v,-.2,"c4_y");
        double expected=m.score(x);
        double[] rotated=x;
        for(int i=0;i<4;i++){
            assertEquals(expected,m.score(rotated),1e-14);
            rotated=BuilderNeuralAdvisor.rotate(rotated);
        }
        assertArrayEquals(x,rotated);
        double[] once=BuilderNeuralAdvisor.rotate(x);
        assertEquals(-x[2],once[0]);assertEquals(x[0],once[2]);
        assertEquals(x[12],once[7]);assertEquals(x[11],once[8]);
        assertEquals(x[18],once[13]);assertEquals(x[17],once[14]);
        x[0]=Double.NaN;
        assertThrows(IllegalArgumentException.class,()->m.score(x));
    }
    @Test void geometryPrecedesEnvironment(){
        assertArrayEquals(new double[]{1,2,3,4},BuilderNeuralAdvisor.combine(new double[]{1,2},new double[]{3,4}));
    }
}
