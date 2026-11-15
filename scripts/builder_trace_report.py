#!/usr/bin/env python3
"""Evidence report from Builder flight-recorder logs; no inferred root-cause claims."""
import collections
import json
import re
import sys
from pathlib import Path

EVENT = re.compile(r"\[BUILDER TRACE EVENT\] worker=(.*?) tick=(\d+) kind=(\S+) (.*)")
CHECK = re.compile(r"\[BUILDER TRACE CHECKPOINT\] worker=(.*?) tick=(\d+) (.*)")

def report(log):
    dumps, current, checkpoints = [], None, collections.defaultdict(list)
    for line in log.splitlines():
        if "[BUILDER TRACE BEGIN]" in line:
            current = {"context": line.split("[BUILDER TRACE BEGIN]",1)[1].strip(),
                       "workers": collections.defaultdict(list)}
        elif "[BUILDER TRACE END]" in line and current is not None:
            dumps.append(current)
            current = None
        elif current is not None and (m := EVENT.search(line)):
            name,tick,kind,detail = m.groups()
            current["workers"][name].append({"tick":int(tick),"kind":kind,"detail":detail})
        elif (m := CHECK.search(line)):
            name,tick,detail = m.groups()
            checkpoints[name].append({"tick":int(tick),"detail":detail})
    if current is not None:
        current["incomplete"]=True
        dumps.append(current)
    for dump in dumps:
        for name, events in list(dump["workers"].items()):
            decisions=[e for e in events if e["kind"] not in ("SAMPLE","OWNER","REPEAT")]
            samples=[e for e in events if e["kind"] in ("SAMPLE","OWNER")]
            dump["workers"][name] = {
                "eventCount":len(events),
                "firstTick":events[0]["tick"],
                "lastTick":events[-1]["tick"],
                "decisionCounts":dict(collections.Counter(e["kind"] for e in decisions)),
                "lastDecisions":decisions[-12:],
                "lastMotionSamples":samples[-6:],
                "events":events,
            }
    return {"dumps":dumps,"earlyCheckpoints":dict(checkpoints),
            "traceAvailable":bool(dumps),
            "note":"Observed branches, not a proven root cause. Check latest motion against the last accepted/rejected route and recovery barrier."}

if __name__=="__main__":
    result=report(Path(sys.argv[1]).read_text(errors="replace"))
    Path(sys.argv[2]).write_text(json.dumps(result,indent=2)+"\n")
    print("Builder trace:",len(result["dumps"]),"dumps;",
          sum(len(d["workers"]) for d in result["dumps"]),"worker histories")
    if not result["traceAvailable"]:
        print("No complete trace collected; inspect startup/test failure.")
