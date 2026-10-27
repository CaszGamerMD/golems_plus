"""Execute real Java policy selection; fixtures are not physical navigation passes."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from scripts.merge_alt_evolution_state import merge_strategy_memory, STRATEGY_FORMAT

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('java'), 'Java source launcher required')
class ActualJavaStrategySelection(unittest.TestCase):
    def test_outcomes_change_choices_and_restore_without_memorized_routes(self):
        source = (ROOT / 'src/gametest/java/com/caszgamermd/golemsplus/builder/NavigationStrategyMemory.java').read_text()
        source = source.replace('package com.caszgamermd.golemsplus.builder;', '')
        probe = '''
    private static Observation example(String id,long sequence,String context,
                                       Strategy strategy,int arrival){
        return new Observation(id,sequence,context,strategy,arrival,260,0,0,40);
    }
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
    }
    public static void main(String[] args){
        NavigationStrategyMemory model=new NavigationStrategyMemory();
        List<Strategy> options=List.of(Strategy.DIRECT,Strategy.SMOOTH,Strategy.DOOR);
        String context="door=near;slabs=many;turns=many;traffic=reserved";
        for(int i=0;i<3;i++)model.observe(example("baseline"+i,i,context,Strategy.DIRECT,250));
        model.observe(example("smooth0",10,context,Strategy.SMOOTH,180));
        check(model.choose(context,options,true).strategy()==Strategy.DIRECT,
              "A lucky single trip must not become a rule");
        for(int i=1;i<3;i++)model.observe(example("smooth"+i,10+i,context,Strategy.SMOOTH,180));
        Decision learned=model.choose(context,options,true);
        check(learned.strategy()==Strategy.SMOOTH&&learned.reason().equals("repeated-outcomes"),
              "Repeated better physical outcomes must change the strategy");
        check(model.choose("unseen-context",options,true).strategy()==Strategy.DIRECT,
              "Do not invent evidence for a different situation");
        check(model.choose(context,List.of(Strategy.DIRECT),true).strategy()==Strategy.DIRECT,
              "Learning must never select an unavailable route");
        NavigationStrategyMemory restored=new NavigationStrategyMemory();
        for(Observation observation:model.observations())restored.observe(observation);
        check(restored.choose(context,options,true).strategy()==Strategy.SMOOTH,
              "A restarted test must actually USE retained experience");
        Observation repeat=model.observations().get(0);
        int oldSize=restored.observations().size();
        restored.observe(repeat);
        check(restored.observations().size()==oldSize,"Duplicate replay taught twice");
        try{
            restored.observe(example(repeat.id(),repeat.sequence(),context,repeat.strategy(),100));
            throw new AssertionError("Conflicting evidence accepted");
        }catch(IllegalStateException expected){}
        // Adapt when the previously learned strategy repeatedly stops working.
        for(int i=0;i<32;i++)restored.observe(example("failure"+i,100+i,context,Strategy.SMOOTH,-1));
        check(restored.choose(context,options,true).strategy()==Strategy.DIRECT,
              "Old success must not prevent adaptation to repeated failures");
        NavigationStrategyMemory exploration=new NavigationStrategyMemory();
        int probes=0;
        for(int i=0;i<100;i++)if(exploration.choose(context,options,false).reason().equals("exploration"))probes++;
        check(probes==20,"Exploration must be bounded to one in five decisions");
        System.out.println("Repeated outcomes alter rules; restore retains them; failures adapt them");
    }
'''
        source = source.rstrip()[:-1] + probe + '\n}\n'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'NavigationStrategyMemory.java'
            path.write_text(source)
            result = subprocess.run(['java', str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class StrategyEvidenceMerge(unittest.TestCase):
    def memory(self, identifier, ticks):
        return dict(strategyMemory=dict(format=STRATEGY_FORMAT, observations=[dict(
            id=identifier, sequence=10, context='door=near', strategy='DOOR',
            arrivedTicks=ticks, deadlineTicks=260, stationaryTicks=5,
            collisions=0, distanceMeters=40)]))

    def test_merges_independent_workers_without_double_counting_inherited_evidence(self):
        a, b = self.memory('run-1:slot0', 200), self.memory('run-2:slot2', 210)
        merged = merge_strategy_memory(a, b, a)
        self.assertEqual([x['id'] for x in merged['observations']], ['run-1:slot0', 'run-2:slot2'])
        self.assertEqual(merge_strategy_memory({}, a), a['strategyMemory'])

    def test_rejects_conflicting_and_invalid_physical_outcomes(self):
        with self.assertRaises(ValueError):
            merge_strategy_memory(self.memory('same', 200), self.memory('same', 210))
        with self.assertRaises(ValueError):
            merge_strategy_memory(self.memory('late', 300))


if __name__ == '__main__':
    unittest.main()
