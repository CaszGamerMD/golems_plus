package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;

/** Bounded, NBT-safe persistence for out-of-order completion and ignored cells. */
public final class BuilderProgressBits {
    private static final int PART_LENGTH=16_000;
    private static final int MAX_BITS=750_000;
    private static final int MAX_ENCODED=4*((((MAX_BITS+7)/8)+2)/3);
    public static final int MAX_PARTS=(MAX_ENCODED+PART_LENGTH-1)/PART_LENGTH;
    private BuilderProgressBits(){}
    public static List<String> encode(BitSet bits){
        if(bits.length()>MAX_BITS)throw new IllegalArgumentException("Builder progress exceeds maximum plan");
        String text=Base64.getEncoder().encodeToString(bits.toByteArray());
        var parts=new ArrayList<String>();
        for(int i=0;i<text.length();i+=PART_LENGTH)
            parts.add(text.substring(i,Math.min(i+PART_LENGTH,text.length())));
        return List.copyOf(parts);
    }
    public static BitSet decode(List<String> parts){
        if(parts.size()>MAX_PARTS)return new BitSet();
        var text=new StringBuilder();
        for(int i=0;i<parts.size();i++){
            String part=parts.get(i);
            if(part.isEmpty()||part.length()>PART_LENGTH
                    ||(i<parts.size()-1&&part.length()!=PART_LENGTH))return new BitSet();
            text.append(part);
        }
        return decodeLegacy(text.toString());
    }
    public static BitSet decodeLegacy(String text){
        if(text.length()>MAX_ENCODED)return new BitSet();
        try{
            var bits=BitSet.valueOf(Base64.getDecoder().decode(text));
            return bits.length()<=MAX_BITS?bits:new BitSet();
        }catch(IllegalArgumentException e){return new BitSet();}
    }
}
