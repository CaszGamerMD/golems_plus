"""Offline movement predictor. Standard library only; never controls a golem."""
import argparse
import json
import math
import random
from pathlib import Path

FEATURES = ['vx', 'vy', 'vz', 'grounded', 'FLIGHT', 'GRAPPLE', 'SCAFFOLD',
            'YIELD', 'CENTER', 'IDLE', 'WALK', 'has_goal', 'goal_dx_32',
            'goal_dy_32', 'goal_dz_32', 'clear_xp', 'clear_xm', 'clear_yp',
            'clear_ym', 'clear_zp', 'clear_zm']
MARKER = '[NNGOLEMS] '


def read_rows(paths):
    rows, seen = [], set()
    for path in paths:
        for line in Path(path).read_text().splitlines():
            if MARKER not in line:
                continue
            row = json.loads(line.split(MARKER, 1)[1])
            x = row['features']
            if (row['schema'] != 2 or len(x) != len(FEATURES)
                    or any(not isinstance(v, (int, float)) or not math.isfinite(v)
                           or abs(v) > 1 for v in x)
                    or row['moved'] not in (0, 1)
                    or not math.isfinite(row['distance'])
                    or not 0 <= row['distance'] <= 8
                    or row['moved'] != int(row['distance'] >= .25)):
                raise ValueError('Invalid movement observation')
            key = (row['run'], row['worker'], row['tick'])
            if key not in seen:
                rows.append(row)
                seen.add(key)
    return rows


def split_runs(rows, seed=17):
    runs = sorted({r['run'] for r in rows})
    if len(runs) < 3:
        raise ValueError('Collect at least three independent server runs')
    random.Random(seed).shuffle(runs)
    held = set(runs[:max(1, len(runs) // 3)])
    train = [r for r in rows if r['run'] not in held]
    test = [r for r in rows if r['run'] in held]
    for name, subset in [('training', train), ('held-out', test)]:
        if {r['moved'] for r in subset} != {0, 1}:
            raise ValueError(f'{name} runs must contain both moving and stationary samples')
    return train, test


class Network:
    def __init__(self, seed=17, hidden=8):
        rng = random.Random(seed)
        self.w = [[rng.uniform(-.3, .3) for _ in FEATURES] for _ in range(hidden)]
        self.b = [0.] * hidden
        self.v = [rng.uniform(-.3, .3) for _ in range(hidden)]
        self.c = 0.

    def forward(self, x):
        h = [math.tanh(sum(a*b for a, b in zip(w, x)) + bias)
             for w, bias in zip(self.w, self.b)]
        z = max(-40, min(40, sum(a*b for a, b in zip(self.v, h)) + self.c))
        return h, 1 / (1 + math.exp(-z))

    def fit(self, rows, epochs=30, rate=.03, seed=17):
        rng = random.Random(seed)
        order = list(rows)
        for _ in range(epochs):
            rng.shuffle(order)
            for row in order:
                x, y = row['features'], row['moved']
                h, p = self.forward(x)
                error = p - y
                for j in range(len(h)):
                    delta = error * self.v[j] * (1 - h[j]**2)
                    self.v[j] -= rate * error * h[j]
                    self.b[j] -= rate * delta
                    for i in range(len(x)):
                        self.w[j][i] -= rate * delta * x[i]
                self.c -= rate * error

    def export(self):
        return dict(schema=2, features=FEATURES, target='moves_at_least_0.25m_in_20_ticks',
                    w=self.w, b=self.b, v=self.v, c=self.c)


def metrics(model, rows):
    probabilities = [model.forward(r['features'])[1] for r in rows]
    return {'samples': len(rows),
            'accuracy': sum((p >= .5) == r['moved'] for p, r in zip(probabilities, rows))/len(rows),
            'log_loss': -sum(math.log(max(1e-12, p if r['moved'] else 1-p))
                             for p, r in zip(probabilities, rows))/len(rows),
            'stationary_recall': (sum(p < .5 and not r['moved'] for p, r in zip(probabilities, rows)) /
                                 sum(not r['moved'] for r in rows) if any(not r['moved'] for r in rows) else None),
            'moving_samples': sum(r['moved'] for r in rows),
            'stationary_samples': sum(not r['moved'] for r in rows)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logs', nargs='+')
    parser.add_argument('--commit', default='unknown')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    train, held = split_runs(read_rows(args.logs))
    model = Network()
    model.fit(train)
    majority = int(sum(r['moved'] for r in train) >= len(train)/2)
    # Choose velocity threshold using training runs only.
    speed = lambda r: math.sqrt(sum(v*v for v in r['features'][:3]))
    thresholds = [i/1000 for i in range(0, 301)]
    threshold = max(thresholds, key=lambda t: sum((speed(r) >= t) == r['moved'] for r in train))
    velocity_accuracy = sum((speed(r) >= threshold) == r['moved'] for r in held)/len(held)
    legacy_train = [dict(r, features=r['features'][:11] + [0.]*10) for r in train]
    legacy_held = [dict(r, features=r['features'][:11] + [0.]*10) for r in held]
    legacy = Network()
    legacy.fit(legacy_train)
    report = {'commit': args.commit, 'schema': 2,
              'velocity_baseline': {'threshold': threshold, 'held_out_accuracy': velocity_accuracy},
              'without_obstacle_goal_features': metrics(legacy, legacy_held),
              'by_controller': {c: metrics(model, [r for r in held if r['controller'] == c])
                                for c in sorted({r['controller'] for r in held})},
              'training': metrics(model, train), 'held_out': metrics(model, held),
              'majority_baseline_accuracy': sum(r['moved'] == majority for r in held)/len(held),
              'training_runs': sorted({r['run'] for r in train}),
              'held_out_runs': sorted({r['run'] for r in held}),
              'warning': 'Movement is not task success. No gameplay control or promotion.'}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'model.json').write_text(json.dumps(model.export(), indent=2))
    (args.output / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
