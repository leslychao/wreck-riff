"""Original offline revision-three location authoring. No runtime or network dependency.

Author roads before navigation; polygon subtraction joins road junctions and cuts
terrain under pavement. Earlier generators and outputs: assets/campaign-layouts-revision2.
"""
import collections
import json
import heapq
import math
import os
import tempfile
import time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'src/main/resources/config'
SOURCE=ROOT/'src/tools/assets/arena-revision3'
AMMO=('HOMING_AMMO','POWER_AMMO','NAPALM_AMMO','BALLISTIC_AMMO','CANNON_AMMO','MINE_AMMO')
AMMO_COUNTS={'construction_17':(18,18,12,8,10,6),'neon_zero':(24,24,16,10,14,8),'euphoria_park':(30,30,20,12,18,10)}

def write_text_atomic(path,text):
    """Publish complete generated files, including when a build reads alongside us."""
    path.parent.mkdir(parents=True,exist_ok=True)
    # Reproducible generation does not need to invalidate timestamps or replace
    # files currently held open by a Java resource reader on Windows.
    if path.exists() and path.read_bytes()==text.encode('utf-8'):return
    temporary=None
    try:
        with tempfile.NamedTemporaryFile(mode='w',encoding='utf-8',newline='\n',dir=path.parent,prefix=path.name+'.',suffix='.tmp',delete=False) as output:
            temporary=Path(output.name);output.write(text);output.flush();os.fsync(output.fileno())
        for attempt in range(6):
            try:
                os.replace(temporary,path);break
            except PermissionError:
                if attempt==5:raise
                time.sleep(.05*(2**attempt))
    finally:
        if temporary is not None and temporary.exists():temporary.unlink()
def vec(p):return dict(zip(('x','y','z'),(round(float(v),5) for v in p)))
def point(v):return tuple(v[k] for k in ('x','y','z'))
def dist(a,b):return math.sqrt(sum((a[i]-b[i])**2 for i in range(3)))
def area(p):return sum(a[0]*b[1]-b[0]*a[1] for a,b in zip(p,p[1:]+p[:1]))/2
def ccw(p):return p if area(p)>0 else list(reversed(p))
def rect(x1,x2,z1,z2):return [(x1,z1),(x2,z1),(x2,z2),(x1,z2)]
def inside(p,poly):
    x,z=p;hit=False
    for a,b in zip(poly,poly[1:]+poly[:1]):
        if (a[1]>z)!=(b[1]>z) and x<(b[0]-a[0])*(z-a[1])/(b[1]-a[1])+a[0]:hit=not hit
    return hit
def half(poly,a,b,positive=True):
    result=[]
    def side(p):return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])
    for p,q in zip(poly,poly[1:]+poly[:1]):
        dp,dq=side(p),side(q);ip=dp>=-1e-7 if positive else dp<=1e-7;iq=dq>=-1e-7 if positive else dq<=1e-7
        if ip:result.append(p)
        if ip!=iq and abs(dp-dq)>1e-10:
            t=dp/(dp-dq);result.append((p[0]+(q[0]-p[0])*t,p[1]+(q[1]-p[1])*t))
    clean=[]
    for p in result:
        if not clean or math.dist(p,clean[-1])>1e-5:clean.append(p)
    if len(clean)>1 and math.dist(clean[0],clean[-1])<1e-5:clean.pop()
    return clean if len(clean)>2 and abs(area(clean))>.001 else []
def subtract(poly,clip):
    remaining=poly;outside=[];clip=ccw(clip)
    for a,b in zip(clip,clip[1:]+clip[:1]):
        if not remaining:break
        part=half(remaining,a,b,False)
        if part:outside.append(part)
        remaining=half(remaining,a,b)
    return outside
def cut(polys,clips):
    for clip in clips:
        cx1,cx2=min(p[0] for p in clip),max(p[0] for p in clip);cz1,cz2=min(p[1] for p in clip),max(p[1] for p in clip);next_polys=[]
        for poly in polys:
            if max(p[0] for p in poly)<=cx1+1e-6 or min(p[0] for p in poly)>=cx2-1e-6 or max(p[1] for p in poly)<=cz1+1e-6 or min(p[1] for p in poly)>=cz2-1e-6:next_polys.append(poly)
            else:next_polys.extend(subtract(poly,clip))
        polys=next_polys
    return polys

