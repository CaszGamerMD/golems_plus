"""Evaluate expanded environmental knowledge against the original geometry model.
Three leave-one-server-out folds are exploratory, not a final held-out test.
"""
import argparse
import json
from pathlib import Path
from actions import FEATURES, KNOWLEDGE, read_actions
from train import Network
from train_actions import labeled, scores, threshold_for


def experiment(rows):
    rows = labeled([r for r in rows if 'knowledge' in r])
    runs = sorted({r['run'] for r in rows})
    if len(runs) < 3:
        raise ValueError('Need three independent enriched servers')
    folds = []
    for held in runs:
        training = [r for r in rows if r['run'] != held]
        test = [r for r in rows if r['run'] == held]
        if {r['moved'] for r in training} != {0,1} or {r['moved'] for r in test} != {0,1}:
            folds.append(dict(held=held, status='insufficient class coverage'))
            continue
        result = dict(held=held, status='evaluated')
        for name, features in [('geometry',FEATURES),('environment',FEATURES+KNOWLEDGE)]:
            def adapt(r):
                return dict(r,features=r['features']+(r['knowledge'] if name=='environment' else []))
            a,b = list(map(adapt,training)),list(map(adapt,test))
            net=Network(seed=17,hidden=8,features=features)
            net.fit(a,epochs=60,rate=.03)
            threshold=threshold_for(a,[net.forward(r['features'])[1] for r in a])
            result[name]=scores(b,[net.forward(r['features'])[1] for r in b],threshold)
        folds.append(result)
    return dict(rows=len(rows), features=FEATURES+KNOWLEDGE, folds=folds,
                warning='Exploratory grouped cross-validation; no candidate outcome labels or policy comparison. No gameplay control.')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('logs',nargs='+')
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    result=experiment(read_actions(a.logs))
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    main()
