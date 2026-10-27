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
    def test_elites_keep_their_slots_and_mutations_keep_eleven_traits(self):
        source = SOURCE.read_text()
        genome = declaration(source, 'private record Genome(')
        pin = declaration(source, 'private static List<Genome> pinElites(')
        # Compile the actual implementations, not a Python reimplementation.
        program = '''import java.util.*;
public class SelectionProbe {
GENOME
private record Knowledge(int slot,Genome dna,double fitness,
                         int arrivedTick,double progress,int generation){}
PIN
public static void main(String[] args) throws Exception {
    Random rng=new Random(721);
    List<Genome> population=new ArrayList<>();
    for(int i=0;i<10;i++)population.add(Genome.random(rng));
    Genome a=Genome.random(rng), b=Genome.random(rng), c=Genome.random(rng);
    // Deliberately use non-leading slots in fitness order, exposing the bug.
    List<Knowledge> pool=List.of(new Knowledge(7,a,120,240,40,1),
        new Knowledge(3,b,115,245,40,1),new Knowledge(9,c,110,250,40,1));
    List<Genome> result=pinElites(population,pool);
    if(result.get(7)!=a||result.get(3)!=b||result.get(9)!=c)
        throw new AssertionError("Elite moved to a different spawn");
    for(int i:new int[]{0,1,2,4,5,6,8})
        if(result.get(i)!=population.get(i))
            throw new AssertionError("Child overwritten in unrelated lane");
    if(population.get(7)==a)throw new AssertionError("Input mutated");
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
    System.out.println("Real Java selection: pinned elites; 10000 local mutations checked");
}
}'''.replace('GENOME', genome).replace('PIN', pin)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'SelectionProbe.java'
            path.write_text(program)
            subprocess.run(['java', str(path)], check=True,
                           capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
