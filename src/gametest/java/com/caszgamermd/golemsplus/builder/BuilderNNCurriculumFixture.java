package com.caszgamermd.golemsplus.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Test-only user-supplied curriculum, verified by canonical block coordinates and states. */
final class BuilderNNCurriculumFixture {
    record Course(String id,String file,int blocks,int air,String hash) {}
    static final List<Course> COURSES=List.of(
            // Byte-exact user-uploaded VillagerMatrixClean_V2; hash is the source-file SHA-256.
            new Course("matrix","VillagerMatrixClean_V2.litematic",15376,15004,
                    "77fa9731b11b1b666034a9e155c5a56abc173b9f9a974f963f916d7ef7d11816"),
            new Course("home","homeDemo.litematic",620,20086,
                    "fd9356663144ec1e6f55e1e71bddc9704624d95b4ca8eb9c358c14f1b3bb7ebb"),
            new Course("dragon","Dragon.litematic",1032,9903,
                    "c4b2961ca54efe70c9ab7a95d831ceb3541299ceadbe18ddfcca7cbb97953c83"),
            new Course("froglights","froglights.litematic",3102,4458,
                    "497093590340226a295e90dd2de3bc4d86668ab48cdb516b25252a19db78e574"),
            new Course("farm","farmDemos.litematic",13143,140324,
                    "31a88223773888df19d1ff9dcaa2ee88869f941adde5183599fe6a1ae12fbbbc"),
            new Course("chibi","CaszChibi.litematic",32061,276058,
                    "616846073ae6295a79f64f05c7e4b657ae8c2c5062c83f364ae2721674a4bc86"));
    static Course selected() {
        String name=System.getenv().getOrDefault("NN_COURSE","home");
        if(name.equals("structures"))return new Course("structures","",56,0,"synthetic-v1");
        return COURSES.stream().filter(c->c.id().equals(name)).findFirst().orElseThrow();
    }
    static LitematicFileReader.Schematic read(Course course)throws Exception {
        if(course.id().equals("structures")){
            var blocks=new java.util.ArrayList<LitematicFileReader.Block>();
            // Two 4x4 foundations with low perimeter walls, 16 air
            // columns apart. Interleave by Y to catch global-layer claims.
            for(int y=0;y<2;y++)for(int ox:List.of(0,20))
                for(int x=0;x<4;x++)for(int z=0;z<4;z++)
                    if(y==0||x==0||x==3||z==0||z==3)
                        blocks.add(new LitematicFileReader.Block(ox+x,y,z,"minecraft:stone"));
            if(blocks.size()!=course.blocks())throw new IllegalStateException("Structure fixture count");
            return new LitematicFileReader.Schematic("Two Buildings",List.copyOf(blocks),
                    java.util.Map.of("minecraft:stone",(long)blocks.size()),List.of());
        }
        if(course.id().equals("matrix")){
            // The original source is embedded in verified Base64 segments. It
            // must not be approximated by an unrelated synthetic Matrix.
            Path source=BuilderVillagerMatrixFullFixture.unpack();
            try{
                var s=LitematicFileReader.read(source);
                if(s.blocks().size()!=course.blocks()||s.airBlocks().size()!=course.air()
                        ||!s.name().equals("VillagerMatrixClean_V2"))
                    throw new IllegalStateException("Original Matrix fixture count/name mismatch");
                return s;
            }finally{Files.deleteIfExists(source);}
        }
        Path p=Files.createTempFile("nn-course-",".litematic");
        try(var in=BuilderNNCurriculumFixture.class.getResourceAsStream(
                "/golems_plus_curriculum/"+course.file())){
            if(in==null)throw new IllegalStateException("Missing curriculum file: "+course.file());
            Files.write(p,in.readAllBytes());
            var s=LitematicFileReader.read(p);
            if(s.blocks().size()!=course.blocks()||s.airBlocks().size()!=course.air())
                throw new IllegalStateException("Unexpected fixture counts");
            var digest=MessageDigest.getInstance("SHA-256");
            var sorted=s.blocks().stream().sorted(Comparator
                    .comparingInt(LitematicFileReader.Block::x)
                    .thenComparingInt(LitematicFileReader.Block::y)
                    .thenComparingInt(LitematicFileReader.Block::z)).toList();
            for(var b:sorted)
                digest.update((b.x()+","+b.y()+","+b.z()+","+b.state()+"\n").getBytes(StandardCharsets.UTF_8));
            if(!HexFormat.of().formatHex(digest.digest()).equals(course.hash()))
                throw new IllegalStateException("Fixture coordinate/state hash mismatch");
            return s;
        }finally{Files.deleteIfExists(p);}
    }
    private BuilderNNCurriculumFixture(){}
}
