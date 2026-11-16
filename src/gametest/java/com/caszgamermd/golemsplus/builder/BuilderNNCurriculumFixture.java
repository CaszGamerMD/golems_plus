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
        return COURSES.stream().filter(c->c.id().equals(name)).findFirst().orElseThrow();
    }
    static LitematicFileReader.Schematic read(Course course)throws Exception {
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
