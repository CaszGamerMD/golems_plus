#!/usr/bin/env python3
"""Compare three independent real Matrix builds, never confuse a cutoff with completion."""
import json
import re
import sys
from pathlib import Path

FIELDS = re.compile(r"\[MATRIX TRIAL RESULT\] outcome=(\w+) layer=(\S+) elapsedMs=(\d+) tick=(\d+) correct=(\d+) total=(\d+) best=(\d+) unsupported=(\d+)")

def extract(log):
    matches = FIELDS.findall(log)
    if not matches:
        return {"outcome": "MISSING_RESULT"}
    outcome, layer, elapsed, tick, correct, total, best, unsupported = matches[-1]
    return dict(outcome=outcome, layer=layer, elapsedMs=int(elapsed), tick=int(tick),
                correct=int(correct), total=int(total), best=int(best), unsupported=int(unsupported))

def compare(rows):
    valid = len(rows) == 3 and all("elapsedMs" in r for r in rows)
    spread = max(r["elapsedMs"] for r in rows)-min(r["elapsedMs"] for r in rows) if valid else None
    same_layer = valid and len({r["layer"] for r in rows}) == 1
    natural_stop = valid and all(r["outcome"] in ("COMPLETE","ALL_BUILDERS_STALLED","NO_WORK_PROGRESS") for r in rows)
    consistent = bool(same_layer and natural_stop and spread <= 300000)
    complete = valid and all(r["outcome"]=="COMPLETE" and r["correct"]==r["total"]==15376 for r in rows)
    return dict(trials=rows, sameLayer=bool(same_layer), elapsedSpreadMs=spread,
                repeatableStop=consistent, allComplete=bool(complete),
                result="FULL_SUCCESS" if consistent and complete else
                "REPEATABLE_STALL" if consistent else "NOT_YET_RELIABLE")

if __name__ == "__main__":
    if sys.argv[1]=="extract":
        result=extract(Path(sys.argv[2]).read_text(errors="replace"))
        result["trial"]=int(sys.argv[3])
        Path(sys.argv[4]).write_text(json.dumps(result,indent=2)+"\n")
    elif sys.argv[1]=="compare":
        root=Path(sys.argv[2])
        rows=[]
        for trial in (1,2,3):
            path=root/f"matrix-trial-{trial}"/"result.json"
            rows.append(json.loads(path.read_text()) if path.exists() else
                        {"trial":trial,"outcome":"MISSING_RESULT"})
        result=compare(rows)
        print(json.dumps(result,indent=2))
        Path("matrix-reliability.json").write_text(json.dumps(result,indent=2)+"\n")
        with open(sys.argv[3],"a") as f:
            f.write("## Three-run Matrix reliability\n\n")
            f.write("| Trial | Outcome | Layer | Correct blocks | Elapsed | Game ticks |\n|---|---|---|---:|---:|---:|\n")
            for r in rows:
                sec=r.get("elapsedMs",0)//1000
                elapsed=f"{sec//60}:{sec%60:02}" if "elapsedMs" in r else "—"
                f.write(f"| {r['trial']} | {r['outcome']} | {r.get('layer','—')} | {r.get('correct','—')} | {elapsed} | {r.get('tick','—')} |\n")
            f.write(f"\n**Result: {result['result']}**\n\n")
            f.write(f"Same stopping layer: {result['sameLayer']}. Runtime spread: {result['elapsedSpreadMs']} ms. All complete: {result['allComplete']}.\n")
        sys.exit(0 if result["repeatableStop"] else 1)
