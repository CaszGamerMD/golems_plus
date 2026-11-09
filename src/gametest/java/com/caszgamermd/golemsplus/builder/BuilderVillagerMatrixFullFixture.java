package com.caszgamermd.golemsplus.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/** Test-only, byte-exact copy of VillagerMatrixClean_V2.litematic supplied by
 * the user. Resources are text-encoded because the GitHub connector writes
 * UTF-8 files. Production gameplay still reads the regular .litematic. */
final class BuilderVillagerMatrixFullFixture {
    private static final int ENCODED_LENGTH=10544;
    private static final int BINARY_LENGTH=7906;
    private static final String SHA256=
            "77fa9731b11b1b666034a9e155c5a56abc173b9f9a974f963f916d7ef7d11816";

    private BuilderVillagerMatrixFullFixture(){}

    static Path unpack() throws Exception {
        StringBuilder encoded=new StringBuilder(ENCODED_LENGTH);
        for(int i=0;i<4;i++){
            String name="/golems_plus_matrix/segment"+i+".txt";
            try(var source=BuilderVillagerMatrixFullFixture.class
                    .getResourceAsStream(name)){
                if(source==null)throw new IOException("Missing Matrix fixture "+name);
                encoded.append(new String(source.readAllBytes(),
                        StandardCharsets.US_ASCII).trim());
            }
        }
        if(encoded.length()!=ENCODED_LENGTH)
            throw new IOException("Truncated Matrix fixture: "+encoded.length());
        byte[] bytes=Base64.getDecoder().decode(encoded.toString());
        if(bytes.length!=BINARY_LENGTH)
            throw new IOException("Matrix fixture length mismatch "+bytes.length);
        StringBuilder actual=new StringBuilder(64);
        for(byte octet:MessageDigest.getInstance("SHA-256").digest(bytes))
            actual.append(String.format("%02x",octet&255));
        if(!SHA256.equals(actual.toString()))
            throw new IOException("Matrix source SHA-256 mismatch: "+actual);
        Path staged=Files.createTempFile("golems-matrix-full-",".litematic");
        Files.write(staged,bytes);
        return staged;
    }
}
