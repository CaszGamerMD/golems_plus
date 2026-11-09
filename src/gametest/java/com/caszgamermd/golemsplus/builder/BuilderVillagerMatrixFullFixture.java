package com.caszgamermd.golemsplus.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/** Unmodified November 8 VillagerMatrixClean_V2.litematic from the user.
 * Split into text fragments because the repository connector writes UTF-8
 * source files. SHA-256 protects the entire ORIGINAL 7,906-byte schematic.
 * This fixture is in gametest resources ONLY, never in the playable JAR.
 */
final class BuilderVillagerMatrixFullFixture {
    static final int ORIGINAL_BLOCKS=15376;
    static final int ORIGINAL_AIR=15004;
    static final String SHA256=
            "77fa9731b11b1b666034a9e155c5a56abc173b9f9a974f963f916d7ef7d11816";
    private BuilderVillagerMatrixFullFixture(){}
    static Path unpack() throws Exception {
        StringBuilder b64=new StringBuilder(10544);
        for(int i=0;i<5;i++){
            String name="/golems_plus_matrix/segment"+i+".txt";
            try(var input=BuilderVillagerMatrixFullFixture.class.getResourceAsStream(name)){
                if(input==null)throw new IOException("Missing full Matrix segment "+name);
                b64.append(new String(input.readAllBytes(),StandardCharsets.UTF_8).trim());
            }
        }
        if(b64.length()!=10544)
            throw new IOException("Full Matrix base64 truncated: "+b64.length());
        byte[] original=Base64.getDecoder().decode(b64.toString());
        if(original.length!=7906)
            throw new IOException("Full Matrix changed size: "+original.length);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(original);
        StringBuilder hex=new StringBuilder();
        for(byte value:digest)hex.append(String.format("%02x",value&255));
        if(!SHA256.equals(hex.toString()))
            throw new IOException("Full Matrix SHA-256 mismatch: "+hex);
        Path file=Files.createTempFile("matrix-clean-v2-", ".litematic");
        Files.write(file,original);
        return file;
    }
}
