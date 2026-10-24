package com.caszgamermd.golemsplus.worker;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfessionGolemNamePoolTest {
    @Test void exactlyThirtyDistinctNamesPerProfession(){
        for(var profession:ProfessionGolemNamePool.Profession.values()){
            var names=ProfessionGolemNamePool.names(profession);
            assertEquals(30,names.size(),"Expected 30 names for "+profession);
            assertEquals(30,new HashSet<>(names).size(),
                    "Repeated name in "+profession);
            for(String name:names){
                assertFalse(name.isBlank(),profession+" has an empty name");
                assertEquals(name,name.trim(),profession+" has stray spaces");
            }
            for(int index=0;index<30;index++)
                assertEquals(names.get(index),
                        ProfessionGolemNamePool.select(profession,index));
            assertThrows(UnsupportedOperationException.class,
                    ()->names.set(0,"Changed"));
        }
    }
    @Test void allFiveProfessionPoolsArePresent(){
        assertEquals(5,ProfessionGolemNamePool.Profession.values().length);
        for(var p:ProfessionGolemNamePool.Profession.values())
            assertNotNull(ProfessionGolemNamePool.legacyName(p));
    }
}
