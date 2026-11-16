"""C4 scene rotations for accepted-flight features. No invented outcomes."""
import math
from collections import Counter
from actions import FEATURES, KNOWLEDGE

NAMES = FEATURES + KNOWLEDGE


def rotate(x):
    """Rotate +90 degrees about Y: (dx,dz)->(-dz,dx); rotate probes too."""
    if len(x) != 24 or any(not math.isfinite(v) for v in x):
        raise ValueError('Expected 24 finite environmental flight features')
    y = list(x)
    y[0], y[2] = -x[2], x[0]
    # World +X now corresponds to old -Z, -X to old +Z,
    # +Z to old +X, -Z to old -X. Vertical probes are unchanged.
    for start in (7, 13):
        y[start], y[start+1] = x[start+5], x[start+4]
        y[start+4], y[start+5] = x[start], x[start+1]
    return y


def orbit(x):
    result = [list(x)]
    for _ in range(3):
        result.append(rotate(result[-1]))
    return result


def invariant_score(net, x):
    return sum(sorted(net.forward(v)[1] for v in orbit(x))) / 4


def balanced_examples(rows):
    """Equal mass per outcome, then per server within that outcome.

    Explicit weights avoid duplicating rare failures thousands of times. Four
    rotations each get one quarter of the original example's mass. The total
    effective sample weight equals the original row count.
    """
    counts = Counter((r['run'], r['moved']) for r in rows)
    if {r['moved'] for r in rows} != {0, 1}:
        raise ValueError('Both arrival and failure observations required')
    servers = {label: len({run for run, y in counts if y == label}) for label in (0, 1)}
    result = []
    for r in rows:
        weight = len(rows) / (2 * servers[r['moved']] * counts[r['run'], r['moved']])
        for x in orbit(r['features']):
            result.append(dict(r, features=x, weight=weight/4))
    return result
