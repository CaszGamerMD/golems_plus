"""Reconstruct the exact non-air Chibi source from its compact column archive.
The 171 disjoint regions keep the existing importer under its air/region limits.
Run from repo root before curriculum GameTests. Standard library only.
"""
import collections
import gzip
import hashlib
import json
import lzma
import math
import struct
from pathlib import Path

ROOT=Path('src/gametest/resources/golems_plus_curriculum')
HASHES={'Dragon.litematic':'ccb6cf6a440b6f9c1fe7dd4c50c72c8baf60c22a4b09756a8b99eadb377d2a40',
        'froglights.litematic':'c9d3019305449a94d09980b27bcd15abb416657f61e06c636c94d686823c5467',
        'homeDemo.litematic':'f388372d46d6f94747fb4576789eca07fa2b5f086722a85fd7379f650ceda52c',
        'CaszChibi.columns.gz':'e7fec9cb11e770c19c63c2397b7cb003f55d371449eed27040334e1f3ae596cd',
        'farmDemos.states.xz':'b46922c9e70d4480eddef4df34d212b6e66540a2b0b54d4b36919a7013d1ffec'}
CELL_HASH='616846073ae6295a79f64f05c7e4b657ae8c2c5062c83f364ae2721674a4bc86'

def nstr(s):
    b=s.encode('utf-8')
    return struct.pack('>H',len(b))+b

def tag(t,n,v):
    return bytes([t])+nstr(n)+payload(t,v)

def payload(t,v):
    if t==3: return struct.pack('>i',v)
    if t==4: return struct.pack('>q',v)
    if t==8: return nstr(v)
    if t==10: return b''.join(tag(k,n,x) for n,(k,x) in v.items())+b'\0'
    if t==9:
        k,items=v
        return bytes([k])+struct.pack('>i',len(items))+b''.join(payload(k,x) for x in items)
    if t==12: return struct.pack('>i',len(v))+b''.join(struct.pack('>Q',x) for x in v)
    raise ValueError(t)

def vec(xyz):
    return {a:(3,v) for a,v in zip('xyz',xyz)}

def palstate(p):
    result={'Name':(8,p['Name'])}
    if p.get('Properties'):
        result['Properties']=(10,{k:(8,v) for k,v in p['Properties'].items()})
    return result

def canonical(p):
    props=p.get('Properties',{})
    return p['Name']+('['+','.join(k+'='+v for k,v in sorted(props.items()))+']' if props else '')

