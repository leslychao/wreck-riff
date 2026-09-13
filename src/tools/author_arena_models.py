"""Original architectural mesh construction; GLB export with metric UVs and normals.

Run with CPython to export GLBs. Run with Blender --background --python this_file
-- --blend to import the exact GLB deliverables and save editable .blend sources.
All dimensional inputs match the canonical location's architectural placements.
"""
import collections
import json
import math
import struct
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'src/tools/assets/architecture'

class Model:
    def __init__(self):self.materials=collections.defaultdict(list)
    def face(self,points,material):
        for i in range(1,len(points)-1):
            a,b,c=points[0],points[i],points[i+1];u=[b[k]-a[k] for k in range(3)];v=[c[k]-a[k] for k in range(3)];n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];length=math.sqrt(sum(x*x for x in n))
            if length<1e-8:continue
            n=[x/length for x in n];axis=max(range(3),key=lambda k:abs(n[k]));axes=[k for k in range(3) if k!=axis]
            self.materials[material].extend((p,n,(p[axes[0]]/4,p[axes[1]]/4)) for p in (a,b,c))
    def box(self,c,s,material):
        x,y,z=c;w,h,d=[v/2 for v in s];p=[(x+dx*w,y+dy*h,z+dz*d) for dx,dy,dz in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]
        for face in [(0,3,2,1),(4,5,6,7),(0,4,7,3),(1,2,6,5),(0,1,5,4),(3,7,6,2)]:self.face([p[i] for i in face],material)
    def cylinder(self,c,r,h,material,segments=16,r2=None):
        r2=r if r2 is None else r2;x,y,z=c
        for i in range(segments):
            a=i*math.tau/segments;b=(i+1)*math.tau/segments;p=(x+r*math.cos(a),y-h/2,z+r*math.sin(a));q=(x+r*math.cos(b),y-h/2,z+r*math.sin(b));u=(x+r2*math.cos(a),y+h/2,z+r2*math.sin(a));v=(x+r2*math.cos(b),y+h/2,z+r2*math.sin(b))
            self.face([p,u,v,q],material);self.face([(x,y+h/2,z),v,u],material);self.face([(x,y-h/2,z),p,q],material)
    def beam(self,a,b,width,material):
        d=[b[k]-a[k] for k in range(3)];length=math.sqrt(sum(v*v for v in d));d=[v/length for v in d];up=[0,1,0] if abs(d[1])<.95 else [1,0,0];x=[d[1]*up[2]-d[2]*up[1],d[2]*up[0]-d[0]*up[2],d[0]*up[1]-d[1]*up[0]];l=math.sqrt(sum(v*v for v in x));x=[v/l*width/2 for v in x];y=[d[1]*x[2]-d[2]*x[1],d[2]*x[0]-d[0]*x[2],d[0]*x[1]-d[1]*x[0]]
        rings=[[tuple(p[k]+sx*x[k]+sy*y[k] for k in range(3)) for sx,sy in [(-1,-1),(1,-1),(1,1),(-1,1)]] for p in (a,b)]
        self.face(list(reversed(rings[0])),material);self.face(rings[1],material)
        for i in range(4):self.face([rings[0][i],rings[0][(i+1)%4],rings[1][(i+1)%4],rings[1][i]],material)
    def roof(self,w,d,height,material,segments=16,curve=True):
        profile=[(-w/2+w*i/segments,height*(math.sin(math.pi*i/segments) if curve else 1-abs(2*i/segments-1))) for i in range(segments+1)]
        for (x,y),(xx,yy) in zip(profile,profile[1:]):
            self.face([(x,y,-d/2),(x,y,d/2),(xx,yy,d/2),(xx,yy,-d/2)],material)
            self.face([(x,y-.8,d/2),(x,y-.8,-d/2),(xx,yy-.8,-d/2),(xx,yy-.8,d/2)],material)
            for z in (-d/2,d/2):self.face([(x,y,z),(xx,yy,z),(xx,yy-.8,z),(x,y-.8,z)] if z>0 else [(x,y-.8,z),(xx,yy-.8,z),(xx,yy,z),(x,y,z)],'steel')
        for x,y in (profile[0],profile[-1]):self.face([(x,y,-d/2),(x,y-.8,-d/2),(x,y-.8,d/2),(x,y,d/2)],material)
    def save(self,name):
        data=bytearray();views=[];accessors=[];meshes=[];nodes=[]
        def buffer(values,kind,components):
            while len(data)%4:data.append(0)
            offset=len(data);fmt='f' if kind==5126 else 'I';data.extend(struct.pack('<'+fmt*len(values),*values));view=len(views);views.append(dict(buffer=0,byteOffset=offset,byteLength=len(data)-offset))
            count=len(values)//components;ac=dict(bufferView=view,componentType=kind,count=count,type={1:'SCALAR',2:'VEC2',3:'VEC3'}[components])
            if components==3:ac.update(min=[min(values[k::components]) for k in range(components)],max=[max(values[k::components]) for k in range(components)])
            accessors.append(ac);return len(accessors)-1
        for material,vertices in self.materials.items():
            pos=buffer([v for p,n,uv in vertices for v in p],5126,3);normal=buffer([v for p,n,uv in vertices for v in n],5126,3);tex=buffer([v for p,n,uv in vertices for v in uv],5126,2);idx=buffer(list(range(len(vertices))),5125,1)
            meshes.append(dict(name='surface:'+material,primitives=[dict(attributes=dict(POSITION=pos,NORMAL=normal,TEXCOORD_0=tex),indices=idx,mode=4)]));nodes.append(dict(name='surface:'+material,mesh=len(meshes)-1))
        document=dict(asset=dict(version='2.0',generator='Wreck Riff original architectural authoring'),scene=0,scenes=[dict(nodes=list(range(len(nodes))))],nodes=nodes,meshes=meshes,accessors=accessors,bufferViews=views,buffers=[dict(byteLength=len(data))])
        header=json.dumps(document,separators=(',',':')).encode();header+=b' '*((-len(header))%4);data+=b'\0'*((-len(data))%4);blob=struct.pack('<III',0x46546c67,2,12+8+len(header)+8+len(data))+struct.pack('<II',len(header),0x4e4f534a)+header+struct.pack('<II',len(data),0x004e4942)+data
        (OUT/(name+'.glb')).write_bytes(blob)

