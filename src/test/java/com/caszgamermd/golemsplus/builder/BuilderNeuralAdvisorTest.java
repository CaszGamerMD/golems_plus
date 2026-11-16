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
    @Test void geometryPrecedesEnvironment(){
        assertArrayEquals(new double[]{1,2,3,4},BuilderNeuralAdvisor.combine(new double[]{1,2},new double[]{3,4}));
    }
}
