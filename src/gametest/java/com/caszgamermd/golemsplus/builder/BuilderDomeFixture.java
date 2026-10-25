package com.caszgamermd.golemsplus.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

/** Exact user's Dome.litematic, split into resources only because GitHub's
 * test-branch file adapter accepts UTF-8 text, not direct file uploads.
 * These nine fragments are checked against the ORIGINAL 6488-byte
 * schematic before the game's production parser sees them.
 * The gameplay mod never packages these test-only resources.
 */
final class BuilderDomeFixture {
    private static final String SHA256=
            "5a3f8b8f5dba9766838b79f238bfa0802aea3cca4ca03f19a8c567408ebeaa54";
    private BuilderDomeFixture(){}
    static Path unpack() throws Exception {
        StringBuilder b64=new StringBuilder(8652);
        for(int i=0;i<9;i++){
            String path="/golems_plus_dome/segment"+i+".txt";
            try(var in=BuilderDomeFixture.class.getResourceAsStream(path)){
                if(in==null)throw new IOException("Missing test resource "+path);
                b64.append(new String(in.readAllBytes(),StandardCharsets.UTF_8).trim());
            }
        }
        if(b64.length()!=8652)
            throw new IOException("Incomplete Dome fixture "+b64.length()+" base64 chars");
        byte[] decoded=Base64.getDecoder().decode(b64.toString());
        if(decoded.length!=6488)
            throw new IOException("Incorrect Dome file length "+decoded.length);
        StringBuilder hash=new StringBuilder();
        for(byte byteValue:MessageDigest.getInstance("SHA-256").digest(decoded))
            hash.append(String.format("%02x",byteValue&255));
        if(!SHA256.equals(hash.toString()))
            throw new IOException("Dome fixture checksum mismatch "+hash);
        Path tmp=Files.createTempFile("golems-plus-dome-",".litematic");
        Files.write(tmp,decoded);
        return tmp;
    }
}