def architectural(name,low=False):
    m=Model();detail=not low
    if name=='unfinished-frame':
        for c,s in [((0,.4,-53),(235,.8,24)),((0,.4,53),(235,.8,24)),((-105,.4,0),(25,.8,82)),((70,.4,0),(95,.8,82))]:m.box(c,s,'concrete')
        for x in range(-100,110,35 if detail else 105):
            m.box((x,-1.2,0),(2.5,2.4,130),'concrete')
            if detail:
                for z in (-52,52):m.cylinder((x,2,z),.1,4,'rust',6)
    elif name=='plant-sawtooth':
        for i in range(5):
            x=-105+i*42
            m.face([(x,0,-100),(x,0,100),(x+42,6,100),(x+42,6,-100)],'steel');m.face([(x+42,6,-100),(x+42,6,100),(x+42,0,100),(x+42,0,-100)],'glass')
            m.face([(x,-.8,100),(x,-.8,-100),(x+42,5.2,-100),(x+42,5.2,100)],'steel')
            for z in (-100,100):m.face([(x,0,z),(x+42,0,z),(x+42,6,z)],'rust')
        if detail:
            for z in range(-95,100,24):
                m.beam((-105,-2,z),(105,-2,z),.6,'steel')
                for x in range(-100,100,25):m.beam((x,-2,z),(x+25,3,z),.25,'steel')
    elif name=='warehouse-trusses':
        m.roof(230,135,6,'blue',12 if detail else 2,False)
        for z in range(-60,70,22 if detail else 120):
            m.beam((-114,-1,z),(114,-1,z),.6,'steel')
            if detail:
                for x in range(-100,100,25):m.beam((x,-1,z),(x+25,4*(1-abs(x)/115),z),.35,'steel')
    elif name=='passage-glass':
        m.roof(225,190,8,'glass',24 if detail else 6)
        for z in range(-95,96,19 if detail else 95):
            count=24 if detail else 6
            for i in range(count):m.beam((-112.5+225*i/count,8*math.sin(math.pi*i/count),z),(-112.5+225*(i+1)/count,8*math.sin(math.pi*(i+1)/count),z),.4,'ivory')
    elif name=='technical-rooftop':
        m.box((0,0,0),(95,1.2,150),'dark-concrete');m.box((-20,3,10),(30,5,45),'steel');m.cylinder((22,6,-30),10,11,'steel',24 if detail else 8)
        if detail:
            for z in range(-60,70,12):m.box((-20,5.7,z),(26,.25,.8),'black')
            for x in (-37,37):m.beam((x,1,-70),(x,1,70),1,'rust')
    elif name=='parking-ceiling':
        m.box((0,0,0),(260,1,110),'concrete')
        for x in range(-120,130,30 if detail else 240):m.box((x,-.7,0),(2,1.4,110),'concrete')
        if detail:
            for x in range(-105,115,30):m.box((x,-1.5,0),(8,.15,1),'light-white')
    elif name=='circus-canopy':
        n=32 if detail else 8
        for i in range(n):
            angle=i*math.tau/n;next_angle=(i+1)*math.tau/n;p=(math.cos(angle)*102.5,0,math.sin(angle)*90);q=(math.cos(next_angle)*102.5,0,math.sin(next_angle)*90);material='faded-red' if i%2 else 'ivory'
            m.face([(0,20,0),q,p],material);m.face([(0,19.4,0),(p[0],-.6,p[2]),(q[0],-.6,q[2])],material);m.face([p,q,(q[0],-.6,q[2]),(p[0],-.6,p[2])],material)
            if detail and i%2==0:m.beam(p,(0,19.6,0),.18,'steel')
        m.cylinder((0,23,0),.4,8,'yellow',8)
    elif name=='orbit-shell':
        m.roof(205,150,12,'purple',28 if detail else 6)
        if detail:
            for z in range(-75,76,15):
                for i in range(16):m.beam((-102.5+205*i/16,12*math.sin(math.pi*i/16)+.1,z),(-102.5+205*(i+1)/16,12*math.sin(math.pi*(i+1)/16)+.1,z),.35,'ivory')
    elif name=='depot-gantry':
        m.roof(180,130,5,'steel',12 if detail else 2,False)
        m.beam((-85,-2,-45),(85,-2,-45),1,'yellow');m.beam((-85,-2,45),(85,-2,45),1,'yellow')
        if detail:
            for z in range(-60,70,20):m.beam((-90,-1,z),(90,-1,z),.45,'steel')
            m.box((20,-4,0),(12,4,8),'yellow');m.beam((20,-6,0),(20,-11,0),.22,'steel')
    elif name=='cement-silo':
        m.cylinder((0,24,0),17,34,'ivory',36 if detail else 12);m.cylinder((0,44,0),17,6,'steel',36 if detail else 12,5);m.cylinder((0,4,0),7,6,'steel',24 if detail else 8,17)
        for x,z in [(-12,-12),(-12,12),(12,-12),(12,12)]:m.box((x,3,z),(1,6,1),'steel')
        if detail:
            for y in range(8,42,6):m.cylinder((0,y,0),17.18,.3,'steel',36)
            m.beam((18,5,0),(18,42,0),.5,'rust')
    elif name=='park-tree':
        m.cylinder((0,6,0),1,12,'wood',12 if detail else 6,.65)
        # Layered low-poly crown; distant form retains full outer envelope.
        for y,r,h in [(12,6,8),(17,5,8),(21,3,7)]:m.cylinder((0,y,0),r,h,'grass',20 if detail else 6,.2)
    else:raise ValueError(name)
    return m

NAMES=('unfinished-frame','plant-sawtooth','warehouse-trusses','passage-glass','technical-rooftop','parking-ceiling','circus-canopy','orbit-shell','depot-gantry','cement-silo','park-tree')
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    if '--blend' in sys.argv:
        import bpy
        for p in sorted(OUT.glob('*.glb')):
            bpy.ops.wm.read_factory_settings(use_empty=True);bpy.ops.import_scene.gltf(filepath=str(p));bpy.ops.wm.save_as_mainfile(filepath=str(p.with_suffix('.blend')))
    else:
        for name in NAMES:
            architectural(name).save(name);architectural(name,True).save(name+'-lod')
            print('Authored',name)
if __name__=='__main__':main()
