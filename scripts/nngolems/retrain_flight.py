"""Predeclared failure-balanced, rotation-invariant neural experiment.
Old course split retained. Latest Matrix trials 1/2 train; trial 3 evaluates.
Archives were observed before this experiment, so this is retrospective validation.
"""
import argparse
import json
from pathlib import Path
from actions import read_actions
from train import Network
from train_actions import labeled, scores, threshold_for
from cross_build import load, TRAIN, HELD
from symmetry import NAMES, balanced_examples, invariant_score, orbit


def enriched(rows):
    return [dict(r,features=r['features']+r['knowledge']) for r in labeled(rows)]


def frozen(path):
    m=json.loads(path.read_text())
    if m['features'] != NAMES:
        raise ValueError('Wrong baseline feature schema')
    n=Network(features=NAMES)
    for k in ('w','b','v','c'):setattr(n,k,m[k])
    return n,m['threshold']


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('--recent',type=Path,required=True)
    p.add_argument('--baseline',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    courses={c:enriched(rows) for c,rows in load(a.root,'actions.log',read_actions).items()}
    recent={i:enriched(read_actions([a.recent/f'nn-trial-{i}'/'actions.log'])) for i in (1,2,3)}
    training=[r for c in TRAIN for r in courses[c]]+recent[1]+recent[2]
    groups={c:courses[c] for c in HELD}
    groups['recent_matrix_3']=recent[3]
    runs={r['run'] for r in training}
    if any(r['run'] in runs for rows in groups.values() for r in rows):
        raise ValueError('Training/evaluation server overlap')
    baseline,bt=frozen(a.baseline)
    # Fixed architecture, seed and schedule. No held-set parameter selection.
    net=Network(features=NAMES)
    net.fit(balanced_examples(training),epochs=40,rate=.01)
    predict=lambda rows:[invariant_score(net,r['features']) for r in rows]
    threshold=threshold_for(training,predict(training))
    def evaluate(rows):
        if not rows:return {'samples':0}
        old=[baseline.forward(r['features'])[1] for r in rows]
        new=predict(rows)
        return {'baseline':scores(rows,old,bt),'candidate':scores(rows,new,threshold),
                'baseline_rotation_spread_max':max(max(baseline.forward(x)[1] for x in orbit(r['features']))-
                    min(baseline.forward(x)[1] for x in orbit(r['features'])) for r in rows),
                'candidate_rotation_spread_max':max(max(invariant_score(net,x) for x in orbit(r['features']))-
                    min(invariant_score(net,x) for x in orbit(r['features'])) for r in rows)}
    report={'training_runs':sorted(runs),'training_samples':len(training),
            'training_failures':sum(not r['moved'] for r in training),
            'threshold':threshold,'by_course':{c:evaluate(rows) for c,rows in groups.items()},
            'training':evaluate(training),'promotion':'SHADOW_ONLY',
            'warning':'Retrospective prediction experiment; no gameplay changes. Failed builds are not flight failure labels. Counterfactual route outcomes remain unknown.'}
    model=dict(features=NAMES,threshold=threshold,w=net.w,b=net.b,v=net.v,c=net.c,
               symmetry='c4_y',target='accepted_flight_arrives',schema=2)
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'flight_environment-model.json').write_text(json.dumps(model,indent=2))
    (a.output/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
