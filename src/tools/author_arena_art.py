"""Original authored architecture, fixtures and mechanical scenery for revision 3.

Canonical pavement is already in ArenaDefinition. No road overlays, anonymous
block grids, decorative paving sheets, or shared drive-through hall generator.
"""
import json
import math
from author_campaign_arenas import ROOT,OUT,vec,construction,neon,carnival

class Scene:
    def __init__(self,location):
        self.location=location;self.data=json.loads((OUT/(location.resource+'.json')).read_text(encoding='utf-8'));self.parts=[];self.groups=[];self.models=[];self.lights=[];self.anchor='';self.group=''
    def part(self,name,pos,size,material,shape='BOX',rotation=(0,0,0)):
        self.parts.append(dict(id=f'{name}-{len(self.parts):05}',group=self.group,anchor=self.anchor,shape=shape,material=material,position=vec(pos),size=vec(size),rotation=vec(rotation)))
    def cylinder(self,name,p,r,h,mat):self.part(name,p,(r*2,r*2,h),mat,'CYLINDER',(90,0,0))
    def beam(self,name,a,b,width,mat):
        dx,dy,dz=(b[i]-a[i] for i in range(3));length=math.sqrt(dx*dx+dy*dy+dz*dz)
        self.part(name,tuple((a[i]+b[i])/2 for i in range(3)),(width,width,length),mat,'CYLINDER',(-math.degrees(math.asin(dy/length)),math.degrees(math.atan2(dx,dz)),0))
    def model(self,name,asset,pos,scale=(1,1,1),rotation=(0,0,0),proxies=(),lod=280):
        self.models.append(dict(id=name,group='',anchor=self.anchor,asset=f'models/arenas/{asset}.j3o',distantAsset=f'models/arenas/{asset}-lod.j3o',position=vec(pos),size=vec(scale),rotation=vec(rotation),lodDistance=lod,collisionGeometryIds=list(proxies)))
    def light(self,name,p,color=(1,.78,.5),radius=30):self.lights.append(dict(id=name,position=vec(p),color=vec(color),radius=radius))
    def hero(self,proxy,asset,y=None):
        b=next(b for b in self.data['boxes'] if b['id']==proxy+'-roof');c=b['center'];bottom=c['y']-b['size']['y']/2
        self.anchor=b['id'];self.model(proxy,asset,(c['x'],bottom if y is None else y,c['z']),proxies=[b['id']]);b['collision']=False
    def facade(self,b):
        c,s=b['center'],b['size'];x,y,z=c['x'],c['y'],c['z'];w,h,d=s['x'],s['y'],s['z'];self.anchor=b['id']
        if min(w,d)<24 or h<8 or 'roof' in b['id'] or b['id'].startswith(('edge-','pile-','silo-')):return
        # Window frames project by decimetres; no near-coplanar millimetre stacks.
        floor_count=min(12,int(h/4.5));columns=min(14,max(2,int(w/8)));base=y-h/2
        for sign in (-1,1):
            face=z+sign*(d/2+.22)
            for floor in range(floor_count):
                for col in range(columns):
                    xx=x-w/2+(col+.5)*w/columns;yy=base+3+floor*4.5
                    self.part('architectural-window',(xx,yy,face),(3.8,2.2,.32),'glass')
                    if self.data['metadata']['theme']=='NEON' and (col+floor*3)%7==0:self.part('occupied-window',(xx,yy,face+sign*.24),(3.0,1.5,.12),'light-amber')
            self.part('parapet-coping',(x,y+h/2+.2,z+sign*(d/2-.4)),(w,.5,.8),'steel')
        self.part('roof-service-unit',(x-w*.22,y+h/2+1.5,z),(max(5,w*.15),3,max(4,d*.16)),'steel')
        self.part('street-address',(x-w*.25,base+4.5,z-d/2-.5),(7,2,.3),'ivory')
    def common(self):
        for b in self.data['boxes']:self.facade(b)
        used=set()
        for path in self.location.paths:
            if path['kind']!='ROAD':continue
            names=path['names']
            for index,name in enumerate(names[:-1]):
                if name in used:continue
                used.add(name);p=self.location.points[name];q=self.location.points[names[index+1]]
                if p[1]!=0:continue
                dx,dz=q[0]-p[0],q[2]-p[2];length=math.hypot(dx,dz);side=path['width']/2+5;x,z=p[0]+dz/length*side,p[2]-dx/length*side
                if not self.location.clear((x,0,z),3):continue
                self.anchor='';height=8 if self.data['metadata']['theme']=='CARNIVAL' else 11
                self.cylinder('road-fixture-column',(x,height/2,z),.22,height,'steel');self.part('road-fixture-head',(x,height,z),(1.8,.6,1.2),'steel');self.part('road-fixture-lens',(x,height-.4,z),(1.2,.15,.8),'light-amber' if self.data['metadata']['theme']!='NEON' else 'light-white')
                if len(self.lights)<24:self.light('road-fixture-'+name,(x,height-.8,z),(1,.8,.5) if self.data['metadata']['theme']!='NEON' else (.6,.8,1),24)
        # Each raised span has fascia, underside and grounded supports. These are
        # structural faces offset below the canonical road, never a second top sheet.
        for path in self.location.paths:
            if not any(self.location.points[n][1]>2 for n in path['names']):continue
            if not any(word in path['id'] for word in ('bridge','express','interchange')):continue
            for first,last in zip(path['names'],path['names'][1:]):
                p,q=self.location.points[first],self.location.points[last];dx,dz=q[0]-p[0],q[2]-p[2];length=math.hypot(dx,dz);sx,sz=dz/length*path['width']/2,-dx/length*path['width']/2
                self.anchor=''
                for sign in (-1,1):self.beam('bridge-edge-girder',(p[0]+sx*sign,p[1]-1,p[2]+sz*sign),(q[0]+sx*sign,q[1]-1,q[2]+sz*sign),1.3,'steel')
                angle=-math.degrees(math.atan2(dz,dx));slope=math.degrees(math.atan2(q[1]-p[1],length))
                self.part('bridge-underside',((p[0]+q[0])/2,(p[1]+q[1])/2-1,(p[2]+q[2])/2),(math.hypot(length,q[1]-p[1]),.35,path['width']),'concrete',rotation=(0,angle,slope))
    def construction(self):
        for proxy,asset in [('unfinished-apartments','unfinished-frame'),('concrete-plant','plant-sawtooth'),('warehouse','warehouse-trusses')]:self.hero(proxy,asset)
        for i,(x,z) in enumerate([(1020,370),(1130,375),(1350,440)]):self.anchor='silo-base-'+str(i);self.model('cement-silo-'+str(i),'cement-silo',(x,3,z))
        self.anchor='pit-crane-foundation';x,z=316,691
        for y in range(0,80,10):
            for sign in (-1,1):
                self.beam('crane-leg',(x+sign*4,y,z-4),(x+sign*4,y+10,z-4),.65,'yellow');self.beam('crane-brace',(x-4,y,z-4),(x+4,y+10,z-4),.3,'steel')
        self.beam('crane-jib',(x-28,80,z),(x+145,80,z),1.8,'yellow');self.beam('crane-cable',(x+125,80,z),(x+125,5,z),.16,'steel');self.part('crane-counterweight',(x-28,78,z),(12,8,12),'concrete')
        for i,(p,q) in enumerate([((1020,45,370),(1110,27,540)),((1130,45,375),(1110,27,540)),((1350,45,440),(1110,27,540))]):self.anchor='plant-mixer-core';self.beam('cement-feed',p,q,1.8,'steel')
        for x,z in [(1040,540),(1150,680),(1180,270),(390,880)]:self.light(f'industrial-work-{x}',(x,11,z),(1,.82,.57),38)
        self.anchor='exterior'
        for x,z,w,d in [(450,-140,700,180),(-120,900,170,450),(1680,600,130,800)]:
            self.part('industrial-neighbor',(x,12,z),(w,24,d),'brick')
            for offset in (-.3,0,.3):self.cylinder('factory-chimney',(x+w*offset,38,z),5,76,'rust')
    def neon(self):
        for proxy,asset in [('shopping-passage','passage-glass'),('technical-complex','technical-rooftop'),('parking-ground-floor','parking-ceiling')]:self.hero(proxy,asset)
        self.anchor='shopping-passage-roof';self.groups.append(dict(id='market-screen',position=vec((780,12,674)),motion='SCREEN',period=8,phase=0));self.group='market-screen'
        for i in range(8):self.part('market-screen-pixel',((i-4)*3.6,0,0),(2.9,5,.2),'light-magenta' if i%3 else 'light-cyan')
        self.group=''
        for i,(x,y,z,color) in enumerate([(750,10,730,(.45,.75,1)),(850,12,830,(1,.4,.7)),(1090,9,990,(.6,.85,1)),(1480,5,350,(1,.8,.55)),(1190,-5,580,(.5,.75,1)),(1190,-5,700,(.5,.75,1)),(1190,-5,800,(.5,.75,1))]):
            self.light('interior-light-'+str(i),(x,y,z),color,28);self.part('interior-light-fixture',(x,y+.5,z),(6,.4,1),'steel');self.part('interior-light-lens',(x,y+.25,z),(5,.12,.6),'light-white')
        for name in ('meridian-tower','exchange-tower'):
            b=next(b for b in self.data['boxes'] if b['id']==name);c,s=b['center'],b['size'];self.anchor=name
            for sign in (-1,1):self.part('vertical-neon-sign',(c['x']+sign*(s['x']/2-.8),c['y'],c['z']-s['z']/2-.6),(1.2,s['y']*.8,.5),'light-cyan' if name=='meridian-tower' else 'light-magenta')
        self.anchor='exterior'
        for i,(x,z,w,d,h) in enumerate([(-95,200,180,250,50),(-130,1100,240,380,120),(480,1500,650,170,65),(1150,1500,380,170,170),(1900,1100,160,500,88),(1870,300,120,400,110),(750,-130,700,210,45)]):
            self.part('city-continuation',(x,h/2,z),(w,h,d),'brick' if i%2 else 'dark-concrete');self.part('city-roof-setback',(x,h+7,z),(w*.65,14,d*.65),'blue')
    def carnival(self):
        for proxy,asset in [('circus','circus-canopy'),('ride-pavilion','orbit-shell'),('repair-depot','depot-gantry')]:self.hero(proxy,asset)
        self.anchor='ferris-foundation'
        for sign in (-1,1):self.beam('ferris-support',(1300+sign*28,3,700),(1300,65,700),2,'ivory')
        self.groups.append(dict(id='euphoria-ferris-wheel',position=vec((1300,65,700)),motion='ROTATE_Z',period=60,phase=0));self.group='euphoria-ferris-wheel'
        for i in range(32):
            a=i*math.tau/32;b=(i+1)*math.tau/32;p=(math.cos(a)*58,math.sin(a)*58,0);q=(math.cos(b)*58,math.sin(b)*58,0);self.beam('wheel-rim',p,q,1.2,'ivory')
            if i%2==0:self.beam('wheel-spoke',(0,0,0),p,.3,'steel');self.part('wheel-gondola',p,(6,4,4),'faded-red' if i%4 else 'blue')
        self.group='';self.anchor='carousel-base';self.cylinder('carousel-roof',(925,11,155),25,2,'ivory')
        for i in range(12):self.cylinder('carousel-pole',(925+math.cos(i*math.tau/12)*20,6,155+math.sin(i*math.tau/12)*20),.25,10,'yellow')
        for b in self.data['boxes']:
            if not b['id'].startswith('fair-stall-'):continue
            c,s=b['center'],b['size'];self.anchor=b['id'];self.part('stall-canopy',(c['x'],7,c['z']-s['z']/2-2),(s['x']+1,1,5),'ivory',rotation=(0,b['yawDegrees'],0));self.part('stall-counter',(c['x'],2,c['z']-s['z']/2-.5),(s['x']*.8,.7,1.3),'wood')
        for i,p in enumerate([(330,12,1030),(400,12,1090),(1210,13,450),(1300,13,515),(1200,10,1040),(1260,10,1100)]):self.light('park-worklight-'+str(i),p,(1,.72,.4),32)
        self.anchor='exterior'
        # Continuous wooded setting beyond the physical park edge; no floating oval hill islands.
        for i in range(36):
            side=i%4;t=(i//4+.5)/9
            x,z=(-35-t*40,t*1300) if side==0 else (1535+t*35,t*1300) if side==1 else (t*1500,-45) if side==2 else (t*1500,1340)
            self.model('woodland-tree-'+str(i),'park-tree',(x,0,z),(1.5+(i%3)*.3,1.3+(i%4)*.2,1.5+(i%3)*.3),lod=350)
        self.part('south-woodland-ground',(750,-2,-110),(1850,4,220),'grass');self.part('north-woodland-ground',(750,-2,1400),(1850,4,200),'grass');self.part('west-woodland-ground',(-100,-2,650),(200,4,1300),'grass');self.part('east-woodland-ground',(1600,-2,650),(200,4,1300),'grass')
    def save(self):
        # Model roof geometry is the single visible/physical source; former analytical
        # proxy is metadata only, excluded from ordinary solids by collision:false.
        (OUT/(self.location.resource+'.json')).write_text(json.dumps(self.data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        data=dict(schemaVersion=2,arenaId=self.data['id'],source='src/tools/author_arena_art.py; src/tools/author_arena_models.py; original revision 3 architecture',license='Original project geometry; locally vendored materials retain their individual provenance.',groups=self.groups,parts=self.parts,models=self.models,lights=self.lights)
        (OUT/('arena-art-'+self.data['id'].replace('_','-')+'.json')).write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8');print(self.data['id'],len(self.parts),'parts',len(self.models),'models',len(self.lights),'lights')
if __name__=='__main__':
    for factory,method in [(construction,'construction'),(neon,'neon'),(carnival,'carnival')]:
        scene=Scene(factory());scene.common();getattr(scene,method)();scene.save()
