"""Execute the trainer's real Java genetics without Minecraft dependencies.

These are selection/persistence checks, never a physical navigation pass.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'src/gametest/java/com/caszgamermd/golemsplus/builder/BuilderEvolutionAltCourseGameTest.java'


def declaration(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


@unittest.skipUnless(shutil.which('java'), 'Java source launcher required')
class ActualJavaSelection(unittest.TestCase):
    def test_three_route_parents_and_six_direct_mutations(self):
        source = SOURCE.read_text()
        genome = declaration(source, 'private record Genome(')
        pin = declaration(source, 'private static List<Genome> pinElites(')
        families = declaration(source, 'private record FamilyPlan(')
        breed = declaration(source, 'private static FamilyPlan breedFamilies(')
        policy = 'enum Strategy { DIRECT, SMOOTH, DOOR }'
        # Compile actual methods against minimal dependency stubs.
        knowledge = declaration(source, 'private record Knowledge(')
        knowledge = knowledge.replace('NavigationStrategyMemory.Strategy', 'Strategy')
        families = families.replace('NavigationStrategyMemory.Strategy', 'Strategy')
        breed = breed.replace('NavigationStrategyMemory.Strategy', 'Strategy')
        program = '''import java.util.*;
public class SelectionProbe {
GENOME
POLICY
KNOWLEDGE
FAMILIES
BREED
PIN
public static void main(String[] args) throws Exception {
    Random rng=new Random(721);
    List<Genome> population=new ArrayList<>();
    for(int i=0;i<9;i++)population.add(Genome.random(rng));
    Genome a=Genome.random(rng), b=Genome.random(rng), c=Genome.random(rng);
    List<Knowledge> parents=List.of(
        new Knowledge(7,a,120,220,40,1,Strategy.DOOR,"WWSS"),
        new Knowledge(3,b,115,230,40,1,Strategy.DIRECT,"WSWW"),
        new Knowledge(8,c,110,245,40,1,Strategy.DOOR,"NNWW"));
    List<Genome> pinned=pinElites(population,parents);
    if(pinned.get(7)!=a||pinned.get(3)!=b||pinned.get(8)!=c)
        throw new AssertionError("Elite genome moved to a different spawn");
    FamilyPlan family=breedFamilies(parents,new Random(1234),9);
    if(family.genes().size()!=9||family.policies().size()!=9)
        throw new AssertionError("Exactly nine real workers required");
    if(family.genes().get(7)!=a||family.genes().get(3)!=b
            ||family.genes().get(8)!=c)
        throw new AssertionError("An unchanged parent was lost");
    for(Knowledge parent:parents){
        int children=0, total=0;
        for(int i=0;i<9;i++)if(family.parentSlots().get(i)==parent.slot()){
            total++;
            if(i==parent.slot()){
                if(family.genes().get(i)!=parent.dna()
                        ||family.policies().get(i)!=parent.policy()
                        ||!family.parentRoutes().get(i).equals(parent.route()))
                    throw new AssertionError("Parent route, policy or DNA mutated");
            }else{
                children++;
                Genome child=family.genes().get(i);
                int differences=0;
                for(var part:Genome.class.getRecordComponents())
                    if(!part.getAccessor().invoke(parent.dna())
                         .equals(part.getAccessor().invoke(child)))differences++;
                if(differences>1)
                    throw new AssertionError("Child not direct single-gene mutation");
                if(!family.parentRoutes().get(i).equals(parent.route()))
                    throw new AssertionError("Family corridor route not inherited");
            }
        }
        if(total!=3||children!=2)
            throw new AssertionError("Each parent needs exactly two clones");
    }
    int changedChildren=0;
    for(int n=0;n<10000;n++){
        Genome parent=Genome.random(rng), child=parent.mutate(rng);
        int differences=0;
        for(var part:Genome.class.getRecordComponents())
            if(!part.getAccessor().invoke(parent).equals(part.getAccessor().invoke(child)))
                differences++;
        if(differences>1)throw new AssertionError("Mutation erased multiple traits");
        if(differences==1)changedChildren++;
    }
    if(changedChildren<9000)throw new AssertionError("Mutation effectively disabled");
    System.out.println("Nine physical golem family genetics: 3 unchanged parents, 2 mutations each");
}
}'''.replace('GENOME', genome).replace('POLICY', policy)
        program = program.replace('KNOWLEDGE', knowledge)
        program = program.replace('FAMILIES', families).replace('BREED', breed)
        program = program.replace('PIN', pin)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'SelectionProbe.java'
            path.write_text(program)
            subprocess.run(['java', str(path)], check=True,
                           capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
