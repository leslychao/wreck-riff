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

def metres_per_tile(material):
    # Keep UV capture scale identical to SurfaceMaterials.metresPerTile, which
    # also authors the corresponding canonical solid and driving-surface UVs.
    if material in ('cast-concrete','park-paving'):return 1.8
    if material=='brick':return 1
    if material=='earth':return 1.3
    if material=='district-earth':return 2
    if material in ('grass','park-ground','district-garden'):return 2.51
    if material=='wood':return 1.5
    if material in ('road-surface','road-wet'):return 2
    if material in ('asphalt','concrete','road-patch'):return 4
    if material in ('rust','blue','steel','black'):return 3
    return 2

class Model:
    def __init__(self):self.materials=collections.defaultdict(list);self.members=[];self.solids=[]
    def face(self,points,material):
        for i in range(1,len(points)-1):
            a,b,c=points[0],points[i],points[i+1];u=[b[k]-a[k] for k in range(3)];v=[c[k]-a[k] for k in range(3)];n=[u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0]];length=math.sqrt(sum(x*x for x in n))
            if length<1e-8:continue
            n=[x/length for x in n];axis=max(range(3),key=lambda k:abs(n[k]));axes=[k for k in range(3) if k!=axis]
            scale=metres_per_tile(material)
            self.materials[material].extend((p,n,(p[axes[0]]/scale,p[axes[1]]/scale)) for p in (a,b,c))
    def box(self,c,s,material,closed_top=True):
        self.solids.append((c,s))
        x,y,z=c;w,h,d=[v/2 for v in s];p=[(x+dx*w,y+dy*h,z+dz*d) for dx,dy,dz in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]
        for face in [(0,3,2,1),(4,5,6,7),(0,4,7,3),(1,2,6,5),(0,1,5,4)]+([(3,7,6,2)] if closed_top else []):self.face([p[i] for i in face],material)
    def cylinder(self,c,r,h,material,segments=16,r2=None):
        r2=r if r2 is None else r2;x,y,z=c
        for i in range(segments):
            a=i*math.tau/segments;b=(i+1)*math.tau/segments;p=(x+r*math.cos(a),y-h/2,z+r*math.sin(a));q=(x+r*math.cos(b),y-h/2,z+r*math.sin(b));u=(x+r2*math.cos(a),y+h/2,z+r2*math.sin(a));v=(x+r2*math.cos(b),y+h/2,z+r2*math.sin(b))
            self.face([p,u,v,q],material);self.face([(x,y+h/2,z),v,u],material);self.face([(x,y-h/2,z),p,q],material)
    def beam(self,a,b,width,material,bearing=True,closed_start=True):
        if bearing:self.members.append((a,b,width))
        d=[b[k]-a[k] for k in range(3)];length=math.sqrt(sum(v*v for v in d));d=[v/length for v in d];up=[0,1,0] if abs(d[1])<.95 else [1,0,0];x=[d[1]*up[2]-d[2]*up[1],d[2]*up[0]-d[0]*up[2],d[0]*up[1]-d[1]*up[0]];l=math.sqrt(sum(v*v for v in x));x=[v/l*width/2 for v in x];y=[d[1]*x[2]-d[2]*x[1],d[2]*x[0]-d[0]*x[2],d[0]*x[1]-d[1]*x[0]]
        rings=[[tuple(p[k]+sx*x[k]+sy*y[k] for k in range(3)) for sx,sy in [(-1,-1),(1,-1),(1,1),(-1,1)]] for p in (a,b)]
        if closed_start:self.face(list(reversed(rings[0])),material)
        self.face(rings[1],material)
        for i in range(4):self.face([rings[0][i],rings[0][(i+1)%4],rings[1][(i+1)%4],rings[1][i]],material)
    def crown(self,c,r,material,segments=12,rings=6):
        # Lobed foliage volumes have proper closed faces and normals; the
        # original model's six-metre radial envelope is retained at both LODs.
        def p(row,col):
            lat=math.pi*row/rings;angle=math.tau*col/segments
            return (c[0]+r[0]*math.sin(lat)*math.cos(angle),c[1]+r[1]*math.cos(lat),c[2]+r[2]*math.sin(lat)*math.sin(angle))
        for row in range(rings):
            for col in range(segments):self.face([p(row,col),p(row,col+1),p(row+1,col+1),p(row+1,col)],material)
    def prism(self,profile,z0,z1,material):
        front=[(x,y,z1) for x,y in profile];back=[(x,y,z0) for x,y in profile]
        self.face(front,material);self.face(list(reversed(back)),material)
        for i in range(len(profile)):
            j=(i+1)%len(profile);self.face([back[i],back[j],front[j],front[i]],material)
    def gable(self,w,d,height,material):
        profile=[(-w/2,-.8),(w/2,-.8),(0,height-.8)]
        self.prism(profile,-d/2,-d/2+.3,material);self.prism(profile,d/2-.3,d/2,material)
    def truss(self,xs,top,bottom,z,width,material):
        upper=[(x,top(x),z) for x in xs];lower=[(x,bottom(x),z) for x in xs]
        for a,b in zip(upper,upper[1:]):self.beam(a,b,width,material)
        for a,b in zip(lower,lower[1:]):self.beam(a,b,width,material)
        for a,b in zip(lower,upper):self.beam(a,b,width*.7,material)
        for a,b in zip(lower,upper[1:]):self.beam(a,b,width*.6,material)
    def roof(self,w,d,height,material,segments=16,curve=True):
        profile=[(-w/2+w*i/segments,height*(math.sin(math.pi*i/segments) if curve else 1-abs(2*i/segments-1))) for i in range(segments+1)]
        for (x,y),(xx,yy) in zip(profile,profile[1:]):
            self.face([(x,y,-d/2),(x,y,d/2),(xx,yy,d/2),(xx,yy,-d/2)],material)
            self.face([(x,y-.8,d/2),(x,y-.8,-d/2),(xx,yy-.8,-d/2),(xx,yy-.8,d/2)],material)
            for z in (-d/2,d/2):self.face([(x,y,z),(xx,yy,z),(xx,yy-.8,z),(x,y-.8,z)] if z<0 else [(x,y-.8,z),(xx,yy-.8,z),(xx,yy,z),(x,y,z)],'steel')
        for x,y in (profile[0],profile[-1]):
            face=[(x,y,-d/2),(x,y-.8,-d/2),(x,y-.8,d/2),(x,y,d/2)]
            self.face(face if x<0 else list(reversed(face)),material)
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
        for c,s in [((0,.4,-53),(235,.8,24)),((0,.4,53),(235,.8,24)),((-105,.4,0),(25,.8,82)),((70,.4,0),(95,.8,82))]:m.box(c,s,'cast-concrete')
        for x in (-107.5,-27.5,92.5):
            m.box((x,-1.2,0),(2.5,2.4,130),'cast-concrete')
            if detail:
                for z in (-52,52):m.cylinder((x,2,z),.1,4,'rust',6)
    elif name=='plant-sawtooth':
        stations=[-100,-85,-50,0,50,85,100] if detail else [-100,-85,85,100]
        for i in range(5):
            x=-105+i*42
            profile=[(x,-.8),(x+42,-.8),(x+42,5.2)]
            m.face([(x,0,-100),(x,0,100),(x+42,6,100),(x+42,6,-100)],'steel')
            m.face([(x,-.8,100),(x,-.8,-100),(x+42,5.2,-100),(x+42,5.2,100)],'steel')
            # Closed ridge glazing and both end wedges, with inward faces too.
            # Glazing ends at the inner rust end wedges. Extending it through
            # them creates coincident outer faces with different contact finish.
            m.box((x+41.9,2.6,0),(.2,6.8,199.4),'glass')
            m.prism(profile,-100,-99.7,'rust');m.prism(profile,99.7,100,'rust')
            for z in stations:
                xs=[x+42*j/(4 if detail else 2) for j in range((4 if detail else 2)+1)]
                m.truss(xs,lambda xx,x=x:6*(xx-x)/42-.95,lambda xx:-2,z,.5,'steel')
        for x in (-105,105):m.beam((x,-2,-100),(x,-2,100),.9,'steel')
    elif name=='warehouse-trusses':
        m.roof(230,135,6,'blue',12 if detail else 2,False);m.gable(230,135,6,'blue')
        for x in (-114,114):
            m.beam((x,-2,-67.5),(x,-2,67.5),1.2,'steel')
            for z in (-57.5,67.5):m.box((x,-8,z),(1.4,14,1.4),'steel')
        stations=[-67.5,-40.5,-13.5,13.5,40.5,67.5] if detail else [-67.5,67.5]
        xs=[-114,-76,-38,0,38,76,114] if detail else [-114,0,114]
        for z in stations:m.truss(xs,lambda x:6*(1-abs(x)/115)-.95,lambda x:-2,z,.6,'steel')
    elif name=='passage-glass':
        m.roof(225,190,8,'glass',24 if detail else 6)
        # The wall-top clerestory closes the curve above the front walls while
        # every portal below eighteen metres keeps its original clearance.
        count=24 if detail else 6
        for z in (-90,90):
            for i in range(count):
                x=-112.5+225*i/count;xx=-112.5+225*(i+1)/count
                y=max(0,8*math.sin(math.pi*i/count)-.8);yy=max(0,8*math.sin(math.pi*(i+1)/count)-.8)
                profile=[(x,0),(xx,0),(xx,yy),(x,y)]
                m.prism(profile,z-.15,z+.15,'glass')
        for x in (-110,110):m.beam((x,-.8,-95),(x,-.8,95),1,'ivory')
        for x,z in [(-110,-80),(-110,30),(-110,80),(110,-90),(110,43)]:
            m.box((x,-9,z),(1.5,18,1.5),'ivory')
        for z in range(-95,96,19 if detail else 95):
            count=24 if detail else 6
            for i in range(count):
                x=-110+220*i/count;xx=-110+220*(i+1)/count
                m.beam((x,8*math.sin(math.pi*(x+112.5)/225)-.95,z),(xx,8*math.sin(math.pi*(xx+112.5)/225)-.95,z),.4,'ivory')
    elif name=='technical-rooftop':
        m.box((0,0,0),(95,1.2,150),'cast-concrete');m.box((-20,3,10),(30,5,45),'steel');m.cylinder((22,6,-30),10,11,'steel',24 if detail else 8)
        # North-east support sits inward from the existing passing boulevard.
        for x,z in [(42.5,-60),(27.5,55)]:m.box((x,-7,z),(2,14,2),'cast-concrete')
        if detail:
            for z in (-10,0,10,20,30):m.box((-20,5.6,z),(26,.25,.8),'black')
            for x in (-37,37):m.beam((x,1,-70),(x,1,70),1,'rust')
    elif name=='parking-ceiling':
        # Canonical floor shells provide the slab; this model owns exposed beams only.
        for x in range(-120,130,30 if detail else 240):m.box((x,-.7,0),(2,1.4,110),'cast-concrete')
        if detail:
            for x in range(-105,115,30):m.box((x,-1.5,0),(8,.15,1),'light-white')
    elif name=='circus-canopy':
        n=32 if detail else 8
        for i in range(n):
            angle=i*math.tau/n;next_angle=(i+1)*math.tau/n;p=(math.cos(angle)*102.5,0,math.sin(angle)*90);q=(math.cos(next_angle)*102.5,0,math.sin(next_angle)*90);material='faded-red' if i%2 else 'ivory'
            m.face([(0,20,0),q,p],material);m.face([(0,19.4,0),(p[0],-.6,p[2]),(q[0],-.6,q[2])],material);m.face([p,q,(q[0],-.6,q[2]),(p[0],-.6,p[2])],material)
            if detail and i%2==0:m.beam((p[0],-.45,p[2]),(0,19.4,0),.18,'steel')
        m.cylinder((0,23,0),.4,8,'yellow',8)
    elif name=='orbit-shell':
        m.roof(205,150,12,'purple',28 if detail else 6)
        profile=lambda x:12*math.sin(math.pi*(x+102.5)/205)
        xs=[-97.5+195*i/(16 if detail else 6) for i in range((16 if detail else 6)+1)]
        for x in (-97.5,97.5):m.beam((x,profile(x)-3.8,-75),(x,profile(x)-3.8,75),1.2,'ivory')
        for z in (range(-75,76,15) if detail else (-75,0,75)):
            m.truss(xs,lambda x:profile(x)-.95,lambda x:profile(x)-3.8,z,.5,'ivory')
    elif name=='depot-gantry':
        m.roof(180,130,5,'steel',12 if detail else 2,False);m.gable(180,130,5,'blue')
        # Four wall bearings carry the longitudinal crane runways, which in
        # turn carry both cross-girders. No girder ends stop in the open hall.
        for x in (-85,85):m.beam((x,-2,-65),(x,-2,65),1.2,'yellow')
        for z in (-65,-45,45,65):m.beam((-85,-2,z),(85,-2,z),1,'yellow')
        # The trolley spans the two cross-girders; the hoist hangs from it.
        m.beam((20,-2,-45),(20,-2,45),1.2,'yellow');m.box((20,-4,0),(12,4,8),'yellow');m.beam((20,-6,0),(20,-8,0),.22,'steel',bearing=False)
        xs=[-85,-42.5,0,42.5,85] if detail else [-85,0,85]
        for z in ([-65,-45,-15,15,45,65] if detail else [-65,65]):
            m.truss(xs,lambda x:5*(1-abs(x)/90)-.95,lambda x:-2,z,.45,'steel')
    elif name=='cement-silo':
        m.cylinder((0,24,0),17,34,'ivory',36 if detail else 12);m.cylinder((0,44,0),17,6,'steel',36 if detail else 12,5);m.cylinder((0,4,0),7,6,'steel',24 if detail else 8,17)
        for x,z in [(-12,-12),(-12,12),(12,-12),(12,12)]:m.box((x,3,z),(1,6,1),'steel')
        if detail:
            for y in range(8,42,6):m.cylinder((0,y,0),17.18,.3,'steel',36)
            m.beam((18,5,0),(18,42,0),.5,'rust')
    elif name=='park-tree':
        m.cylinder((0,6,0),1,12,'wood',12 if detail else 6,.65)
        for endpoint in [(-3.5,13,-1.5),(3.4,15,2),(1,20,-1),(-1.5,18,2.3)]:
            m.beam((0,7 if endpoint[1]<16 else 10,0),endpoint,.45,'wood')
        for centre,radii in [((0,12.5,0),(5.8,4.5,5.8)),((-2,17,-1),(3.6,4,3.6)),((2,19,1),(3.4,4,3.6)),((0,22,0),(3,2.5,3))]:
            m.crown(centre,radii,'park-leaf',12 if detail else 8,6 if detail else 4)
    elif name=='island-bandstand':
        count=16 if detail else 8
        for i in range(count):
            a=i*math.tau/count;b=(i+1)*math.tau/count;p=(30*math.cos(a),10,30*math.sin(a));q=(30*math.cos(b),10,30*math.sin(b))
            m.face([(0,18,0),q,p],'ivory' if i%2 else 'faded-red');m.face([(0,17.4,0),(p[0],9.4,p[2]),(q[0],9.4,q[2])],'wood')
            m.face([p,q,(q[0],9.4,q[2]),(p[0],9.4,p[2])],'wood')
        for i in range(8):
            a=i*math.tau/8;x,z=26*math.cos(a),26*math.sin(a);m.cylinder((x,5,z),.7,10,'ivory',12 if detail else 6)
            b=(i+1)*math.tau/8;m.beam((x,9.5,z),(26*math.cos(b),9.5,26*math.sin(b)),.8,'wood')
        if detail:
            for i in range(8):
                angle=i*math.tau/8;m.beam((0,17.3,0),(29*math.cos(angle),9.5,29*math.sin(angle)),.3,'steel')
    elif name=='comet-coaster':
        from author_campaign_arenas import carnival
        blueprint=next(item for item in carnival().models if item['id']==name)
        count=144 if detail else 72
        def p(angle,side=0,drop=0):
            x,y,z=140*math.cos(angle),8+28*math.sin(angle/2)**2,58*math.sin(angle)
            nx,nz=58*math.cos(angle),140*math.sin(angle);length=math.hypot(nx,nz)
            return (x+side*nx/length,y-drop,z+side*nz/length)
        # Twin rails, a lower spine and triangulated crossheads form a continuous
        # return circuit. The complete low section remains inside the station yard.
        for i in range(count):
            a=i*math.tau/count;b=(i+1)*math.tau/count
            for side in (-2.1,2.1):m.beam(p(a,side),p(b,side),.38,'faded-red')
            m.beam(p(a,0,1.5),p(b,0,1.5),.55,'steel')
            for side in (-2.1,2.1):m.beam(p(a,0,1.5),p(a,side),.22,'ivory')
            if detail:m.beam(p(a,-2.1),p(b,2.1),.16,'steel')
        for support in blueprint['supports']:
            a=support['angle'];x,y,z=p(a)
            for side in (-3,3):
                top=p(a,side,2);base=(top[0],.6,top[2])
                # The column bears on the footing top. Only the exterior union
                # is exported: no buried blue bottom or competing contact faces.
                m.beam(base,top,.8,'blue',closed_start=False)
                m.box((base[0],.3,base[2]),(2.1,.6,2.1),'cast-concrete',closed_top=False)
                for x1,x2,z1,z2 in [(-1.05,-.4,-1.05,1.05),(.4,1.05,-1.05,1.05),(-.4,.4,-1.05,-.4),(-.4,.4,.4,1.05)]:
                    m.face([(base[0]+x,.6,base[2]+z) for x,z in [(x1,z1),(x1,z2),(x2,z2),(x2,z1)]],'cast-concrete')
            m.beam(p(a,-3,2),p(a,3,2),1.15,'blue')
            m.beam(p(a,-3,2),p(a,3,y*.55),.35,'steel')
        # A covered boarding platform stands on four columns. Stair treads and
        # a service cabinet explain how the mechanism is reached and operated.
        m.box((139,6.75,0),(22,1.2,29),'wood')
        for x in (130,148):
            for z in (-12,12):
                m.box((x,3.1,z),(1.2,6.2,1.2),'cast-concrete');m.box((x,9.3,z),(.4,4,.4),'steel')
        # Move the station roof above its supports without introducing another
        # runtime object/physics owner. Geometry remains in this single model.
        roof=Model();roof.roof(24,30,2,'blue',8 if detail else 2,False)
        for material,vertices in roof.materials.items():
            for index in range(0,len(vertices),3):m.face([tuple(v[0][k]+(139,11.3,0)[k] for k in range(3)) for v in vertices[index:index+3]],material)
        for index in range(24):m.box((110.75+index*.75,(index+1)*.3/2,10),(.77,(index+1)*.3,4),'steel')
        m.box((137,1.25,-20),(4,2.5,3),'blue')
    else:raise ValueError(name)
    return m

NAMES=('unfinished-frame','plant-sawtooth','warehouse-trusses','passage-glass','technical-rooftop','parking-ceiling','circus-canopy','orbit-shell','depot-gantry','cement-silo','park-tree','island-bandstand','comet-coaster')
def main():
    OUT.mkdir(parents=True,exist_ok=True)
    names=NAMES if '--only' not in sys.argv else (sys.argv[sys.argv.index('--only')+1],)
    if any(name not in NAMES for name in names):raise ValueError('Unknown architecture selection: '+str(names))
    if '--blend' in sys.argv:
        import bpy
        for p in sorted(OUT/(name+suffix+'.glb') for name in names for suffix in ('','-lod')):
            bpy.ops.wm.read_factory_settings(use_empty=True);bpy.context.preferences.filepaths.save_version=0;bpy.ops.import_scene.gltf(filepath=str(p));bpy.ops.wm.save_as_mainfile(filepath=str(p.with_suffix('.blend')))
    else:
        for name in names:
            architectural(name).save(name);architectural(name,True).save(name+'-lod')
            print('Authored',name)
if __name__=='__main__':main()
