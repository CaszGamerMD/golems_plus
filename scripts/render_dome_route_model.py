#!/usr/bin/env python3
"""Render original schematic and *actual* Minecraft collision snapshots.
No third-party dependencies. All voxel records come from GameTest world.
"""
import csv
import html
import json
import re
import sys
from pathlib import Path

ROOT=Path("build/run/gameTest/route-survey")
HTML=ROOT/"dome_route_model.html"
def read(path):
    with path.open(encoding="utf-8",newline="") as f:
        result=[]
        for r in csv.DictReader(f):
            result.append({
                "x":int(r["x"]),"y":int(r["y"]),"z":int(r["z"]),
                "intended":r["intendedBlock"],"actual":r["physicalBlock"],
                "height":float(r["collisionTop"])
            })
    return result

def worker_info(report):
    if not report.exists(): return []
    txt=report.read_text(encoding="utf-8")
    items=[]
    current=None
    for line in txt.splitlines():
        if line.startswith("[WORKER]"):
            m=re.search(r"name=(.*?) claimedIndex=(\d+) feet=(-?\d+, -?\d+, -?\d+).*? target=(-?\d+, -?\d+, -?\d+) block=(\S+)",line)
            if m:
                current={"name":m.group(1), "index":int(m.group(2)),
                         "start":[int(n) for n in m.group(3).split(", ")],
                         "goal":[int(n) for n in m.group(4).split(", ")],
                         "block":m.group(5),"route":[],"graph":""}
                items.append(current)
        elif line.startswith("[GROUND GRAPH]") and current:
            current["graph"]=line
            coords=line.partition(" waypoints=")[2]
            current["route"]=[
                [int(x) for x in piece.split(", ")]
                for piece in re.findall(r"-?\d+, -?\d+, -?\d+",coords)
            ]
    return items