class Location:
    def __init__(self,identity,title,theme,width,depth,enemies,boss):
        self.resource='arena-'+identity.replace('_','-');self.width=width;self.depth=depth
        self.data=dict(schemaVersion=4,id=identity,metadata=dict(title=title,theme=theme,normalEnemies=enemies,durationSeconds=0,recoveryCost=0,introduction='',music=f'audio/music/{identity}-normal.wav',bossMusic=f'audio/music/{identity}-boss.wav'),bounds=dict(minX=0,maxX=width,minZ=0,maxZ=depth,recoveryY=-45),boxes=[],ramps=[],spawns=[],pickups=[],hazards=[],nodes=[],edges=[],surfaces=[],launchPads=[],drops=[],destructibles=[],secrets=[],barriers=[],bosses=[boss],layoutRevision=3,meshes=[],districts=[],roads=[])
        self.points={};self.paths=[];self.node_ids={};self.footprints=[];self.holes=[];self.special=[];self.regions=[];self.district_specs=[];self.sites=[];self.models=[];self.fixed=[];self.deck_openings={};self.road_openings=None;self.trees=[]
    def box(self,name,center,size,material='concrete',collision=True,yaw=0):
        assert min(size)>0,(name,size)
        self.data['boxes'].append(dict(id=name,center=vec(center),size=vec(size),material=material,collision=collision,yawDegrees=yaw));return name
    def wall(self,name,a,b,height=10,thick=1.5,material='concrete',base=0):
        dx,dz=b[0]-a[0],b[1]-a[1]
        return self.box(name,((a[0]+b[0])/2,base+height/2,(a[1]+b[1])/2),(math.hypot(dx,dz),height,thick),material,yaw=-math.degrees(math.atan2(dz,dx)))
    def building(self,name,bounds,height,material='concrete',base=0):
        x1,x2,z1,z2=bounds;return self.box(name,((x1+x2)/2,base+height/2,(z1+z2)/2),(x2-x1,height,z2-z1),material)
    def roof(self,name,bounds,y,material='steel'):
        x1,x2,z1,z2=bounds;return self.box(name+'-roof',((x1+x2)/2,y+.6,(z1+z2)/2),(x2-x1,1.2,z2-z1),material)
    def mesh(self,name,polys,height=0,material='concrete',level=0,drive=True,additional=()):
        verts=[];indices=[];triangle_materials=[]
        for layer,tag in [(polys,material),*additional]:
            for poly in layer:
                if len(poly)<3:continue
                poly=ccw(poly);base=len(verts);verts.extend(vec((x,height(x,z) if callable(height) else height,z)) for x,z in poly)
                for i in range(1,len(poly)-1):
                    if abs(area([poly[0],poly[i],poly[i+1]]))>.0001:indices.extend((base,base+i+1,base+i));triangle_materials.append(tag)
        if not indices:return None
        self.data['meshes'].append(dict(id=name,vertices=verts,indices=indices,material=material,collision=drive,triangleMaterials=triangle_materials if len(set(triangle_materials))>1 else [],thickness=0))
        if drive:self.data['surfaces'].append(dict(id=name,geometryId=name,level=level,grip=1))
        return name
    def surface_at(self,p):
        for mesh in self.data['meshes']:
            if not mesh['collision']:continue
            v=mesh['vertices']
            for i in range(0,len(mesh['indices']),3):
                a,b,c=(v[mesh['indices'][i+j]] for j in range(3));den=(b['z']-c['z'])*(a['x']-c['x'])+(c['x']-b['x'])*(a['z']-c['z'])
                if abs(den)<1e-9:continue
                u=((b['z']-c['z'])*(p[0]-c['x'])+(c['x']-b['x'])*(p[2]-c['z']))/den;w=((c['z']-a['z'])*(p[0]-c['x'])+(a['x']-c['x'])*(p[2]-c['z']))/den
                if u>=-.00001 and w>=-.00001 and u+w<=1.00001 and abs(p[1]-u*a['y']-w*b['y']-(1-u-w)*c['y'])<.03:return mesh['id']
        raise ValueError(f'{self.data["id"]}: unsupported {p}')
    def node(self,p):
        key=tuple(round(v,4) for v in p)
        if key not in self.node_ids:
            identity=len(self.data['nodes']);self.node_ids[key]=identity;self.data['nodes'].append(dict(id=identity,position=vec(p),surfaceId=self.surface_at(p)))
        return self.node_ids[key]
    def road(self,name,names,width=22,material='road-surface',kind='ROAD',object_id='',draw=True):
        self.paths.append(dict(id=name,names=names.split(),width=width,material=material,kind=kind,objectId=object_id,draw=draw))
    def district(self,identity,title,center,boundary):self.district_specs.append((identity,title,center,boundary))
    def site(self,identity,title,function,route,bounds,kind):self.sites.append(dict(id=identity,title=title,function=function,route=route,bounds=bounds,kind=kind))
    def spawn(self,names):
        for i,name in enumerate(names.split()):self.data['spawns'].append(dict(id=i,position=vec(self.points[name]),yawDegrees=90 if i%2==0 else -90))
    def hazard(self,name,kind,p,size=(22,6,22),sector=None):
        x,y,z=p;w,h,d=size;self.data['hazards'].append(dict(id=name,type=kind,sector=sector or name,minX=x-w/2,maxX=x+w/2,minZ=z-d/2,maxZ=z+d/2,minY=y-.1,maxY=y+h,offTicks=1680,warningTicks=360,activeTicks=360,damageIntervalTicks=120,damage=120 if kind=='CRANE' else 90))
    def opening(self,name,a,b,barrier=False):
        p,q=self.points[a],self.points[b];dx,dz=q[0]-p[0],q[2]-p[2]
        geometry=self.box(name+'-panel',((p[0]+q[0])/2,p[1]+2,(p[2]+q[2])/2),(2,4,20),'yellow' if barrier else 'rust',not barrier,-math.degrees(math.atan2(dz,dx)))
        if barrier:self.data['barriers'].append(dict(id=name,geometryId=geometry,sector=name,offTicks=1680,warningTicks=180,activeTicks=480))
        else:self.data['destructibles'].append(dict(id=name,geometryId=geometry,maximumHp=25))
        self.road(name,a+' '+b,22,kind='OPENABLE',object_id=name)
    def launch(self,name,a,b,flight=2.6):self.special.append((name,a,b,flight))
    def landscape_clear(self,x,z,radius=8,ignore=()):
        for path in self.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.points[first],self.points[last];dx,dz=b[0]-a[0],b[2]-a[2];t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
                if math.hypot(x-a[0]-t*dx,z-a[2]-t*dz)<path['width']/2+radius:return False
        if any(inside((x,z),hole) for hole in self.holes):return False
        for box in self.data['boxes']:
            if box['id'] in ignore:continue
            c,s=box['center'],box['size']
            if c['y']+s['y']/2<.1:continue
            yaw=math.radians(box['yawDegrees']);dx,dz=x-c['x'],z-c['z']
            if abs(math.cos(yaw)*dx-math.sin(yaw)*dz)<s['x']/2+radius and abs(math.sin(yaw)*dx+math.cos(yaw)*dz)<s['z']/2+radius:return False
        return True
    def grove(self,name,bounds,spacing=34):
        x1,x2,z1,z2=bounds;self.site(name,name,'Озеленённый участок с группами деревьев','Посадки отступают от автомобильных проездов и служебных площадок',bounds,'landscape')
        for row,z in enumerate(range(z1+12,z2-10,spacing)):
            for col,x in enumerate(range(x1+12,x2-10,spacing)):
                xx=x+math.sin(row*2.3+col*.7)*5;zz=z+math.cos(col*1.9+row)*5
                if not self.landscape_clear(xx,zz,10):continue
                scale=.7+.12*((row+col)%4);identity=f'{name}-tree-{row}-{col}'
                self.box(identity,(xx,6*scale,zz),(2*scale,12*scale,2*scale),'wood',False);self.trees.append(dict(id=identity,position=(xx,0,zz),scale=scale))
    def boundaries(self):
        theme=self.data['metadata']['theme']
        if theme=='CARNIVAL':
            banks=[[(0,0),(35,35),(35,self.depth-35),(0,self.depth)],[(self.width,0),(self.width,self.depth),(self.width-35,self.depth-35),(self.width-35,35)],[(0,0),(self.width,0),(self.width-35,35),(35,35)],[(0,self.depth),(35,self.depth-35),(self.width-35,self.depth-35),(self.width,self.depth)]]
            for side,bank in enumerate(banks):
                self.holes.append(bank)
                for start in range(0,self.depth if side<2 else self.width,160):
                    clip=rect(0,self.width,start,min(start+160,self.depth)) if side<2 else rect(start,min(start+160,self.width),0,self.depth)
                    piece=ccw(bank)
                    for aa,bb in zip(clip,clip[1:]+clip[:1]):piece=half(piece,aa,bb) if piece else []
                    if piece:self.mesh(f'woodland-bank-{side}-{start}',[piece],lambda x,z:8*(1-min(x,self.width-x,z,self.depth-z)/35),'grass')
            # Low boundary core is buried under the sloping wooded bank.
            for side in range(4):
                if side<2:self.box(f'woodland-core-{side}',(0 if side==0 else self.width,3,self.depth/2),(2,6,self.depth),'earth')
                else:self.box(f'woodland-core-{side}',(self.width/2,3,0 if side==2 else self.depth),(self.width,6,2),'earth')
            return
        for side in range(4):
            length=self.depth if side<2 else self.width
            for start in range(0,length,100):
                end=min(length,start+100);mid=(start+end)/2;h=(22+(start//100)%4*11) if theme=='NEON' else 3+(start//100)%3*.5
                mat=('brick' if (start//100)%2 else 'dark-concrete') if theme=='NEON' else 'rust';depth=60 if theme=='NEON' else 2
                if side<2:self.box(f'edge-{side}-{start}',(0 if side==0 else self.width,h/2,mid),(depth,h,end-start),mat)
                else:self.box(f'edge-{side}-{start}',(mid,h/2,0 if side==2 else self.depth),(end-start,h,depth),mat)
    def compile_geometry(self):
        claimed={};ground_clips=list(self.holes)
        for path in self.paths:
            path['geometryIds']=[]
            if not path['draw']:continue
            for segment,(first,last) in enumerate(zip(path['names'],path['names'][1:])):
                a,b=self.points[first],self.points[last];dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz);sx,sz=dz/length*path['width']/2,-dx/length*path['width']/2
                poly=ccw([(a[0]+sx,a[2]+sz),(b[0]+sx,b[2]+sz),(b[0]-sx,b[2]-sz),(a[0]-sx,a[2]-sz)])
                flat=abs(a[1]-b[1])<.0001;bucket=round(a[1],3) if flat else None;pieces=cut([poly],claimed.get(bucket,[])+self.deck_openings.get(bucket,[])+((self.holes if self.road_openings is None else self.road_openings) if bucket==0 else [])) if flat else [poly]
                if flat:claimed.setdefault(bucket,[]).append(poly)
                if flat and abs(a[1])<.0001:ground_clips.append(poly)
                height=lambda x,z,a=a,b=b,dx=dx,dz=dz,length=length:a[1]+(b[1]-a[1])*((x-a[0])*dx+(z-a[2])*dz)/(length*length)
                level=0 if a[1]==b[1]==0 else -1 if min(a[1],b[1])<0 else 1
                paint=[];paint_pieces=[]
                # Paint occupies its own triangles in the road surface. It is not
                # a decal sheet. Junction approaches and park promenades stay clear.
                marked=flat and length>48 and self.data['metadata']['theme']!='CARNIVAL' and path['width']>=26 and path['kind']=='ROAD' and not path['id'].startswith('pit-')
                if marked:
                    ux,uz=dx/length,dz/length;vx,vz=dz/length,-dx/length
                    for t in range(22,int(length)-25,22):
                        cx,cz=a[0]+ux*t,a[2]+uz*t
                        paint.append(ccw([(cx-ux*4-vx*.16,cz-uz*4-vz*.16),(cx+ux*4-vx*.16,cz+uz*4-vz*.16),(cx+ux*4+vx*.16,cz+uz*4+vz*.16),(cx-ux*4+vx*.16,cz-uz*4+vz*.16)]))
                    for marking in paint:
                        for piece in pieces:
                            clipped=piece
                            for v,w in zip(marking,marking[1:]+marking[:1]):clipped=half(clipped,v,w) if clipped else []
                            if clipped:paint_pieces.append(clipped)
                    pieces=cut(pieces,paint)
                identity=self.mesh(f'road-{path["id"]}-{segment}',pieces,height,path['material'],level,additional=[(paint_pieces,'road-marking')] if paint_pieces else [])
                if identity:
                    path['geometryIds'].append(identity)
                    if max(a[1],b[1])>0 and any(word in path['id'] for word in ('bridge','express','interchange','deck-service','island-')):self.data['meshes'][-1]['thickness']=1.2
                self.footprints.append((poly,a,b,path))
        for name,poly,y,material,level in self.fixed:
            pieces=cut([poly],claimed.get(y,[])+self.deck_openings.get(y,[]));self.mesh(name,pieces,y,material,level)
            if name=='pit-floor':next(s for s in self.data['surfaces'] if s['geometryId']==name)['grip']=.8
            if name in ('island','gantry-landing-apron'):self.data['meshes'][-1]['thickness']=1.2
        for x in range(0,self.width,160):
            for z in range(0,self.depth,160):
                pieces=cut([rect(x,min(x+160,self.width),z,min(z+160,self.depth))],ground_clips)
                mat='district-earth' if self.data['metadata']['theme']=='CONSTRUCTION' else 'district-garden' if self.data['metadata']['theme']=='CARNIVAL' else 'district-slate'
                for index,(region,material) in enumerate(self.regions):
                    intersections=[]
                    for poly in pieces:
                        q=poly
                        for a,b in zip(ccw(region),ccw(region)[1:]+ccw(region)[:1]):q=half(q,a,b) if q else []
                        if q:intersections.append(q)
                    self.mesh(f'ground-{x}-{z}-region{index}',intersections,0,material);pieces=cut(pieces,[region])
                self.mesh(f'ground-{x}-{z}',pieces,0,mat)
    def compile_nav(self):
        for path in self.paths:
            ids=[]
            for segment,(first,last) in enumerate(zip(path['names'],path['names'][1:])):
                a,b=self.points[first],self.points[last];count=1 if path['kind']=='OPENABLE' else max(1,math.ceil(dist(a,b)/45));before=self.node(a);ids.append(before)
                for n in range(1,count+1):
                    after=self.node(tuple(a[k]+(b[k]-a[k])*n/count for k in range(3)));self.data['edges'].append(dict(id=f'{path["id"]}-{segment}-{n}',**{'from':before},to=after,width=path['width'],clearance=100,type=path['kind'],objectId=path['objectId'] or (f'road-{path["id"]}-{segment}' if path['kind']=='RAMP' else ''),bidirectional=True));before=after;ids.append(after)
            self.data['roads'].append(dict(id=path['id'],title=path['id'].replace('-',' '),width=path['width'],geometryIds=path['geometryIds'] or list(dict.fromkeys(self.data['nodes'][i]['surfaceId'] for i in ids)),navNodeIds=list(dict.fromkeys(ids))))
        for identity,title,center,boundary in self.district_specs:
            ids=[n['id'] for n in self.data['nodes'] if inside((n['position']['x'],n['position']['z']),boundary)]
            self.data['districts'].append(dict(id=identity,title=title,center=vec(center),patrolNodeIds=ids,boundary=[vec((x,0,z)) for x,z in boundary]))
        for name,a,b,flight in self.special:
            s,t=self.points[a],self.points[b];self.data['launchPads'].append(dict(id=name,source=vec(s),target=vec(t),sourceSurfaceId=self.surface_at(s),landingSurfaceId=self.surface_at(t),length=12,width=12,maximumEntryAngle=55,minimumSpeed=8,compressionTicks=12,flightSeconds=flight,rearmTicks=120));self.data['edges'].append(dict(id=name,**{'from':self.node(s)},to=self.node(t),width=12,clearance=80,type='LAUNCH',objectId=name,bidirectional=False))
    def clear(self,p,r=4):
        dynamic={x['geometryId'] for x in self.data['destructibles']+self.data['barriers']}
        for b in self.data['boxes']:
            if not b['collision'] or b['id'] in dynamic:continue
            c,s=b['center'],b['size'];top,bottom=c['y']+s['y']/2,c['y']-s['y']/2
            if top<=p[1]+.65 or bottom>=p[1]+6:continue
            yaw=math.radians(b['yawDegrees']);dx,dz=p[0]-c['x'],p[2]-c['z']
            if abs(math.cos(yaw)*dx-math.sin(yaw)*dz)<s['x']/2+r and abs(math.sin(yaw)*dx+math.cos(yaw)*dz)<s['z']/2+r:return False
        return True
    def supplies(self,counts):
        remaining=dict(zip(AMMO,counts));positions=[];spawns=[point(s['position']) for s in self.data['spawns']];nodes={n['id']:point(n['position']) for n in self.data['nodes']};candidates=[]
        for edge in self.data['edges']:
            if edge['type']!='ROAD':continue
            a,b=nodes[edge['from']],nodes[edge['to']]
            if abs(a[1]-b[1])>.01:continue
            for t in (.22,.55,.82):
                p=tuple(a[k]+(b[k]-a[k])*t for k in range(3))
                if min(dist(p,q) for q in spawns)>=60 and self.clear(p,5):candidates.append(p)
        adjacency={n:[] for n in nodes}
        for edge in self.data['edges']:
            if edge['type'] not in ('ROAD','RAMP'):continue
            u,v=edge['from'],edge['to'];length=dist(nodes[u],nodes[v]);adjacency[u].append((v,length))
            if edge['bidirectional']:adjacency[v].append((u,length))
        nearest={p:min(nodes,key=lambda n:dist(p,nodes[n])) for p in candidates}
        access=[]
        for spawn in spawns:
            origin=min(nodes,key=lambda n:dist(spawn,nodes[n]));cost={origin:0};first={origin:origin};queue=[(0,origin)]
            while queue:
                value,node=heapq.heappop(queue)
                if value>cost[node]+1e-7:continue
                for other,length in adjacency[node]:
                    next_cost=value+length
                    if next_cost+1e-7<cost.get(other,float('inf')):
                        cost[other]=next_cost;first[other]=other if node==origin else first[node];heapq.heappush(queue,(next_cost,other))
            def direction(p):
                target=p if nearest[p]==origin else nodes[first[nearest[p]]]
                dx,dz=target[0]-spawn[0],target[2]-spawn[2];length=math.hypot(dx,dz)
                return (dx/length,dz/length)
            access.append({p:(cost[nearest[p]]+dist(p,nodes[nearest[p]]),direction(p)) for p in candidates if nearest[p] in cost})
        def add(kind,p,label):
            if kind in remaining and remaining[kind]<=0 or any(dist(p,q)<17 for q in positions) or not self.clear(p,5):return False
            self.surface_at(p);positions.append(p);self.data['pickups'].append(dict(id=f'{label}-{len(positions):03}',type=kind,position=vec(p),respawnTicks=(40 if kind=='REPAIR' else 30 if kind=='TURBO_CELL' else 25)*120))
            if kind in remaining:remaining[kind]-=1
            return True
        for i,spawn in enumerate(spawns):
            selected=[]
            for p in sorted((p for p in candidates if abs(p[1]-spawn[1])<.1 and p in access[i] and access[i][p][0]<=180),key=lambda p:access[i][p][0]):
                distance=dist(spawn,p)
                if distance<60:continue
                if selected:
                    a,b=access[i][selected[0]][1],access[i][p][1]
                    if sum(a[k]*b[k] for k in (0,1))>.95:continue
                kind=('HOMING_AMMO','POWER_AMMO','CANNON_AMMO')[(i+len(selected))%3]
                if remaining[kind]<=0:kind=max(('HOMING_AMMO','POWER_AMMO'),key=lambda k:remaining[k])
                if add(kind,p,'start-cache'):selected.append(p)
                if len(selected)==2:break
            assert len(selected)==2,(self.data['id'],'start supplies',i)
        for district in self.data['districts']:
            boundary=[(p['x'],p['z']) for p in district['boundary']];centre=point(district['center']);local=sorted((p for p in candidates if inside((p[0],p[2]),boundary)),key=lambda p:dist(p,centre))
            for kind in (*AMMO,'REPAIR','TURBO_CELL'):
                if any(q['type']==kind and inside((q['position']['x'],q['position']['z']),boundary) for q in self.data['pickups']):continue
                assert any(add(kind,p,district['id']+'-cache') for p in local),(self.data['id'],district['id'],kind)
        for boss in self.data['bosses']:
            for entrance in boss['entrances']:
                start=point(entrance['position'])
                for weapon in (boss['primary'],boss['secondary']):
                    kind=weapon+'_AMMO'
                    if any(p['type']==kind and abs(p['position']['y']-start[1])<.1 and dist(start,point(p['position']))<150 for p in self.data['pickups']):continue
                    assert any(add(kind,p,'boss-supply') for p in sorted((p for p in candidates if abs(p[1]-start[1])<.1),key=lambda q:dist(start,q))),(self.data['id'],kind)
        for kind in AMMO:
            while remaining[kind]>0:
                possible=[p for p in candidates if all(dist(p,q)>=17 for q in positions)];assert possible,(self.data['id'],'insufficient sockets',remaining)
                assert add(kind,max(possible,key=lambda p:min(dist(p,q) for q in positions)),'route-cache')
        start_access=[]
        for i,spawn in enumerate(spawns):
            options=[]
            for pickup in self.data['pickups']:
                if pickup['type'] not in ('HOMING_AMMO','POWER_AMMO','CANNON_AMMO'):continue
                p=min(candidates,key=lambda p:dist(p,point(pickup['position'])))
                if abs(p[1]-spawn[1])<.1 and p in access[i]:options.append((access[i][p][0],pickup['id'],access[i][p][1]))
            options.sort();closest=options[:2]
            if i>0:
                dx,dz=(sum(item[2][k] for item in closest) for k in (0,1))
                if math.hypot(dx,dz)<.1:dx,dz=-closest[0][2][1],closest[0][2][0]
                self.data['spawns'][i]['yawDegrees']=round(math.degrees(math.atan2(dx,dz)),4)
            start_access.append(dict(spawnId=i,pickups=[dict(id=row[1],routeMeters=round(row[0],3),initialDirection=vec((row[2][0],0,row[2][1]))) for row in closest]))
        self.supply_report=dict(ammunitionCounts=dict(zip(AMMO,counts)),totalAmmunition=sum(counts),spawns=len(spawns),startAccess=start_access)
    def validate(self):
        nodes={n['id']:point(n['position']) for n in self.data['nodes']};dynamic={x['geometryId'] for x in self.data['destructibles']+self.data['barriers']};reached={0}
        while True:
            before=len(reached)
            for e in self.data['edges']:
                if e['type']=='OPENABLE':continue
                if e['from'] in reached:reached.add(e['to'])
                if e['bidirectional'] and e['to'] in reached:reached.add(e['from'])
            if len(reached)==before:break
        assert len(reached)==len(nodes),(self.data['id'],'disconnected',set(nodes)-reached)
        for e in self.data['edges']:
            if e['type'] in ('LAUNCH','OPENABLE'):continue
            a,b=nodes[e['from']],nodes[e['to']];clearance=100
            for step in range(math.ceil(dist(a,b)/4)+1):
                t=step/max(1,math.ceil(dist(a,b)/4));p=tuple(a[k]+(b[k]-a[k])*t for k in range(3));self.surface_at(p)
                for box in self.data['boxes']:
                    if not box['collision'] or box['id'] in dynamic:continue
                    c,s=box['center'],box['size'];top,bottom=c['y']+s['y']/2,c['y']-s['y']/2
                    if top<=p[1]+.65:continue
                    yaw=math.radians(box['yawDegrees']);dx,dz=p[0]-c['x'],p[2]-c['z']
                    if abs(math.cos(yaw)*dx-math.sin(yaw)*dz)>s['x']/2+2.5 or abs(math.sin(yaw)*dx+math.cos(yaw)*dz)>s['z']/2+2.5:continue
                    assert bottom-p[1]>=5,(self.data['id'],'blocked',e['id'],box['id'],p)
                    clearance=min(clearance,bottom-p[1])
            e['clearance']=round(clearance,3)
    def compile(self,counts=None):
        """Compile a fresh authored location without reading or writing generated files."""
        if self.data['nodes']:raise ValueError('Compile a fresh Location, not previously generated geometry')
        self.boundaries();self.compile_geometry();self.compile_nav();self.validate();self.supplies(counts or AMMO_COUNTS[self.data['id']])
        # The authored model replaces these metadata proxies in both rendering and
        # the single PhysicsSpace. Keep authoring clearance conservative beforehand.
        hero_roofs={'unfinished-apartments-roof','concrete-plant-roof','warehouse-roof','shopping-passage-roof','technical-complex-roof','parking-ground-floor-roof','circus-roof','ride-pavilion-roof','repair-depot-roof'}
        for box in self.data['boxes']:
            if box['id'] in hero_roofs:box['collision']=False
        return self.data
    def write_design(self):
        SOURCE.mkdir(parents=True,exist_ok=True)
        write_text_atomic(SOURCE/(self.data['id']+'-design.json'),json.dumps(dict(sites=self.sites,districts=self.data['districts'],roads=self.data['roads'],supply=self.supply_report,trees=self.trees),ensure_ascii=False,indent=2)+'\n')
    def finish(self,counts=None):
        self.compile(counts)
        write_text_atomic(OUT/(self.resource+'.json'),json.dumps(self.data,ensure_ascii=False,indent=2)+'\n')
        self.write_design()
        print(self.data['id'],len(self.data['boxes']),'solids',len(self.data['meshes']),'meshes',len(self.data['nodes']),'nodes',len(self.data['pickups']),'pickups')

def boss(identity,name,hp,weapons,entrances,quotes,pads=()):
    return dict(id=identity,name=name,profileId=identity,maximumHp=hp,primary=weapons[0],secondary=weapons[1],entrances=[dict(id=i,position=vec(p),yawDegrees=90) for i,p in enumerate(entrances)],launchPadIds=list(pads),quotes=quotes)

def construction():
    a=Location('construction_17','МЕГАСТРОЙ-17','CONSTRUCTION',1600,1200,8,boss('boss_foreman','Бригадир',4000,('POWER','CANNON'),[(1420,0,780),(170,0,250)],['Объект закрыт. Посторонних — под отвал','Сейчас выровняем!']))
    a.data['metadata']['introduction']='Рабочая стройка: котлован, корпуса на разных стадиях, производство бетона, склады и монтаж развязки.'
    a.holes=[rect(280,650,330,730)]
    a.fixed=[('pit-floor',rect(365,565,415,645),-14,'district-earth',-1),('gantry-landing-apron',rect(1095,1215,965,1050),18,'concrete',1)]
    for name,poly,height in [('west',[(280,330),(365,415),(365,645),(280,730)],lambda x,z:(x-280)*-14/85),
        ('east',[(565,415),(650,330),(650,730),(565,645)],lambda x,z:(650-x)*-14/85),
        ('south',[(280,330),(650,330),(565,415),(365,415)],lambda x,z:(z-330)*-14/85),
        ('north',[(365,645),(565,645),(650,730),(280,730)],lambda x,z:(730-z)*-14/85)]:a.mesh('pit-'+name+'-slope',[poly],height,'district-earth',-1)
    p=dict(sw=(170,0,140),south=(720,0,190),warehouse_w=(1070,0,240),warehouse_in=(1180,0,240),warehouse_turn=(1260,0,240),warehouse_out=(1260,0,345),warehouse_e=(1420,0,240),east=(1420,0,500),plant_e=(1420,0,780),ne=(1420,0,1100),north=(830,0,1100),nw=(170,0,1060),west=(170,0,570),pit_w=(280,0,530),pit_wb=(365,-14,530),pit=(465,-14,530),pit_eb=(565,-14,530),pit_e=(650,0,530),pit_sb=(465,-14,415),pit_s=(465,0,330),pit_nb=(465,-14,645),pit_n=(465,0,730),frame_w=(310,0,860),frame_in=(410,0,860),frame_turn=(510,0,860),frame_out=(510,0,1000),frame_e=(710,0,920),frame_s=(680,0,780),spine=(830,0,600),plant_w=(970,0,600),plant_in=(1070,0,600),plant_turn=(1160,0,600),plant_n=(1160,0,780),plant_s=(1070,0,450),plant_bypass=(1280,0,600),silos_s=(930,0,370),ramp_s=(1000,0,820),deck_s=(1140,18,920),deck_c=(1260,18,1000),deck_n=(1360,18,1100),ramp_n=(1510,0,1150),under=(1220,0,950),gate_w=(820,0,360),gate_e=(950,0,360),jump=(1000,0,930),landing=(1140,18,1010))
    a.points=p
    for name,path,width in [('arrival-haul','sw south warehouse_w warehouse_in warehouse_turn warehouse_e east plant_e ne',30),('west-haul','sw west nw north ne',28),('pit-west','west pit_w',26),('pit-bottom','pit_wb pit pit_eb',30),('pit-cross','pit_sb pit pit_nb',28),('pit-south-approach','south pit_s',26),('pit-north-approach','pit_n frame_s frame_e north',24),('pit-plant','pit_e spine plant_w plant_in plant_turn plant_bypass east',28),('frame-courtyard','nw frame_w frame_in frame_turn frame_out north',22),('frame-service','frame_w west',24),('frame-east','frame_turn frame_s spine',22),('frame-north','frame_out frame_e',24),('factory-dispatch','plant_turn plant_n plant_e',26),('factory-loading','plant_in plant_s silos_s warehouse_w',24),('factory-bypass','plant_n plant_bypass warehouse_out warehouse_e',26),('warehouse-north-dock','warehouse_turn warehouse_out plant_s',24),('central-haul','south gate_w spine',28),('interchange-underpass','spine ramp_s under plant_e',28),('north-maintenance','under north',24),('east-maintenance','ne ramp_n',22),('jump-runup','frame_e jump ramp_s',22),('deck-service','deck_s landing deck_c',36),('cut-west-approach','gate_w south',22),('cut-east-approach','gate_e silos_s',22)]:a.road(name,path,width)
    for name,first,last in [('west','pit_w','pit_wb'),('east','pit_eb','pit_e'),('south','pit_s','pit_sb'),('north','pit_nb','pit_n')]:a.road('pit-slope-'+name,first+' '+last,32,kind='RAMP',object_id='pit-'+name+'-slope',draw=False)
    a.road('interchange-rise','ramp_s deck_s',34,'concrete','RAMP');a.road('interchange-span','deck_s deck_c deck_n',34,'concrete');a.road('interchange-descent','deck_n ramp_n',34,'concrete','RAMP')
    a.opening('warehouse-service-gate','gate_w','gate_e');a.launch('construction-gantry-launch','jump','landing',3.2)
    # Unfinished L-shaped frame: open bays, two connected courts, staggered upper wings.
    a.site('unfinished-apartments','Корпус 17Б','Каркас жилого корпуса и монтажный двор','Западный въезд → открытый каркас → северный двор',[355,610,815,980],'through-interior')
    a.roof('unfinished-apartments',(360,595,820,950),16,'concrete');a.roof('unfinished-east-wing',(555,670,900,1050),29,'concrete')
    for x,z in [(370,830),(450,830),(570,810),(370,925),(450,925),(570,925),(610,940),(650,1025)]:a.box(f'frame-column-{x}-{z}',(x,8,z),(2.5,16,2.5))
    
    for x,z in [(560,910),(660,910),(560,1035),(660,1035)]:a.box(f'frame-wing-column-{x}-{z}',(x,14.5,z),(3,29,3),'concrete')
    a.building('unfinished-stair-core',(550,575,870,910),42);a.building('finished-shell-west',(70,120,815,980),35,'ivory');a.building('foundation-next-phase',(235,285,1100,1150),1.2)
    a.building('cladding-in-progress',(675,735,990,1045),48,'ivory');a.wall('frame-courtyard-wall',(355,960),(455,960),7)
    # Concrete processing: offset loading/discharge legs around mixer core, side escape.
    a.site('concrete-plant','Бетонный завод','Силосы питают смеситель, грузовой двор принимает готовый бетон','Западная загрузка → производственный зал → северная выдача; восточный обход',[995,1210,500,710],'through-interior')
    a.roof('concrete-plant',(1000,1210,505,705),23,'rust')
    a.wall('plant-south-wall',(1000,510),(1040,510),22,2,'rust');a.wall('plant-south-wing',(1100,510),(1210,510),22,2,'rust');a.wall('plant-north-wall',(1000,700),(1125,700),22,2,'rust');a.wall('plant-north-wing',(1195,680),(1205,680),22,2,'rust')
    a.building('plant-mixer-core',(1105,1135,530,565),31,'steel');a.building('plant-control-office',(1000,1040,650,685),12,'blue')
    for i,(x,z) in enumerate([(1020,370),(1130,375),(1350,440)]):a.building('silo-base-'+str(i),(x-17,x+17,z-17,z+17),3,'concrete')
    for x,z in [(1010,520),(1200,520),(1010,690),(1200,690)]:a.box(f'plant-column-{x}-{z}',(x,11.5,z),(3,23,3),'steel')
    
    for x,z in [(1050,520),(1100,520),(1150,520),(1080,690)]:a.box(f'plant-bay-column-{x}-{z}',(x,11.5,z),(2.5,23,2.5),'steel')
    a.wall('aggregate-bin-a',(870,410),(960,410),5,3);a.wall('aggregate-bin-b',(870,460),(960,460),5,3);a.wall('aggregate-bin-back',(870,410),(870,460),5,3)
    # Warehouse runs east-west then turns through north dock; racking defines a broad L.
    a.site('warehouse','Склад комплектации','Приём панелей и выдача деталей на стройку','Западные ворота → зона комплектации → северные доки',[1120,1350,180,315],'through-interior')
    a.roof('warehouse',(1120,1350,180,315),15,'blue');a.wall('warehouse-south-wall',(1120,180),(1350,180),15,2,'blue');a.wall('warehouse-north-west',(1120,315),(1225,315),15,2,'blue');a.wall('warehouse-north-east',(1345,315),(1350,315),15,2,'blue')
    for i,x in enumerate((1150,1210,1315)):a.building('warehouse-rack-'+str(i),(x-12,x+12,190,213),7,'steel')
    a.building('warehouse-dispatch-office',(1355,1410,310,360),10,'ivory');a.building('warehouse-cold-store',(1140,1270,65,125),13,'blue')
    for i,(x,z) in enumerate([(960,120),(1000,120),(1370,110),(1420,110)]):a.box('panel-stack-'+str(i),(x,2,z),(24,4,12),'concrete')
    # Supported elevated interchange, with empty ground routes between piers.
    a.site('interchange','Монтаж развязки','Связь верхних участков и монтаж пролётов','Южный съезд → диагональные пролёты → северо-восточный съезд',[985,1530,805,1180],'elevated-road')
    for i,(x,z) in enumerate([(1138,860),(1180,975),(1278,974),(1340,1118)]):a.box('viaduct-pier-'+str(i),(x,8.3,z),(5,16.6,5));a.box('viaduct-pier-cap-'+str(i),(x,16,z),(12,2,8))
    a.box('gantry-apron-pier-west',(1110,8.3,970),(5,16.6,5));a.box('gantry-apron-pier-east',(1200,8.3,1040),(5,16.6,5))
    a.building('pit-crane-foundation',(305,327,680,702),4,'concrete');a.building('pit-pump',(385,410,425,445),3,'blue',-14)
    for i,(x,z,w,d) in enumerate([(420,475,18,22),(510,585,30,14),(410,610,12,18)]):a.box('pile-cap-'+str(i),(x,-12,z),(w,4,d),'concrete')
    a.hazard('crane-1','CRANE',(535,-14,485),sector='pit-crane');a.hazard('crane-2','CRANE',(1160,0,640),sector='plant-crane');a.hazard('crane-3','CRANE',(1260,18,1000),sector='gantry-crane')
    a.regions=[(rect(970,1450,80,410),'district-service'),(rect(970,1300,470,740),'district-slate')]
    a.spawn('sw spine warehouse_e west frame_e plant_e deck_c nw ne')
    a.district('pit','Котлован',(465,-14,530),rect(80,750,280,755));a.district('homes','Недостроенный квартал',(510,0,860),rect(70,870,760,1160));a.district('plant','Бетонный завод',(1160,0,600),rect(870,1530,415,815));a.district('warehouses','Склады',(1260,0,240),rect(690,1530,35,410));a.district('interchange','Строящаяся развязка',(1260,18,1000),rect(875,1570,820,1180))
    return a

def neon():
    a=Location('neon_zero','НЕОН-РАЙОН ZERO','NEON',1800,1400,10,boss('boss_prefect','Префект',3400,('HOMING','MINE'),[(1620,0,1120),(200,0,1200)],['В этом районе даже аварии происходят по разрешению','Ваш маршрут отменён']))
    a.data['metadata']['introduction']='Городские кварталы, торговый пассаж, связные дворы, диагональная эстакада и настоящий многоэтажный паркинг.'
    a.holes=[rect(1163,1217,400,960)]
    # Cross streets span the deep cutting; its shallow entry mouths remain open.
    a.road_openings=[rect(1163,1217,400,472),rect(1163,1217,888,960)]
    a.fixed=[('underpass-floor',rect(1163,1217,540,820),-12,'road-surface',-1),('parking-first-floor',rect(1400,1660,200,500),8,'concrete',1),('parking-roof-deck',rect(1400,1660,200,500),16,'concrete',2)]
    a.deck_openings={8:[rect(1440,1570,220,260)],16:[rect(1440,1570,420,460)]}
    a.points=dict(sw=(200,0,220),s=(790,0,180),se=(1710,0,180),w=(170,0,600),nw=(200,0,1200),n=(840,0,1210),ne=(1620,0,1120),e=(1650,0,760),home_w=(260,0,430),home_c=(480,0,400),home_e=(660,0,470),home_n=(430,0,640),market_w=(620,0,720),market_in=(750,0,720),market_c=(850,0,720),market_n=(850,0,900),market_e=(1010,0,800),market_s=(850,0,530),business_w=(330,0,970),business_c=(520,0,1020),business_e=(780,0,1040),tech_w=(1020,0,980),tech_in=(1090,0,980),tech_c=(1090,0,1040),tech_e=(1240,0,1040),tn=(1190,0,960),tnb=(1190,-12,820),tc=(1190,-12,680),tsb=(1190,-12,540),ts=(1190,0,400),park_w=(1360,0,350),park_c=(1480,0,350),park_e=(1600,0,350),park_out=(1720,0,350),park_s=(1460,0,240),p1s=(1570,8,240),p1e=(1610,8,240),p1ne=(1610,8,440),p1nw=(1460,8,440),p1w=(1430,8,340),p2nw=(1570,16,440),p2ne=(1610,16,440),p2se=(1610,16,240),p2sw=(1430,16,240),p2w=(1430,16,340),park_n=(1480,0,580),express_s=(930,0,260),express_a=(1050,20,550),express_b=(1320,20,960),express_n=(1460,0,1210),transport_c=(1390,0,900),alley_a=(540,0,670),alley_b=(590,0,820),cut_a=(1300,0,620),cut_b=(1400,0,660))
    a.points.update(ts_w=(1125,0,400),ts_e=(1260,0,400),market_gate=(850,0,660),p1return=(1570,8,340),p2return=(1570,16,340),park_gate=(1400,0,240),park_align=(1400,0,350),p1north=(1610,8,480),p1west=(1420,8,480),p1align=(1420,8,440))
    for name,path,w in [('south-avenue','sw s express_s ts park_w park_c park_e park_out',30),('south-service','s se park_out e ne',24),('west-boulevard','sw w business_w nw n tech_e ne',32),('market-avenue','w home_n market_w market_in market_c market_e transport_c e',26),('north-business','business_w business_c business_e n',26),('business-market','business_c market_n market_e',24),('market-passage','market_c market_n business_e',20),('market-loading','market_w alley_a home_e market_s market_c',20),('residential-market-entry','home_e market_gate market_c',24),('residential-courts','sw home_w home_c home_e s',20),('courtyard-link','home_c home_n',18),('technical-service','market_e tech_w tech_in tech_c tech_e tn',22),('technical-exit','tech_w business_e',22),('transport-services','tech_e transport_c ne',26),('parking-north-entry','park_c park_n cut_b transport_c',24),('parking-west-entry','ts ts_w market_s',24),('tunnel-south-approach','ts park_gate park_s',24),('parking-ramp-approach','park_c park_align park_gate',24),('parking-level1','p1s p1e p1ne p1north p1west p1align p1nw',20),('parking-level1-return','p1align p1w p1return p1e',20),('parking-roof-circuit','p2nw p2ne p2se p2sw p2w p2return p2ne',20),('express-north-approach','ne express_n n',26),('alley-west','alley_a market_w',18),('alley-north','alley_b business_c',18),('industrial-shortcut-south','cut_a ts_e ts',22),('industrial-shortcut-north','cut_b park_n',22)]:a.road(name,path,w)
    a.road('tunnel-north-ramp','tn tnb',54,'road-surface','RAMP');a.road('underpass','tnb tc tsb',54);a.road('tunnel-south-ramp','tsb ts',54,'road-surface','RAMP')
    a.road('parking-ramp-one','park_s p1s',24,'concrete','RAMP');a.road('parking-ramp-two','p1nw p2nw',24,'concrete','RAMP')
    a.road('express-rise','express_s express_a',34,'concrete','RAMP');a.road('express-diagonal','express_a express_b',34,'concrete');a.road('express-fall','express_b express_n',34,'concrete','RAMP')
    a.opening('city-barrier-west','alley_a','alley_b',True);a.opening('city-barrier-east','cut_a','cut_b',True)
    # Deliberately shaped blocks, each with a front, delivery side, and shared street wall.
    for name,b,h,mat in [('meridian-tower',(350,445,1080,1175),145,'blue'),('meridian-podium',(305,485,1070,1185),18,'dark-concrete'),('exchange-tower',(560,650,1090,1165),98,'ivory'),('exchange-podium',(550,680,1070,1180),12,'dark-concrete'),('conference-west',(80,135,755,850),28,'ivory'),('conference-east',(465,535,850,940),44,'blue'),('office-ribbon',(90,125,855,1100),65,'dark-concrete')]:a.building(name,b,h,mat)
    a.site('business-meridian','Деловой центр Meridian','Башни на общественных подиумах и грузовые дворы','Проспект → площадь → служебная улица',[285,680,850,1185],'landmark')
    for name,b,h in [('courtyard-a-south',(305,410,280,315),26),('courtyard-a-west',(290,325,315,375),26),('courtyard-b-east',(555,595,285,370),32),('courtyard-b-south',(455,595,280,315),32),('courtyard-c-north',(250,365,525,555),22),('courtyard-c-west',(250,280,455,525),22),('school',(90,140,350,485),14),('residential-service',(495,530,535,565),8)]:a.building(name,b,h,'brick' if 'courtyard' in name else 'ivory')
    # Named city compounds occupy actual blocks between the authored streets.
    # Their setbacks and delivery courts differ; this is not a grid population pass.
    compounds=[
        ('civic-archive','Городской архив','Хранилище и читальный зал на северной улице',[(140,430,1280,1330,30),(140,185,1240,1280,30),(385,430,1240,1280,18)],'brick'),
        ('north-hotel','Гостиница Meridian','Высотный корпус и низкая входная галерея',[(500,660,1260,1340,82),(660,760,1275,1320,16)],'ivory'),
        ('exchange-annex','Расчётный центр','Офисный двор за деловым проспектом',[(940,1110,1250,1330,58),(940,985,1240,1250,26)],'blue'),
        ('transit-depot','Автобусное депо','Ремонт и хранение вне боевых проездов',[(1210,1370,1290,1350,15),(1210,1260,1245,1290,12)],'steel'),
        ('west-clinic','Поликлиника','Районное общественное здание и служебное крыло',[(60,190,660,710,18),(60,105,710,755,18)],'ivory'),
        ('arts-workshops','Мастерские','Мастерские между западным проспектом и рынком',[(330,480,700,745,16),(335,375,745,820,12),(440,485,745,820,22)],'brick'),
        ('market-cold-store','Холодильный склад','Снабжение торгового пассажа с технической улицы',[(885,970,930,960,9)],'blue'),
        ('east-apartments','Восточный жилой дом','Двор с северным и восточным входами',[(1450,1575,680,715,36),(1450,1485,715,800,36),(1535,1575,755,775,24)],'brick'),
        ('garden-court','Садовый двор','Жилые крылья вокруг тихого двора',[(320,420,465,495,24),(385,420,495,570,24)],'ivory'),
        ('south-business','Гостевой комплекс','Две башни и общая входная площадь',[(825,870,315,430,52),(875,910,340,430,38)],'blue'),
        ('western-substation','Подстанция','Ввод электричества в жилой район',[(45,125,80,160,11),(45,85,170,255,8)],'dark-concrete'),
        ('southern-frontage','Южная торговая линия','Магазины первого этажа и жильё над ними',[(315,590,65,130,25),(625,715,65,125,34)],'brick'),
        ('delivery-centre','Грузовой терминал','Снабжение паркинга и торговых улиц',[(1220,1340,65,140,18),(1440,1600,65,125,12)],'steel')]
    for identity,title,function,wings,material in compounds:
        bounds=[min(w[0] for w in wings),max(w[1] for w in wings),min(w[2] for w in wings),max(w[3] for w in wings)]
        a.site(identity,title,function,'Фасад на существующую улицу; служебный двор между крыльями',bounds,'city-compound')
        for index,(x1,x2,z1,z2,height) in enumerate(wings):a.building(identity+'-wing-'+str(index),(x1,x2,z1,z2),height,material)
    # Glass-roofed passage, stall islands split the atrium into two safe lanes.
    a.site('shopping-passage','Пассаж ZERO','Торговая галерея через квартал с атриумом и разгрузочной стороной','Западный вход → атриум → северный выход',[710,935,670,860],'through-interior')
    a.roof('shopping-passage',(710,935,670,860),18,'glass');a.wall('passage-south-front',(710,675),(815,675),18,2,'ivory');a.wall('passage-south-wing',(885,675),(935,675),18,2,'ivory');a.wall('passage-north-front',(710,855),(810,855),18,2,'ivory');a.wall('passage-east-front',(930,790),(930,815),18,2,'ivory')
    a.building('passage-shop-west',(720,770,770,835),8,'blue');a.building('passage-shop-east',(885,915,770,835),8,'blue');a.building('atrium-stall',(785,820,760,800),3,'wood')
    for i,(x,z) in enumerate([(700,600),(965,635),(1030,690),(680,900)]):a.building('market-corner-'+str(i),(x-30,x+30,z-24,z+24),20+i*3,'brick')
    # Technical complex is a turn through workshops into tunnel approach.
    a.site('technical-complex','Станция обслуживания','Энергетика и обслуживание тоннеля','Западный проезд → технический зал → северный двор → тоннель',[1045,1140,930,1080],'through-interior')
    a.roof('technical-complex',(1045,1140,930,1080),14,'steel');a.wall('technical-west',(1050,1005),(1050,1080),14,2,'dark-concrete');a.wall('technical-south',(1050,935),(1140,935),14,2,'dark-concrete');a.building('technical-switchgear',(1110,1130,945,1005),6,'blue')
    # Underground roof is also a genuine surface, without an invisible ground sheet.
    a.roof('tunnel-cover',(1160,1220,540,820),1.5,'concrete')
    a.wall('tunnel-west-wall',(1162,540),(1162,820),14,2,'concrete',-12);a.wall('tunnel-east-wall',(1218,540),(1218,820),14,2,'concrete',-12)
    # Retaining faces close the visible sides of both open ramp cuttings.
    # Their inside faces coincide with the road edge, not with its top surface.
    for end,z1,z2 in [('south',400,540),('north',820,960)]:
        for side,x in [('west',1162),('east',1218)]:a.wall(f'tunnel-{end}-{side}-retaining',(x,z1),(x,z2),12.6,2,'cast-concrete',-12)
    # Three compact parking levels. Open ramp slots are cut from decks before authoring.
    a.site('parking-ground-floor','Паркинг P‑03','Два крытых этажа, открытый верхний и встроенные рампы','Западный и восточный въезды; северный двор; рампы вдоль фасадов',[1400,1660,200,500],'through-interior')
    a.roof('parking-ground-floor',(1400,1660,290,400),7.2,'concrete')
    for x,z in [(1410,300),(1410,400),(1530,300),(1530,400),(1650,300),(1650,400)]:
        for level in (0,8):a.box(f'parking-column-{x}-{z}-{level}',(x,level+3.6,z),(2,7.2,2),'concrete')
    for level in (8,16):
        for label,aa,bb in [('north',(1400,500),(1660,500)),('south',(1400,200),(1660,200)),('east',(1660,200),(1660,500)),('west',(1400,200),(1400,500))]:a.wall(f'parking-parapet-{level}-{label}',aa,bb,1.1,.6,'concrete',level)
    for i,(x,z) in enumerate([(1030,590),(1135,750),(1240,915)]):a.box('express-pier-'+str(i),(x,8.8,z),(5,17.6,5),'concrete')
    a.hazard('traffic-west','TRAFFIC',(480,0,180),(120,4,22),'traffic');a.hazard('traffic-east','TRAFFIC',(1600,0,180),(120,4,22),'traffic');a.hazard('electric-strip','ELECTRIC',(1090,0,1015),(9,6,18))
    a.regions=[(rect(240,640,250,610),'district-garden'),(rect(700,950,650,880),'district-warm'),(rect(1380,1680,180,520),'district-service')]
    a.spawn('sw market_e park_out home_e business_e tc p1ne nw ne s home_n')
    a.district('business','Деловой центр',(520,0,1020),rect(40,835,845,1340));a.district('market','Торговые улицы',(850,0,720),rect(545,1050,510,935));a.district('homes','Жилые дворы',(480,0,400),rect(50,710,90,670));a.district('transport','Развязка и подземные проезды',(1390,0,900),rect(1055,1745,590,1340));a.district('parking','Многоэтажный паркинг',(1480,0,350),rect(1080,1750,100,585))
    return a

def carnival():
    a=Location('euphoria_park','ЛУНАПАРК ЭЙФОРИЯ','CARNIVAL',1500,1300,12,boss('boss_emcee','Конферансье',4000,('NAPALM','POWER'),[(1310,0,1030),(220,0,1060)],['Билеты невозвратные. Как и вы!','А теперь — через озеро!'],['island-south-launch','backstage-launch']))
    a.data['bounds']['recoveryY']=-8
    a.data['metadata']['introduction']='Парадные аллеи и закулисные дороги вокруг живописного озера. Три моста, настоящий цирк и ремонтные мастерские.'
    lake=[(490,470),(620,400),(860,420),(1000,540),(1020,700),(920,870),(700,920),(520,800),(450,620)]
    island=[(640,580),(700,540),(810,540),(870,600),(870,720),(810,800),(700,800),(640,740)]
    a.holes=[lake];a.fixed=[('lake-bottom',lake,-18,'earth',-1),('island',island,3,'park-paving',1)]
    a.mesh('lake-water',[lake],-1.2,'cyan',0,False)
    a.points=dict(entry=(160,0,80),entry_e=(400,0,200),entry_n=(200,0,330),fair_s=(380,0,390),fair_c=(310,0,560),fair_n=(320,0,780),fair_e=(420,0,620),fair_w=(170,0,620),circus_s=(330,0,930),circus_in=(330,0,1020),circus_e=(410,0,1080),circus_out=(530,0,1080),circus_w=(220,0,1060),circus_n=(330,0,1170),north=(750,0,1080),depot_w=(1080,0,1040),depot_in=(1180,0,1040),depot_turn=(1250,0,1040),depot_out=(1250,0,1160),depot_e=(1390,0,1040),backstage=(1370,0,900),rides_n=(1140,0,820),rides_c=(1210,0,600),rides_s=(1140,0,370),ride_in=(1210,0,440),ride_turn=(1290,0,500),ride_out=(1410,0,520),south=(740,0,250),se=(1340,0,220),shore_s=(720,0,345),shore_sw=(510,0,360),shore_w=(420,0,650),shore_nw=(460,0,890),shore_n=(760,0,975),shore_e=(1080,0,670),west_ramp=(445,0,650),west_high=(515,3,650),island_w=(640,3,650),island_c=(750,3,650),island_e=(870,3,650),east_high=(1030,3,650),east_ramp=(1100,0,700),island_n=(755,3,800),north_high=(755,3,945),north_ramp=(835,0,1020),island_s=(750,3,565),jump_s=(750,0,355),jump_run=(750,0,290),gate_a=(165,0,820),gate_b=(290,0,885),jump_back=(1120,0,950),jump_land=(1095,0,1160))
    a.points.update(island_around=(815,3,700),back_run=(1127.09,0,890.42),service_mid=(1160,0,790),depot_receiving=(1185,0,950),ride_service_yard=(1200,0,800),service_cross=(1395,0,900))
    for name,path,w in [('arrival-walk','entry entry_e shore_sw south se rides_s',30),('entrance-alley','entry entry_n fair_s fair_c fair_n circus_s',24),('fair-backstage','entry_n fair_w gate_a circus_w circus_n circus_out north depot_out depot_e',22),('fair-crossings','fair_s shore_sw shore_s jump_s',24),('fair-front','fair_c fair_e shore_w shore_nw circus_s',24),('fair-north-cross','fair_n shore_nw shore_n north',24),('circus-arena','circus_s circus_in circus_e circus_out',24),('circus-backstage','circus_w circus_in circus_n',22),('east-promenade','se ride_out rides_c rides_n depot_w depot_in depot_turn depot_e',24),('ride-pavilion','rides_s ride_in ride_turn ride_out',24),('ride-front','ride_in rides_c shore_e east_ramp',26),('ride-servicing','ride_out service_cross depot_e',22),('backstage-cross-aisle','backstage service_cross',24),('depot-through','depot_turn depot_out',24),('depot-west-service','depot_w jump_back north',24),('backstage-runup','rides_n back_run jump_back',24),('mechanism-service-link','rides_c service_mid jump_back depot_w',24),('depot-receiving-route','rides_c ride_service_yard depot_receiving depot_in',24),('depot-north-service','depot_out jump_land north',22),('north-shore','shore_n north_ramp depot_w',24),('southern-promenade','south shore_s rides_s shore_e',26),('island-path','island_w island_s island_e island_n island_w',24),('island-square','island_w island_c island_e',26),('island-north-path','island_c island_around island_n',24),('bridge-west-approach','fair_w shore_w west_ramp',26),('jump-runup','south jump_run jump_s',24),('service-gate-a','fair_w gate_a',22),('service-gate-b','gate_b circus_s',22),('boss-depot-entry','backstage depot_e',24)]:a.road(name,path,w,'road-surface' if 'service' in name or 'backstage' in name else 'park-paving')
    # Exactly three bridges. Their flat landings stop AT island polygon edges;
    # island pavement is clipped against ribbons, never hidden under another top face.
    a.road('west-bridge-rise','west_ramp west_high',30,'wood','RAMP');a.road('west-lake-bridge','west_high island_w',30,'wood')
    a.road('east-lake-bridge','island_e east_high',26,'steel');a.road('east-bridge-fall','east_high east_ramp',26,'steel','RAMP')
    a.road('north-lake-bridge','island_n north_high',24,'concrete');a.road('north-bridge-fall','north_high north_ramp',24,'concrete','RAMP')
    a.opening('service-shortcut','gate_a','gate_b');a.launch('island-south-launch','jump_s','island_s',3.2);a.launch('backstage-launch','jump_back','jump_land',2.8)
    # Ticket court, actual pavilion-scale fair stalls with front and delivery face.
    a.site('main-gates','Вход Эйфория','Кассы и распределение посетителей','Площадь → ярмарка или береговая дорога',[100,420,100,350],'gateway')
    a.building('ticket-office-west',(105,140,240,280),6,'faded-red');a.building('ticket-office-east',(255,300,240,280),6,'ivory')
    for i,(x,z,w,d,yaw) in enumerate([(245,410,32,24,-12),(260,500,28,20,10),(375,475,40,26,-18),(205,725,34,28,15),(390,745,30,22,-8),(285,330,26,20,12),(360,700,32,24,-12)]):a.box('fair-stall-'+str(i),(x,4,z),(w,8,d),'wood' if i%2 else 'faded-red',True,yaw)
    a.building('fair-delivery-store',(70,115,710,780),7,'blue')
    for identity,x,z,w,d,h,mat,yaw in [
        ('sweet-shop',265,570,26,20,7,'faded-red',0),('shooting-gallery',270,665,32,22,8,'blue',-3),
        ('fortune-cabinet',270,720,26,20,10,'purple',4),('arcade',365,520,30,26,9,'blue',-12),
        ('tea-house',220,475,26,28,8,'wood',10),('bakery',225,535,30,22,7,'ivory',5),
        ('prize-store',230,585,30,20,7,'faded-red',-8),('photo-kiosk',445,520,26,20,8,'ivory',8),
        ('souvenir-row',405,795,34,22,9,'wood',18),('puppet-stage',220,775,36,26,9,'purple',-5)]:
        a.box('fair-shop-'+identity,(x,h/2,z),(w,h,d),mat,True,yaw)
    a.site('fair-street','Ярмарочная улица','Кафе, игры, призы и мастерские за торговым фасадом','Извилистая публичная улица и отдельный западный проезд разгрузки',[150,470,380,830],'market-frontage')
    a.site('island-bandstand','Музыкальный павильон','Оркестровая эстрада и место отдыха у воды','Три мостовых подхода сходятся на площади; проезд обходит павильон',[725,785,685,745],'landmark')
    a.box('island-bandstand-proxy',(755,11,715),(62,16,62),'ivory',False)
    a.box('island-bandstand-base',(755,3.6,715),(52,1.2,52),'wood')
    # Polygonal circus with two cardinal entrances and an offset backstage exit.
    a.site('circus','Цирк Эйфория','Манеж, зрительские сектора и закулисье','Южный вход → манеж → восточное закулисье; северный обход',[240,455,975,1160],'through-interior')
    a.roof('circus',(245,450,975,1155),18,'faded-red')
    for name,aa,bb in [('sw',(250,995),(285,975)),('se',(375,975),(430,1000)),('east-low',(445,1010),(445,1045)),('east-high',(445,1135),(445,1150)),('nw',(250,995),(235,1045)),('west-top',(235,1100),(285,1155))]:a.wall('circus-'+name,aa,bb,12,2,'faded-red')
    a.building('circus-seating-east',(382,410,995,1030),5,'ivory');a.building('circus-seating-west',(260,285,1075,1090),5,'ivory');a.building('circus-changing',(475,510,1135,1170),7,'blue')
    # Covered ride hall has a curved shell and an offset drive path around a central ride.
    a.site('ride-pavilion','Павильон Орбита','Крытый аттракцион и его технический доступ','Южный вход → объезд механизма → восточный служебный выход',[1170,1375,405,555],'through-interior')
    a.roof('ride-pavilion',(1170,1375,405,555),20,'purple');a.wall('ridehall-south',(1245,410),(1370,410),12,2,'purple');a.wall('ridehall-north',(1240,550),(1260,550),12,2,'purple');a.wall('ridehall-west',(1175,475),(1175,550),12,2,'purple');a.building('orbit-ride-core',(1250,1310,420,460),6,'steel')
    a.building('ferris-foundation',(1270,1330,680,720),3,'concrete');a.building('carousel-base',(900,950,130,180),1,'wood')
    for i,(x,z) in enumerate([(1050,480),(1380,780),(1430,650)]):a.building('ride-control-'+str(i),(x-12,x+12,z-10,z+10),6,'ivory')
    # Workshop bays, cross aisle, gantry, storage and north dispatch door.
    a.site('repair-depot','Ремонтное депо','Обслуживание аттракционов и хранение модулей','Западный ремонтный проезд → поперечный цех → северная выдача; южная приёмка связана с аттракционами',[1140,1320,990,1120],'through-interior')
    a.roof('repair-depot',(1140,1320,990,1120),15,'steel');a.wall('depot-south-west',(1140,990),(1155,990),15,2,'blue');a.wall('depot-south-east',(1210,990),(1320,990),15,2,'blue');a.wall('depot-north-west',(1140,1120),(1215,1120),15,2,'blue');a.wall('depot-north-east',(1310,1120),(1320,1120),15,2,'blue')
    for i,x in enumerate((1165,1220,1290)):a.building('repair-bench-'+str(i),(x-10,x+10,995,1015),3,'steel')
    a.building('depot-parts-store',(1340,1435,1140,1185),10,'blue');a.building('maintenance-office',(1030,1080,1170,1220),8,'ivory')
    a.hazard('carousel','CAROUSEL',(925,0,315),(36,6,36));a.hazard('stage-fire','FIRE',(1280,0,500),(24,6,7))
    for name,bounds in [('entry-garden',(45,130,75,305)),('front-lawn',(440,630,65,195)),('west-fair-garden',(45,130,340,865)),('south-promenade-grove',(540,675,235,330)),('circus-garden',(65,200,940,1220)),('north-bank-grove',(530,670,935,1020)),('wheel-garden',(1340,1450,610,880)),('backstage-green-buffer',(870,1030,1135,1240)),('fair-east-garden',(365,440,445,585)),('ride-arrival-garden',(1060,1200,80,250))]:a.grove(name,bounds)
    a.regions=[(rect(90,435,100,355),'park-paving'),(rect(1120,1450,950,1230),'district-service'),(rect(220,535,965,1190),'park-paving')]
    a.spawn('entry fair_s ride_out depot_e circus_out shore_nw island_e rides_n backstage se circus_w south fair_w')
    a.district('entrance','Входная площадь',(200,0,180),rect(40,600,50,365));a.district('fair','Ярмарка',(310,0,560),rect(40,480,370,925));a.district('lake','Озеро и остров',(750,3,650),[(485,430),(1010,430),(1060,740),(920,935),(480,925)]);a.district('rides','Большие аттракционы',(1210,0,600),rect(1065,1470,100,935));a.district('circus','Цирк',(330,0,1060),rect(50,700,940,1260));a.district('backstage','Служебная территория',(1250,0,1040),rect(705,1470,940,1260))
    return a

def expand_yard():
    path=OUT/'arena.json';data=json.loads(path.read_text(encoding='utf-8'));data['roads']=[]
    for mesh in data['meshes']:mesh.setdefault('thickness',0)
    # Native Grinder collection was4.58s from the southeast start. Keep the
    # existing cache and its1440tick respawn, closer along the same clear apron.
    next(p for p in data['pickups'] if p['id']=='homing-south')['position']=vec((20,0,-40))
    next(p for p in data['pickups'] if p['id']=='additional-power_ammo-17')['position']=vec((20,0,-58))
    existing=collections.Counter(p['type'] for p in data['pickups']);sockets=[(-65,0,-5),(-50,0,-15),(-30,0,-55),(-15,0,-50),(10,0,-52),(30,0,-45),(58,0,-45),(65,0,-20),(65,0,10),(55,0,35),(15,0,52),(-5,0,45),(-35,0,52),(-60,0,47),(-66,0,25),(-35,0,-18),(-12,0,15),(10,0,22),(25,0,35),(40,6,7)]
    for kind in AMMO:
        for _ in range(max(0,4-existing[kind])):
            chosen=None
            for p in sockets:
                if any(dist(p,point(q['position']))<7 for q in data['pickups']+data['spawns']):continue
                valid=True
                for b in data['boxes']:
                    if not b['collision']:continue
                    c,s=b['center'],b['size']
                    if c['y']+s['y']/2<=p[1]+.2 or c['y']-s['y']/2>=p[1]+5:continue
                    if abs(p[0]-c['x'])<s['x']/2+2.5 and abs(p[2]-c['z'])<s['z']/2+2.5:valid=False;break
                if valid:chosen=p;break
            assert chosen,kind;sockets.remove(chosen);data['pickups'].append(dict(id='additional-'+kind.lower()+'-'+str(len(data['pickups'])),type=kind,position=vec(chosen),respawnTicks=3000))
    write_text_atomic(path,json.dumps(data,ensure_ascii=False,indent=2)+'\n')

def main():
    for factory in (construction,neon,carnival):factory().finish()
    expand_yard()
    write_review_routes()
def write_review_routes():
    import html
    specs={
        'construction_17': [('pit','district','west pit_w pit_wb pit pit_eb pit_e'),('homes','district','frame_w frame_in frame_turn frame_out frame_e'),('plant','district','plant_w plant_in plant_turn plant_n plant_e'),('warehouses','district','warehouse_w warehouse_in warehouse_turn warehouse_out'),('interchange','district','ramp_s deck_s deck_c deck_n ramp_n'),('unfinished-apartments','interior','frame_w frame_in frame_turn frame_out'),('concrete-plant','interior','plant_w plant_in plant_turn plant_n'),('warehouse','interior','warehouse_w warehouse_in warehouse_turn warehouse_out')],
        'neon_zero': [('business','district','business_w business_c business_e n'),('market','district','market_w market_in market_c market_n'),('homes','district','home_w home_c home_e s'),('transport','district','tech_e tn tnb tc tsb ts'),('parking','district','park_w park_c park_align park_gate park_s p1s p1e p1ne p1north p1west p1align p1nw p2nw'),('shopping-passage','interior','market_w market_in market_c market_n'),('technical-complex','interior','tech_w tech_in tech_c tech_e'),('parking-ground-floor','interior','park_w park_c park_e park_out')],
        'euphoria_park': [('entrance','district','entry entry_n fair_s'),('fair','district','fair_s fair_c fair_e shore_w'),('lake','district','west_ramp west_high island_w island_c island_around island_n north_high north_ramp'),('rides','district','rides_s ride_in ride_turn ride_out'),('circus','district','circus_s circus_in circus_e circus_out'),('backstage','district','depot_w depot_in depot_turn depot_out'),('circus','interior','circus_s circus_in circus_e circus_out'),('ride-pavilion','interior','rides_s ride_in ride_turn ride_out'),('repair-depot','interior','depot_w depot_in depot_turn depot_out')]}
    routes=[]
    for factory in (construction,neon,carnival):
        a=factory();identity=a.data['id'];data=json.loads((OUT/(a.resource+'.json')).read_text(encoding='utf-8'))
        for name,kind,names in specs[identity]:routes.append(dict(arenaId=identity,id=name,kind=kind,points=[vec(a.points[n]) for n in names.split()]))
        elements=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="-30 -90 {a.width+60} {a.depth+120}"><rect x="-30" y="-90" width="{a.width+60}" height="{a.depth+120}" fill="#172026"/><text x="20" y="-35" fill="white" font-size="32" font-family="sans-serif">{html.escape(data["metadata"]["title"])} · REVISION 3</text><g transform="translate(0 {a.depth}) scale(1 -1)">']
        for i,d in enumerate(data['districts']):
            coords=' '.join(f'{p["x"]},{p["z"]}' for p in d['boundary']);elements.append(f'<polygon points="{coords}" fill="{["#39484e","#524b36","#40594e","#4a4560","#61474b","#3c5962"][i]}" stroke="#809197" stroke-width="2"/>')
        for m in data['meshes']:
            if m['id']=='lake-water':elements.append('<polygon points="'+' '.join(f'{v["x"]},{v["z"]}' for v in m['vertices'])+'" fill="#295b78"/>')
        for path in a.paths:
            coords=' '.join(f'{a.points[n][0]},{a.points[n][2]}' for n in path['names']);color='#daaf68' if any(a.points[n][1]>1 for n in path['names']) else '#7095bc' if any(a.points[n][1]<-1 for n in path['names']) else '#b2b8b7'
            elements.append(f'<polyline points="{coords}" stroke="{color}" stroke-width="{path["width"]}" fill="none" stroke-linejoin="round"/>')
        for b in data['boxes']:
            if b['id'].startswith('edge-'):continue
            c,s=b['center'],b['size']
            if s['y']<3:continue
            elements.append(f'<rect x="{c["x"]-s["x"]/2}" y="{c["z"]-s["z"]/2}" width="{s["x"]}" height="{s["z"]}" fill="#20272c" stroke="#a0a2a1" transform="rotate({-b["yawDegrees"]} {c["x"]} {c["z"]})"/>')
        for p in data['pickups']:
            if p['type'] in AMMO:elements.append(f'<circle cx="{p["position"]["x"]}" cy="{p["position"]["z"]}" r="5" fill="#f8d25c"/>')
        for p in data['spawns']:elements.append(f'<circle cx="{p["position"]["x"]}" cy="{p["position"]["z"]}" r="10" fill="#83d6ff"/>')
        elements.append('</g>')
        for d in data['districts']:elements.append(f'<text x="{d["center"]["x"]}" y="{a.depth-d["center"]["z"]-38}" fill="white" text-anchor="middle" font-size="22" font-family="sans-serif">{html.escape(d["title"])}</text>')
        elements.append('</svg>');write_text_atomic(SOURCE/(identity+'-plan.svg'),'\n'.join(elements))
    path=ROOT/'src/tools/assets/architecture';write_text_atomic(path/'review-routes.json',json.dumps(dict(schemaVersion=1,routes=routes),ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()

