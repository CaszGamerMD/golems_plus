"""Execute actual Java matching/rule logic. Unit fixtures are not Minecraft passes."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from scripts.merge_alt_evolution_state import merge_local_rule_memory, RULE_FORMAT

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which('java'), 'Java source launcher required')
class ActualJavaLocalRules(unittest.TestCase):
    def test_matched_evidence_creates_restores_and_protects_context_rules(self):
        base = ROOT / 'src/gametest/java/com/caszgamermd/golemsplus/builder'
        source = (base / 'NavigationRuleMemory.java').read_text().replace(
            'package com.caszgamermd.golemsplus.builder;', '')
        leases = '\n'.join(line for line in (base / 'NavigationRightOfWay.java').read_text().splitlines()
                           if not line.startswith(('package ', 'import '))).replace(
                               'public final class NavigationRightOfWay', 'final class NavigationRightOfWay')
        probe = '''
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);
    }
    private static Trial example(String block,int repeat,boolean challenger,
            int finished,boolean clear,String fingerprint){
        Episode episode=new Episode("obstacle",0,"obstacle=step;traffic=crossing",
                challenger?Rule.STABLE_MERGE:Rule.BASELINE,clear,
                challenger?20:45,0,0);
        return new Trial(block+":"+repeat+":"+challenger,repeat*2+(challenger?1:0),
                block,repeat,challenger,fingerprint,finished,challenger?220:230,List.of(episode));
    }
    public static void main(String[] args){
        String context="obstacle=step;traffic=crossing";
        NavigationRuleMemory model=new NavigationRuleMemory();
        model.observe(example("good",0,false,8,true,"cohort"));
        model.observe(example("good",0,true,9,true,"cohort"));
        check(model.bindings().isEmpty(),"One lucky pair must not create a rule");
        for(int i=1;i<3;i++){
            model.observe(example("good",i,false,8,true,"cohort"));
            model.observe(example("good",i,true,9,true,"cohort"));
        }
        check(model.bindings().get(context).action()==Rule.STABLE_MERGE,
              "Three matched improvements must create a context/action rule");
        NavigationRuleMemory restored=new NavigationRuleMemory();
        for(Trial t:model.trials()){restored.observe(t);restored.observe(t);}
        check(restored.trials().size()==6&&restored.bindings().equals(model.bindings()),
              "Restore/merge must preserve decisions without duplicate votes");
        for(int i=0;i<3;i++){
            restored.observe(example("bad",i,false,9,true,"cohort"));
            restored.observe(example("bad",i,true,8,false,"cohort"));
        }
        check(restored.bindings().get(context).evidenceBlock().equals("good"),
              "A later failed experiment must not erase a proven rule");
        for(int block=0;block<10;block++)for(int i=0;i<3;i++){
            restored.observe(example("later"+block,i,false,9,true,"cohort"));
            restored.observe(example("later"+block,i,true,8,false,"cohort"));
        }
        NavigationRuleMemory compact=new NavigationRuleMemory();
        for(Trial t:restored.checkpointTrials())compact.observe(t);
        check(compact.bindings().equals(restored.bindings())&&compact.trials().size()<=18,
              "Compaction must retain every proven rule and bound working evidence");
        NavigationRuleMemory mismatch=new NavigationRuleMemory();
        NavigationRuleMemory worseCrew=new NavigationRuleMemory();
        for(int i=0;i<3;i++){
            mismatch.observe(example("mixed",i,false,8,true,"A"));
            mismatch.observe(example("mixed",i,true,9,true,"B"));
            worseCrew.observe(example("regression",i,false,9,true,"same"));
            worseCrew.observe(example("regression",i,true,8,true,"same"));
        }
        check(mismatch.bindings().isEmpty(),"Different cohorts/routes cannot prove a rule");
        check(worseCrew.bindings().isEmpty(),"Local improvement cannot excuse crew regression");
        NavigationRuleMemory tail=new NavigationRuleMemory();
        for(int i=0;i<3;i++){
            tail.observe(example("tail",i,false,8,true,"same"));
            tail.observe(example("tail",i,true,i==0?7:10,true,"same"));
        }
        check(tail.bindings().isEmpty(),"A higher median cannot hide a worse minimum");
        NavigationRightOfWay lease=new NavigationRightOfWay();
        check(lease.owner(0,10,1,10.1,0)==0,"Initial priority");
        for(int tick=1;tick<20;tick++)check(lease.owner(0,10,1,9,tick)==0,
              "Changing remaining distance must not flip a live lease");
        check(lease.owner(0,10,1,9,20)==1,"Stalled owner must release right-of-way");
        check(lease.owner(1,9,0,10,20)==1,"Peer call order cannot flip ownership");
        System.out.println("Matched rules persist; regressions rejected; right-of-way bounded and stable");
    }
'''
        source = source.rstrip()[:-1] + probe + '\n}\n' + leases
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'NavigationRuleMemory.java'
            path.write_text(source)
            result = subprocess.run(['java', str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


class LocalRuleMerge(unittest.TestCase):
    def memory(self, identifier='trial', elapsed=20):
        return dict(localRuleMemory=dict(format=RULE_FORMAT, trials=[dict(
            id=identifier, sequence=20, block='run:block0', repeat=0,
            challenger=True, fingerprint='frozen-cohort', finished=8, meanArrivalTicks=230,
            episodes=[dict(id='episode', slot=0, context='obstacle=step;traffic=crossing',
                           action='STABLE_MERGE', cleared=True, elapsedTicks=elapsed,
                           stationaryTicks=0, collisions=0)])]))

    def test_retains_both_workers_and_deduplicates_inherited_trials(self):
        a, b = self.memory('first'), self.memory('second')
        result = merge_local_rule_memory(a, b, a)
        self.assertEqual([t['id'] for t in result['trials']], ['first', 'second'])
        self.assertEqual(merge_local_rule_memory({}, a), a['localRuleMemory'])

    def test_rejects_conflicting_and_invalid_local_outcomes(self):
        with self.assertRaises(ValueError):
            merge_local_rule_memory(self.memory(), self.memory(elapsed=25))
        with self.assertRaises(ValueError):
            merge_local_rule_memory(self.memory(elapsed=61))

    def test_checkpoint_compaction_retains_proof_blocks_and_recent_work(self):
        source = self.memory()
        trials = []
        import copy
        for block in range(10):
            for repeat in range(3):
                for challenger in (False, True):
                    trial = copy.deepcopy(source['localRuleMemory']['trials'][0])
                    trial.update(id=f'{block}-{repeat}-{challenger}', block=f'block{block}',
                                 sequence=block*6+repeat*2+challenger, repeat=repeat,
                                 challenger=challenger)
                    trials.append(trial)
        source['localRuleMemory']['trials'] = trials
        source['localRuleMemory']['bindings'] = [dict(context='obstacle=step;traffic=crossing',
                action='STABLE_MERGE', evidenceBlock='block0', matchedPairs=3)]
        result = merge_local_rule_memory(source)
        self.assertEqual({t['block'] for t in result['trials']}, {'block0', 'block8', 'block9'})
        self.assertEqual(len(result['trials']), 18)

    def test_pre_native_tick_evidence_cannot_create_native_rules(self):
        old = self.memory()
        old['localRuleMemory']['format'] = 'golems-local-rules-v1'
        self.assertIsNone(merge_local_rule_memory(old))


if __name__ == '__main__':
    unittest.main()
