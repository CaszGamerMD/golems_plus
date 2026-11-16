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
    @Test void geometryPrecedesEnvironment(){
        assertArrayEquals(new double[]{1,2,3,4},BuilderNeuralAdvisor.combine(new double[]{1,2},new double[]{3,4}));
    }
}