def build():
    if not ROOT.exists(): return False
    base=ROOT/"original_dome_exact_model.csv"
    live=ROOT/"live_dome_last_stall.csv"
    source=live if live.exists() else base
    if not source.exists():return False
    data=read(source)
    workers=worker_info(ROOT/"live_worker_route_analysis.txt")
    if not workers:
        workers=[
            {"name":"North handoff","start":[8,26,11],"goal":[15,30,11],"route":[],"graph":"survey pre-roof geometry"},
            {"name":"South handoff","start":[9,26,24],"goal":[15,30,24],"route":[],"graph":"survey pre-roof geometry"},
            {"name":"Cobblestone leg","start":[8,26,12],"goal":[15,30,12],"route":[],"graph":"survey pre-roof geometry"},
            {"name":"Diorite leg","start":[22,29,23],"goal":[20,30,22],"route":[],"graph":"survey pre-roof geometry"},
        ]
    flavor="LIVE stalled dome (actual placed blocks)" if live.exists() else "PRE-ROOF reconstructed survey (Y30+ still air)"
    description=("All expected blocks from the original verified litematic, plus the"
                 " physical state and collision top measured by Minecraft."
                 " Route lines are analytical candidate waypoints, NOT"
                 " proof of physically completed traversal.")
    obj={"rows":data,"workers":workers,"flavor":flavor,
         "description":description,"source":source.name}
    tmpl=r'''<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Dome • Exact Voxel & Builder Route Survey</title>
<style>
:root{color-scheme:dark;--bg:#0f151e;--panel:#1b2533;--border:#344357;--ink:#e5edf7;--muted:#95a8c1}
*{box-sizing:border-box}body{font:15px/1.45 system-ui,Arial,sans-serif;background:var(--bg);color:var(--ink);margin:0;padding:20px}
h1{margin:0;font-size:26px;letter-spacing:-.7px}h2{font-size:17px;margin:0 0 10px}
main{max-width:1360px;margin:0 auto}.intro{color:var(--muted);max-width:1000px}
.layout{display:grid;grid-template-columns:320px minmax(0,1fr);gap:18px;margin-top:18px}
.panel{background:var(--panel);border:1px solid var(--border);border-radius:12px;padding:16px}
.controls{display:grid;gap:12px}label{font-weight:650;font-size:13px;display:grid;gap:5px}
select,input{width:100%;accent-color:#83b8f5;background:#101b29;color:var(--ink);padding:9px;border-radius:6px;border:1px solid #53627a}
#slice{width:100%;max-width:780px;image-rendering:pixelated;aspect-ratio:1;background:#101c2b;border:1px solid var(--border);border-radius:8px}
.note{color:var(--muted);font-size:13px}.stat{padding:10px 0;border-bottom:1px solid #334356}.legend{display:flex;flex-wrap:wrap;gap:10px;margin:14px 0}.chip{font-size:12px}.swatch{display:inline-block;width:13px;height:13px;vertical-align:middle;border-radius:3px;margin-right:4px;border:1px solid #ffffff44}
#inspect{white-space:pre-wrap;font:12px/1.5 ui-monospace,SFMono-Regular,monospace;overflow-wrap:anywhere;min-height:75px}
@media(max-width:820px){.layout{grid-template-columns:1fr}body{padding:12px}}
</style></head><body><main>
<h1>Minecraft dome — exact geometry and route model</h1>
<p class="intro" id="intro"></p>
<div class="layout">
<section class="panel controls">
<h2>Explore the build</h2>
<label>Model view <select id="mode"><option value="actual">Actual physical blocks</option><option value="intended">Completed blueprint</option><option value="diff">Missing construction only</option></select></label>
<label>Vertical schematic layer: <output id="yLabel">30</output><input id="layer" type="range" min="0" max="40" value="30"></label>
<label>Builder / destination <select id="worker"></select></label>
<label>Overlay <select id="overlay"><option value="worker">Worker and target</option><option value="route">Candidate A* waypoints</option><option value="none">No overlay</option></select></label>
<label><input id="footprint" type="checkbox" checked style="width:auto"> Show neighboring collision surfaces</label>
<div class="stat" id="status"></div>
<h2>Selected voxel</h2><div id="inspect">Move the cursor over a block.</div>
<p class="note">Coordinates are from the original Litematic origin. Y30 is the air pocket/roof-support layer. A full block of glass is solid; panes, walls, stairs and slabs have different collision geometry.</p>
</section>
<section class="panel">
<h2 id="heading">XY-slice</h2>
<canvas id="slice" width="820" height="820"></canvas>
<div class="legend" id="legend"></div>
<p class="note">Bright cyan = worker. Red outline = target. Yellow route = analyzed waypoints. The dim grid highlights places where a block belongs but has not been physically placed.</p>
</section></div>
</main>
<script>
const MODEL=__MODEL__;
const $=id=>document.getElementById(id);
const el=$('slice'),ctx=el.getContext('2d'),w=820,margin=44,cell=18;
const palette={glass:'#7fbae7',wall:'#d7a266',fence:'#d3c47d',slab:'#a9a1db',stairs:'#b998d4',scaffold:'#dfb37a',stone:'#768995',diorite:'#c1cad9',cobble:'#8d9d9c',plant:'#6a9f68',other:'#94afbd',air:'#112033'};
const map=new Map(MODEL.rows.map(r=>[[r.x,r.y,r.z].join(','),r]));
function key(x,y,z){return [x,y,z].join(',')}
function stateType(s){
 if(!s||/minecraft:air/.test(s))return 'air';
 if(/stained_glass|minecraft:glass/.test(s))return 'glass';
 if(/scaffolding/.test(s))return 'scaffold';
 if(/fence/.test(s))return 'fence';
 if(/_wall/.test(s))return 'wall';
 if(/_slab/.test(s))return 'slab';
 if(/_stairs/.test(s))return 'stairs';
 if(/diorite/.test(s))return 'diorite';
 if(/cobblestone/.test(s))return 'cobble';
 if(/flower|tulip|grass|wheat|bush|sapling/.test(s))return 'plant';
 if(/stone|brick|log|plank|concrete/.test(s))return 'stone';
 return 'other';
}
function color(t){return palette[stateType(t)]||palette.other}
function blockAt(x,y,z){return map.get(key(x,y,z))}
function getState(row){
 const mode=$('mode').value;
 if(mode==='intended')return row?.intended||'minecraft:air';
 if(mode==='diff')return row&&stateType(row.actual)==='air'?row.intended:'minecraft:air';
 return row?.actual||'minecraft:air';
}
function worker(){return MODEL.workers[Number($('worker').value)]||MODEL.workers[0]}
function draw(){
 const y=Number($('layer').value),person=worker();
 $('yLabel').value=String(y);
 ctx.clearRect(0,0,w,w);ctx.fillStyle='#101c2b';ctx.fillRect(0,0,w,w);
 const actual=$('mode').value==='actual';
 const footprint=$('footprint').checked;
 for(let z=0;z<39;z++)for(let x=0;x<39;x++){
  const r=blockAt(x,y,z),s=getState(r),xx=margin+x*cell,yy=margin+z*cell;
  ctx.fillStyle=color(s);ctx.fillRect(xx+1,yy+1,cell-2,cell-2);
  if(actual&&r&&stateType(r.actual)==='air'&&stateType(r.intended)!=='air'){
   ctx.strokeStyle='#ffe07d';ctx.globalAlpha=.5;ctx.setLineDash([2,3]);ctx.strokeRect(xx+2,yy+2,cell-4,cell-4);ctx.setLineDash([]);ctx.globalAlpha=1;
  }
  if(footprint&&r&&r.height>0&&r.height<1.01&&r.height!==1){
   ctx.fillStyle='#f6d796a6';ctx.fillRect(xx+cell-6,yy+2,4,4);
  }
 }
 ctx.lineWidth=1;ctx.strokeStyle='#35506e';
 for(let i=0;i<=39;i++){const px=margin+i*cell;
  ctx.beginPath();ctx.moveTo(px,margin);ctx.lineTo(px,margin+39*cell);ctx.stroke();
  ctx.beginPath();ctx.moveTo(margin,px);ctx.lineTo(margin+39*cell,px);ctx.stroke();
 }
 ctx.fillStyle='#a9bfd7';ctx.font='11px ui-monospace,monospace';
 for(let n=0;n<39;n+=5){ctx.fillText(n,margin+n*cell,margin-9);ctx.fillText(n,margin-28,margin+(n+.6)*cell)}
 if(person&&$('overlay').value!=='none'){
  const plot=(p)=>[margin+(p[0]+.5)*cell,margin+(p[2]+.5)*cell];
  const a=plot(person.start),b=plot(person.goal);
  if($('overlay').value==='route'&&person.route.length){
   const points=[person.start,...person.route];
   ctx.strokeStyle='#ffe382';ctx.lineWidth=3;ctx.beginPath();
   points.forEach((p,i)=>{const [px,py]=plot(p);i?ctx.lineTo(px,py):ctx.moveTo(px,py)});ctx.stroke();
  }
  ctx.fillStyle='#58f4f5';ctx.beginPath();ctx.arc(...a,7,0,Math.PI*2);ctx.fill();
  ctx.strokeStyle='#ff7777';ctx.lineWidth=3;ctx.strokeRect(b[0]-8,b[1]-8,16,16);
  ctx.fillStyle='#fff';ctx.font='bold 11px sans-serif';ctx.fillText('A',a[0]-4,a[1]+4);ctx.fillText('T',b[0]-3,b[1]+4);
 }
 $('heading').textContent='Schematic X–Z cross-section • Y '+y;
 $('status').textContent=person?person.name+' | feet '+person.start.join(', ')+' → target '+person.goal.join(', ')+'\\n'+(person.graph||''):'No worker selected';
}
function inspect(e){
 const rect=el.getBoundingClientRect(),scale=820/rect.width;
 const x=Math.floor((e.clientX-rect.left)*scale/cell-margin/cell),z=Math.floor((e.clientY-rect.top)*scale/cell-margin/cell),y=Number($('layer').value);
 if(x<0||z<0||x>=39||z>=39)return;
 const r=blockAt(x,y,z);
 $('inspect').textContent='Local XYZ: '+[x,y,z].join(', ')+'\\nExpected: '+(r?.intended||'air')+'\\nPhysical: '+(r?.actual||'air')+'\\nCollision top: '+(r?.height??'—');
}
$('intro').textContent=MODEL.flavor+' • '+MODEL.description;
$('worker').innerHTML=MODEL.workers.map((p,i)=>'<option value="'+i+'">'+p.name.replace(/&/g,'&amp;').replace(/</g,'&lt;')+'</option>').join('');
const legends=[['glass','solid glass'],['wall','wall 1.5'],['fence','fence 1.5'],['slab','slab'],['stairs','stairs'],['scaffold','scaffolding'],['diorite','diorite'],['cobble','cobblestone']];
$('legend').innerHTML=legends.map(([name,label])=>'<span class="chip"><i class="swatch" style="background:'+palette[name]+'"></i>'+label+'</span>').join('');
for(const id of ['mode','layer','worker','overlay','footprint'])$(id).addEventListener('input',draw);
el.addEventListener('pointermove',inspect);draw();
</script></body></html>'''
    content=tmpl.replace("__MODEL__",json.dumps(obj,separators=(",",":")).replace("</","<\\/"))
    HTML.write_text(content,encoding="utf-8")
    print("[ROUTE MODEL HTML] created",HTML,"mode",flavor,"entries",len(data),"workers",len(workers))
    return True

if __name__=="__main__":
    sys.exit(0 if build() else 1)
