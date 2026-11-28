package com.caszgamermd.golemsplus.builder;

import java.util.BitSet;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderProgressBitsTest {
    @Test void handAndMaximumPlanProgressSurvivesSerialization(){
        var bits=new BitSet();
        bits.set(0);bits.set(327_440);bits.set(749_999);
        for(int i=3;i<327_441;i+=7)bits.set(i);
        var parts=BuilderProgressBits.encode(bits);
        assertTrue(parts.size()>1);
        assertTrue(parts.stream().allMatch(p->p.length()<=16_000));
        assertEquals(bits,BuilderProgressBits.decode(parts));
    }
    @Test void readsLegacyAndRejectsTruncatedOrOversizedData(){
        var bits=new BitSet();bits.set(99_999);
        assertEquals(bits,BuilderProgressBits.decodeLegacy(Base64.getEncoder().encodeToString(bits.toByteArray())));
        assertTrue(BuilderProgressBits.decode(List.of("abc","def")).isEmpty());
        assertTrue(BuilderProgressBits.decodeLegacy("!".repeat(130_000)).isEmpty());
        assertTrue(BuilderProgressBits.decode(List.of()).isEmpty());
    }
}
