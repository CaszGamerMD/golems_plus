"""Offline flight arrival model: train on archived runs, evaluate on fresh servers."""
import argparse
import json
import math
from pathlib import Path
from actions import FEATURES, TERMINAL, read_actions
from train import Network


def labeled(rows):
    return [dict(r, moved=int(r['outcome'] == 'ARRIVED'))
            for r in rows if r['outcome'] in TERMINAL]


def check_split(training, held):
    for name, rows in [('training', training), ('held-out', held)]:
        if len({r['run'] for r in rows}) < 3 or {r['moved'] for r in rows} != {0, 1}:
            raise ValueError(name + ' needs three independent runs and both outcomes')
    if {r['run'] for r in training} & {r['run'] for r in held}:
        raise ValueError('Training and held-out servers overlap')


def scores(rows, probabilities, threshold):
    if not rows:
        return None
    n = len(rows)
    pairs = list(zip(rows, probabilities))
    tp = sum(p >= threshold and r['moved'] for r,p in pairs)
    tn = sum(p < threshold and not r['moved'] for r,p in pairs)
    positives = sum(r['moved'] for r in rows)
    negatives = n - positives
    arrival = tp/positives if positives else None
    failure = tn/negatives if negatives else None
    return dict(samples=n, arrivals=positives, failures=negatives,
                accuracy=(tp+tn)/n, arrival_recall=arrival, failure_recall=failure,
                balanced_accuracy=(arrival+failure)/2 if arrival is not None and failure is not None else None,
                log_loss=-sum(math.log(max(1e-12, p if r['moved'] else 1-p)) for r,p in pairs)/n,
                false_alarms=positives-tp, missed_failures=negatives-tn)


def threshold_for(rows, p):
    # Fixed grid; choose only from training data. Tie favors fewer false alarms.
    return max((i/100 for i in range(1,100)),
               key=lambda t:(scores(rows,p,t)['balanced_accuracy'], -t))


def evaluate(training, held):
    check_split(training, held)
    # Fixed architecture and schedule before any fresh evaluation.
    net = Network(seed=17, hidden=8, features=FEATURES)
    net.fit(training, epochs=60, rate=.03)
    predict = lambda rows: [net.forward(r['features'])[1] for r in rows]
    threshold = threshold_for(training, predict(training))
    # A transparent linear logistic baseline uses identical inputs and data.
    # Use a plain logistic fit rather than hidden-unit nonlinear features.
    weights, bias = [0.]*len(FEATURES), 0.
    import random
    rng = random.Random(17)
    order = list(training)
    def lp(x):
        z = max(-40,min(40,sum(a*b for a,b in zip(weights,x))+bias))
        return 1/(1+math.exp(-z))
    for _ in range(60):
        rng.shuffle(order)
        for r in order:
            error = lp(r['features']) - r['moved']
            weights = [w-.03*error*x for w,x in zip(weights,r['features'])]
            bias -= .03*error
    linear_threshold = threshold_for(training, [lp(r['features']) for r in training])
    prevalence = sum(r['moved'] for r in training)/len(training)
    def evaluate_set(rows):
        p = predict(rows)
        return dict(neural=scores(rows,p,threshold),
                    neural_default_threshold=scores(rows,p,.5),
                    linear=scores(rows,[lp(r['features']) for r in rows],linear_threshold),
                    always_arrive=scores(rows,[prevalence]*len(rows),.5))
    result = dict(training_runs=sorted({r['run'] for r in training}),
                  held_out_runs=sorted({r['run'] for r in held}),
                  threshold=threshold, linear_threshold=linear_threshold,
                  training=evaluate_set(training), held_out=evaluate_set(held),
                  by_run={run:evaluate_set([r for r in held if r['run']==run])
                          for run in sorted({r['run'] for r in held})},
                  warning='Accepted-flight prediction only; no candidate ranking or gameplay control. Threshold is a classification operating point, not a safety guarantee.')
    model = dict(schema=1,target='accepted_flight_arrives',features=FEATURES,
                 threshold=threshold,w=net.w,b=net.b,v=net.v,c=net.c)
    return model,result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--train',nargs='+',required=True)
    p.add_argument('--held',nargs='+',required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--commit',required=True)
    a=p.parse_args()
    model,result=evaluate(labeled(read_actions(a.train)),labeled(read_actions(a.held)))
    result['commit']=a.commit
    a.output.mkdir(parents=True,exist_ok=True)
    (a.output/'flight-model.json').write_text(json.dumps(model,indent=2))
    (a.output/'flight-prediction-report.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    main()