def pack(ids,n):
    bits=max(2,(n-1).bit_length())
    out=[0]*((len(ids)*bits+63)//64)
    for i,v in enumerate(ids):
        j,s=divmod(i*bits,64)
        out[j]|=(v<<s)&((1<<64)-1)
        if s+bits>64: out[j+1]|=v>>(64-s)
    return out

def prepare_farm():
    # Source Sponge v3 state data, stored X/Y/Z-major to compress efficiently.
    # No inventories or transient entity NBT are reconstructed.
    metadata,raw=lzma.decompress((ROOT/'farmDemos.states.xz').read_bytes()).split(b'\n',1)
    source=json.loads(metadata)
    w,h,d=source['size']
    offset=source['offset']
    states=source['palette']
    if (w,h,d)!=(83,43,43) or offset!=[-82,0,-43] or len(states)!=186:
        raise ValueError('Farm Demos metadata mismatch')
    if len(raw)!=w*h*d or max(raw)>=len(states) or states[0]!='minecraft:air':
        raise ValueError('Invalid Farm Demos palette indices')
    ids=[raw[(x*h+y)*d+z] for y in range(h) for z in range(d) for x in range(w)]
    palette=[]
    for state in states:
        name,sep,props=state.partition('[')
        p={'Name':name}
        if sep:
            p['Properties']=dict(v.split('=',1) for v in props[:-1].split(','))
        palette.append(p)
    cells=sorted((x+offset[0],y+offset[1],z+offset[2],canonical(palette[ids[(y*d+z)*w+x]]))
                 for y in range(h) for z in range(d) for x in range(w)
                 if ids[(y*d+z)*w+x]!=0)
    digest=hashlib.sha256(''.join(f'{x},{y},{z},{state}\n' for x,y,z,state in cells).encode()).hexdigest()
    if len(cells)!=13143 or digest!='31a88223773888df19d1ff9dcaa2ee88869f941adde5183599fe6a1ae12fbbbc':
        raise ValueError('Farm Demos coordinate/state mismatch')
    region={'Position':(10,vec(offset)),'Size':(10,vec((w,h,d))),
            'BlockStatePalette':(9,(10,[palstate(p) for p in palette])),
            'BlockStates':(12,pack(ids,len(palette)))}
    for name in ('Entities','TileEntities','PendingBlockTicks','PendingFluidTicks'):
        region[name]=(9,(10,[]))
    metadata={'Name':(8,'Farm Demos'),'Author':(8,'CaszGamerMD'),
              'Description':(8,'Block-state training fixture'),
              'TotalBlocks':(3,len(cells)),'TotalVolume':(3,len(ids)),
              'RegionCount':(3,1),'EnclosingSize':(10,vec((w,h,d))),
              'TimeCreated':(4,0),'TimeModified':(4,0)}
    root={'Version':(3,7),'SubVersion':(3,1),'MinecraftDataVersion':(3,source['data_version']),
          'Metadata':(10,metadata),'Regions':(10,{'farmDemos':(10,region)})}
    (ROOT/'farmDemos.litematic').write_bytes(gzip.compress(tag(10,'',root),mtime=0))
    print('Verified Farm Demos: 13143 source blocks, 140324 air cells, exact signed coordinates')


def main():
    for name,digest in HASHES.items():
        if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=digest:
            raise ValueError('Fixture checksum mismatch: '+name)
    prepare_farm()
    palette=json.loads((ROOT/'CaszChibi.palette.json').read_text())
    raw=gzip.decompress((ROOT/'CaszChibi.columns.gz').read_bytes())
    n=128*129
    if len(raw)!=65085: raise ValueError('Bad column archive length')
    counts,delta,top,lower=raw[:n],raw[n:2*n],raw[2*n:3*n],raw[3*n:]
    cells={}
    y=0
    j=0
    for i,count in enumerate(counts):
        y=(y+delta[i])%256
        x,z=i%128,i//128
        if not 1<=count<=3 or not count-1<=y<=128:
            raise ValueError('Invalid column geometry')
        cells[x,y,z]=top[i]
        for dy in range(1,count):
            cells[x,y-dy,z]=lower[j]
            j+=1
    if j!=len(lower) or len(cells)!=32061 or max(cells.values())>=len(palette):
        raise ValueError('Invalid source cells')
    digest=hashlib.sha256(''.join(f'{x},{y},{z},{canonical(palette[v])}\n'
                    for (x,y,z),v in sorted(cells.items())).encode()).hexdigest()
    if digest!=CELL_HASH: raise ValueError('Chibi source cell mismatch')
    groups=collections.defaultdict(list)
    for (x,y,z),v in cells.items():
        groups[x//16,y//16,z//16].append(((x,y,z),v))
    regions={}
    volume=0
    for k,bs in sorted(groups.items()):
        lo=[min(p[i] for p,_ in bs) for i in range(3)]
        hi=[max(p[i] for p,_ in bs) for i in range(3)]
        size=[b-a+1 for a,b in zip(lo,hi)]
        w,h,d=size
        used=sorted({v for _,v in bs})
        pal=[{'Name':'minecraft:air'}]+[palette[v] for v in used]
        lookup={v:i+1 for i,v in enumerate(used)}
        ids=[0]*(w*h*d)
        for p,v in bs:
            x,y,z=[p[i]-lo[i] for i in range(3)]
            ids[(y*d+z)*w+x]=lookup[v]
        r={'Position':(10,vec(lo)),'Size':(10,vec(size)),
           'BlockStatePalette':(9,(10,[palstate(p) for p in pal])),
           'BlockStates':(12,pack(ids,len(pal)))}
        for name in ('Entities','TileEntities','PendingBlockTicks','PendingFluidTicks'):
            r[name]=(9,(10,[]))
        regions['_'.join(map(str,k))]=(10,r)
        volume+=len(ids)
    if len(regions)!=171 or volume-len(cells)!=276058:
        raise ValueError('Unexpected region/air count')
    metadata={'Name':(8,'Casz Chibi'),'Author':(8,'CaszGamerMD'),
              'Description':(8,'Training fixture: block states only'),
              'TotalBlocks':(3,len(cells)),'TotalVolume':(3,volume),
              'RegionCount':(3,len(regions)),'EnclosingSize':(10,vec((128,129,129))),
              'TimeCreated':(4,0),'TimeModified':(4,0)}
    root={'Version':(3,7),'SubVersion':(3,1),'MinecraftDataVersion':(3,3463),
          'Metadata':(10,metadata),'Regions':(10,regions)}
    (ROOT/'CaszChibi.litematic').write_bytes(gzip.compress(tag(10,'',root),mtime=0))
    print('Verified all fixture source hashes; generated Chibi: 32061 blocks, 171 regions, 276058 air cells')

if __name__=='__main__':
    main()
