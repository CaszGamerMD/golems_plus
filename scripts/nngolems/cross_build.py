"""Frozen cross-build experiment. Fit Matrix/Home/Dragon; hold out Froglights/Farm/Chibi.
No online learning or steering. Each report retains separate course coverage.
"""
import argparse
import json
import math
import random
from pathlib import Path
from train import Network, FEATURES_BY_SCHEMA, read_rows
from actions import FEATURES, KNOWLEDGE, read_actions
from placements import FEATURES as PLACEMENT_FEATURES, read_placements
from train_actions import labeled, scores, threshold_for

TRAIN=('matrix','home','dragon')
HELD=('froglights','farm','chibi')


def load(root, filename, reader):
    data={}
    for course in TRAIN+HELD:
        paths=sorted((root/course).glob('*/'+filename))
        if not paths:
            raise ValueError('Missing archived course '+course+'/'+filename)
        data[course]=reader(paths)
    return data


def partition(data):
    a=[r for c in TRAIN for r in data[c]]
    b=[r for c in HELD for r in data[c]]
    train_runs={r['run'] for r in a}
    held_runs={r['run'] for r in b}
    if train_runs & held_runs or len(train_runs)<3 or not b:
        raise ValueError('Invalid course/server split')
    if {r['moved'] for r in a}!={0,1}:
        raise ValueError('Training needs both outcomes')
    return a,b


def placement_rows(rows):
    # Dependency repair is neither completion of original claim nor a wait.
    # Exact repeated decision contexts are collapsed only when fitting.
    return [dict(r,moved=int(r['outcome']=='COMPLETED'))
            for r in rows if r['outcome']!='REPAIRED_DEPENDENCY']


def compact_placements(rows):
    seen=set()
    result=[]
    for r in rows:
        key=(r['run'],r['worker'],r['desired'],r['existing'],r['support'],
             r['category'],tuple(r['features']),r['outcome'])
        if key not in seen:
            seen.add(key)
            result.append(r)
    return result


def fit_linear(rows,n,epochs):
    weights=[0.]*n
    bias=0.
    order=list(rows)
    rng=random.Random(17)
    def predict(x):
        z=max(-40,min(40,sum(w*v for w,v in zip(weights,x))+bias))
        return 1/(1+math.exp(-z))
    for _ in range(epochs):
        rng.shuffle(order)
        for r in order:
            e=predict(r['features'])-r['moved']
            for i,x in enumerate(r['features']):
                weights[i]-=.03*e*x
            bias-=.03*e
    return predict


def experiment(data,features,epochs,compact=False):
    train,held=partition(data)
    fit=compact_placements(train) if compact else train
    net=Network(seed=17,hidden=8,features=features)
    net.fit(fit,epochs=epochs,rate=.03)
    p=lambda rows:[net.forward(r['features'])[1] for r in rows]
    linear=fit_linear(fit,len(features),epochs)
    t=threshold_for(fit,p(fit))
    lt=threshold_for(fit,[linear(r['features']) for r in fit])
    rate=sum(r['moved'] for r in fit)/len(fit)
    def evaluate(rows):
        if not rows:
            return dict(samples=0,warning='No observations; not a prediction result')
        return dict(neural=scores(rows,p(rows),t),
                    neural_default=scores(rows,p(rows),.5),
                    linear=scores(rows,[linear(r['features']) for r in rows],lt),
                    always_positive=scores(rows,[rate]*len(rows),.5))
    report=dict(training_samples=len(train),fit_samples=len(fit),
                training_runs=sorted({r['run'] for r in train}),
                held_runs=sorted({r['run'] for r in held}),
                threshold=t,linear_threshold=lt,held_out=evaluate(held),
                by_course={c:evaluate(data[c]) for c in HELD})
    model=dict(features=features,threshold=t,w=net.w,b=net.b,v=net.v,c=net.c)
    return report,model


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('root',type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--commit',required=True)
    a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=True)
    movement=load(a.root,'samples.log',read_rows)
    flight=load(a.root,'actions.log',read_actions)
    placement=load(a.root,'placements.log',read_placements)
    flights={c:labeled(rows) for c,rows in flight.items()}
    # Refuse silently missing enriched fields.
    enriched={c:[dict(r,features=r['features']+r['knowledge']) for r in rows]
              for c,rows in flights.items()}
    jobs=[
        ('movement',movement,FEATURES_BY_SCHEMA[3],20,False),
        ('flight_geometry',flights,FEATURES,40,False),
        ('flight_environment',enriched,FEATURES+KNOWLEDGE,40,False),
        ('placement', {c:placement_rows(rows) for c,rows in placement.items()},
         PLACEMENT_FEATURES,30,True)]
    report=dict(commit=a.commit,train_courses=TRAIN,held_courses=HELD,
                source_runs=[43811361323,43832947779,43833231954],
                warning='Held-out from model fitting, not a blind policy trial. '
                'Flight predicts accepted action arrival; placement predicts near-work completion vs wait/rejection, not build completion. '
                'Repeated observations and sparse class coverage limit generalization. No steering.')
    for name,rows,features,epochs,compact in jobs:
        report[name],model=experiment(rows,features,epochs,compact)
        (a.output/(name+'-model.json')).write_text(json.dumps(model,indent=2))
        print(name,json.dumps(report[name],indent=2),flush=True)
    (a.output/'cross-build-report.json').write_text(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
