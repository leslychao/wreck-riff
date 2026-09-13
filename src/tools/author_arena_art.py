"""Original authored architecture, fixtures and mechanical scenery for revision 3.

Canonical pavement is already in ArenaDefinition. No road overlays, anonymous
block grids, decorative paving sheets, or shared drive-through hall generator.
"""
import copy
import json
import math
from author_campaign_arenas import ROOT,OUT,vec,construction,neon,carnival,expand_yard,write_review_routes,write_text_atomic

class Scene:
    def __init__(self,location):
        # Dressing changes both surfaces and solids. Always rebuild from the
        # authoring source; stripping IDs cannot restore ground cut on an earlier run.
        self.location=location;self.data=copy.deepcopy(location.compile());self.parts=[];self.groups=[];self.models=[];self.lights=[];self.signs=[];self.anchor='';self.group=''
    def part(self,name,pos,size,material,shape='BOX',rotation=(0,0,0)):
        self.parts.append(dict(id=f'{name}-{len(self.parts):05}',group=self.group,anchor=self.anchor,shape=shape,material=material,position=vec(pos),size=vec(size),rotation=vec(rotation)))
    def cylinder(self,name,p,r,h,mat):self.part(name,p,(r*2,r*2,h),mat,'CYLINDER',(90,0,0))
    def beam(self,name,a,b,width,mat):
        dx,dy,dz=(b[i]-a[i] for i in range(3));length=math.sqrt(dx*dx+dy*dy+dz*dz)
        self.part(name,tuple((a[i]+b[i])/2 for i in range(3)),(width,width,length),mat,'CYLINDER',(-math.degrees(math.asin(dy/length)),math.degrees(math.atan2(dx,dz)),0))
    def model(self,name,asset,pos,scale=(1,1,1),rotation=(0,0,0),proxies=(),lod=280):
        self.models.append(dict(id=name,group='',anchor=self.anchor,asset=f'models/arenas/{asset}.j3o',distantAsset=f'models/arenas/{asset}-lod.j3o',position=vec(pos),size=vec(scale),rotation=vec(rotation),lodDistance=lod,collisionGeometryIds=list(proxies)))
    def light(self,name,p,color=(1,.78,.5),radius=30):self.lights.append(dict(id=name,position=vec(p),color=vec(color),radius=radius))
    def sign(self,name,text,p,yaw,width,height):
        self.signs.append(dict(id=name,anchor=self.anchor,text=text,position=vec(p),yawDegrees=yaw,width=width,height=height))
    def hero(self,proxy,asset,y=None):
        b=next(b for b in self.data['boxes'] if b['id']==proxy+'-roof');c=b['center'];bottom=c['y']-b['size']['y']/2
        self.anchor=b['id'];self.model(proxy,asset,(c['x'],bottom if y is None else y,c['z']),proxies=[b['id']]);b['collision']=False
    def facade(self,b):
        c,s=b['center'],b['size'];x,y,z=c['x'],c['y'],c['z'];w,h,d=s['x'],s['y'],s['z'];self.anchor=b['id']
        if not b['collision'] or min(w,d)<24 or h<8 or 'roof' in b['id'] or b['id'] in ('plant-mixer-core','unfinished-stair-core') or b['id'].startswith(('edge-','pile-','silo-','fair-stall-','fair-shop-','ticket-office-')):return
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
                dx,dz=q[0]-p[0],q[2]-p[2];length=math.hypot(dx,dz);chosen=None
                for along in (35,60,85):
                    if along>length-15:continue
                    for sign in (-1,1):
                        side=sign*(path['width']/2+5);x,z=p[0]+dx/length*along+dz/length*side,p[2]+dz/length*along-dx/length*side
                        if self.location.landscape_clear(x,z,2):chosen=(x,z);break
                    if chosen:break
                if not chosen:continue
                x,z=chosen
                self.anchor='';height=8 if self.data['metadata']['theme']=='CARNIVAL' else 11
                identity='dress-fixture-'+name;self.data['boxes'].append(dict(id=identity,center=vec((x,height/2,z)),size=vec((.45,height,.45)),material='steel',collision=True,yawDegrees=0));self.anchor=identity
                self.part('road-fixture-head',(x,height,z),(1.8,.6,1.2),'steel');self.part('road-fixture-lens',(x,height-.4,z),(1.2,.15,.8),'light-amber' if self.data['metadata']['theme']!='NEON' else 'light-white')
                if len(self.lights)<24:self.light('road-fixture-'+name,(x,height-.8,z),(1,.8,.5) if self.data['metadata']['theme']!='NEON' else (.6,.8,1),24)
        # Structural side/bottom faces come from canonical TriangleSurface.thickness.
    def construction(self):
        for proxy,asset in [('unfinished-apartments','unfinished-frame'),('concrete-plant','plant-sawtooth'),('warehouse','warehouse-trusses')]:self.hero(proxy,asset)
        self.anchor='unfinished-stair-core'
        # The unfinished lift/stair core has pour lifts and formwork ties, not
        # office windows or a uniformly ruined plaster surface.
        for side in (-1,1):
            x=562.5+side*12.62
            for y in range(6,42,6):self.part('core-pour-joint',(x,y,890),(.14,.09,40),'steel')
            for z in (874,882,890,898,906):
                for y in range(3,42,6):self.part('core-form-tie',(x+side*.1,y,z),(.12,.18,.18),'black')
        for side in (-1,1):
            z=890+side*20.12
            for y in range(6,42,6):self.part('core-end-pour-joint',(562.5,y,z),(25,.09,.14),'steel')
            for x in (554,562.5,571):
                for y in range(3,42,6):self.part('core-end-form-tie',(x,y,z+side*.1),(.18,.18,.12),'black')
        self.part('core-service-hatch',(549.78,2.1,885),(.35,4.2,3.2),'steel')
        self.sign('core-lift-title','ЯДРО 02',(549.55,9,890),-90,13,2)
        for i,(x,z) in enumerate([(1020,370),(1130,375),(1350,440)]):self.anchor='silo-base-'+str(i);self.model('cement-silo-'+str(i),'cement-silo',(x,3,z))
        self.anchor='pit-crane-foundation';x,z=316,691
        for y in range(0,80,10):
            for sign in (-1,1):
                self.beam('crane-leg',(x+sign*4,y,z-4),(x+sign*4,y+10,z-4),.65,'yellow')
            self.beam('crane-brace',(x-4,y,z-4),(x+4,y+10,z-4),.3,'steel')
        self.beam('crane-jib',(x-28,80,z),(x+145,80,z),1.8,'yellow');self.beam('crane-cable',(x+125,80,z),(x+125,5,z),.16,'steel');self.part('crane-counterweight',(x-28,78,z),(12,8,12),'concrete')
        for i,(p,q) in enumerate([((1020,45,370),(1110,27,540)),((1130,45,375),(1110,27,540)),((1350,45,440),(1110,27,540))]):self.anchor='plant-mixer-core';self.beam('cement-feed',p,q,1.8,'steel')
        for x,z in [(1040,540),(1150,680),(1180,270),(390,880)]:self.light(f'industrial-work-{x}',(x,11,z),(1,.82,.57),38)
        self.industrial_backdrop()
    def industrial_backdrop(self):
        # The surrounding industrial estate has identifiable departments and
        # connected roof volumes. All of it is beyond the existing solid boundary.
        self.anchor='exterior';self.group=''
        for name,p,size in [('north',(800,-2,1320),(1600,4,240)),('east',(1710,-2,620),(220,4,1640)),('west',(-110,-2,600),(220,4,1600)),('south',(800,-2,-100),(1600,4,200))]:
            self.part('estate-'+name+'-ground',p,size,'gravel')
        def works(name,origin,width,depth,height,yaw,title,roof_steps,chimneys=()):
            angle=math.radians(yaw);cx,cz=origin
            def point(p):return (cx+math.cos(angle)*p[0]+math.sin(angle)*p[2],p[1],cz-math.sin(angle)*p[0]+math.cos(angle)*p[2])
            def box(label,p,size,mat):self.part(name+'-'+label,point(p),size,mat,rotation=(0,yaw,0))
            box('brick-processing-hall',(0,height/2,0),(width,height,depth),'brick')
            box('concrete-plinth',(0,1.5,-depth/2-.25),(width,3,.5),'cast-concrete')
            box('eave-gutter',(0,height+.25,-depth/2),(width+2,.5,1.4),'steel')
            # Tall factory windows, integral pilasters, loading doors and downpipes
            # distinguish the working front from a featureless perimeter wall.
            count=max(4,round(width/20));pitch=width/count
            for i in range(count):
                x=-width/2+(i+.5)*pitch
                box('window-bay',(x,height*.64,-depth/2-.3),(pitch-3,height*.3,.45),'glass')
                for xx in (-pitch*.32,0,pitch*.32):box('window-mullion',(x+xx,height*.64,-depth/2-.6),(.25,height*.32,.3),'ivory')
                box('window-transom',(x,height*.64,-depth/2-.6),(pitch-2.5,.3,.3),'ivory')
                box('pier',(x-pitch/2,height/2,-depth/2-.65),(1.2,height,1.3),'cast-concrete')
                if i%3==0:
                    box('loading-shutter',(x,4.1,-depth/2-.4),(9,8.2,.7),'blue')
                    box('dock-sill',(x,.55,-depth/2-1.3),(11,1.1,2.6),'cast-concrete')
                    box('dock-canopy',(x,9,-depth/2-2),(13,.6,4),'steel')
                    for y in (1.3,2.6,3.9,5.2,6.5):box('door-rib',(x,y,-depth/2-.82),(8.7,.12,.2),'steel')
                if i%4==1:self.beam(name+'-downpipe',point((x,height,-depth/2-1.05)),point((x,.4,-depth/2-1.05)),.4,'steel')
            for x0,x1,rise in roof_steps:
                box('raised-roof',(sum((x0,x1))/2,height+rise/2,depth*.1),(x1-x0,rise,depth*.66),'steel')
                box('roof-monitor-glazing',(sum((x0,x1))/2,height+rise*.55,-depth*.23-.2),(x1-x0-4,rise*.45,.4),'glass')
                box('roof-monitor-cap',(sum((x0,x1))/2,height+rise+.3,depth*.1),(x1-x0+1,.6,depth*.66+1),'steel')
            # A lower attached service wing and the pipe gallery create a stepped
            # skyline with a real purpose and supports, instead of repeated boxes.
            box('service-annex',(width*.25,height*.25,-depth/2-12),(width*.22,height*.5,24),'blue')
            for x in (-width*.42,-width*.14,width*.14,width*.42):
                box('gallery-pier',(x,5,-depth/2-5),(1.4,10,1.4),'cast-concrete')
            for y in (9,10):self.beam(name+'-utility-main',point((-width*.46,y,-depth/2-5)),point((width*.46,y,-depth/2-5)),.65,'steel')
            for i,(x,h) in enumerate(chimneys):
                self.cylinder(name+'-flue',point((x,height+h/2,depth*.15)),3,h,'steel')
                self.cylinder(name+'-flue-cap',point((x,height+h,depth*.15)),3.5,1,'rust')
            self.sign(name+'-name',title,point((-width*.2,10.5,-depth/2-1.1)),yaw+180,min(width*.38,94),4.2)
        works('north-panel-works',(670,1310),900,150,27,0,'ПАНЕЛЬНЫЙ ЦЕХ  /  ЗАВОД ЖБИ',[(-410,-200,8),(-165,110,13),(160,405,6)])
        works('northeast-powerhouse',(1445,1320),330,150,35,0,'ТЕПЛОЦЕНТРАЛЬ',[(-140,-25,7),(15,140,12)],[(-85,41),(40,54)])
        works('east-rolling-shop',(1745,670),760,210,29,90,'МЕТАЛЛОКОНСТРУКЦИИ',[(-340,-120,12),(-85,100,6),(140,340,17)],[(-230,27)])
        works('south-assembly-shop',(470,-130),680,160,23,180,'СБОРКА ПРОЛЁТОВ',[(-305,-60,8),(-20,160,15),(190,305,5)])
        works('west-precast-works',(-110,930),440,140,25,-90,'ВЫДЕРЖКА БЕТОНА',[(-195,-60,6),(-25,185,11)])
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
        self.anchor='comet-coaster-proxy';self.model('comet-coaster','comet-coaster',(1310,0,315),proxies=['comet-coaster-proxy'],lod=380)
        self.sign('comet-coaster-name','КОМЕТА  /  ПОСАДКА',(1447,2.4,293.4),180,3.6,1.1)
        self.anchor='island-bandstand-proxy';self.model('island-bandstand','island-bandstand',(755,3,715),proxies=['island-bandstand-proxy'])
        for label,x in [('west',733),('east',777)]:
            self.light('island-stage-'+label,(x,10,715),(1,.78,.5),32);self.part('stage-light-fixture',(x,11,715),(1,.5,1),'steel');self.part('stage-light-lens',(x,10.65,715),(.7,.15,.7),'light-amber')
        for tree in self.location.trees:
            self.anchor=tree['id'];scale=tree['scale'];self.model(tree['id'],'park-tree',tree['position'],(scale,scale,scale),proxies=[tree['id']],lod=230)
        self.anchor='ferris-foundation'
        # Two grounded trestles carry a shared axle; twin rims form a spatial
        # truss instead of a single unsupported hoop. Everything below the
        # rotating wheel stays inside the existing foundation's collision.
        for side in (-1,1):
            z=700+side*13
            for sign in (-1,1):
                self.part('ferris-foot',(1300+sign*27,3.35,z),(5,.7,5),'steel')
                self.beam('ferris-support',(1300+sign*27,3.7,z),(1300,65,z),2.1,'ivory')
            self.part('ferris-bearing',(1300,65,z),(5,5,3),'blue')
            self.beam('ferris-trestle-tie',(1288,38,z),(1312,38,z),.65,'steel')
        self.beam('ferris-main-axle',(1300,65,683),(1300,65,717),2.3,'steel')
        self.part('ferris-drive-casing',(1300,62.5,716),(6,7,4),'blue')
        self.part('ferris-boarding-deck',(1300,3.45,704),(16,.9,16),'wood')
        for x in (1292,1308):
            for z in (698,706,712):self.beam('ferris-queue-post',(x,3.9,z),(x,5,z),.13,'steel')
            self.beam('ferris-queue-rail',(x,5,698),(x,5,712),.13,'steel')
        self.groups.append(dict(id='euphoria-ferris-wheel',position=vec((1300,65,700)),motion='ROTATE_Z',period=60,phase=0));self.group='euphoria-ferris-wheel'
        for i in range(64):
            a=i*math.tau/64;b=(i+1)*math.tau/64
            for side in (-1,1):
                p=(math.cos(a)*58,math.sin(a)*58,side*4);q=(math.cos(b)*58,math.sin(b)*58,side*4)
                self.beam('wheel-rim',p,q,.75,'ivory')
                if i%4==0:self.beam('wheel-spoke',(0,0,side*4),p,.32,'steel')
                if i%2==0:self.beam('wheel-rim-cross-brace',p,(q[0],q[1],-side*4),.18,'steel')
            if i%4==0:
                self.beam('wheel-suspension-axle',(math.cos(a)*58,math.sin(a)*58,-4.7),(math.cos(a)*58,math.sin(a)*58,4.7),.5,'steel')
                self.part('wheel-rim-lamp',(math.cos(a)*58,math.sin(a)*58,-4.9),(.45,.45,.2),'light-amber')
        self.part('wheel-hub',(0,0,0),(6,6,9),'blue','CYLINDER')
        for i in range(16):
            a=i*math.tau/16;x,y=math.cos(a)*58,math.sin(a)*58
            self.group='euphoria-ferris-cabin-'+str(i)
            self.groups.append(dict(id=self.group,position=vec((1300,65,700)),motion='ORBIT_Z',period=60,phase=i/16,radius=58))
            color='faded-red' if i%2 else 'blue'
            self.part('cabin-lower-shell',(x,y-2.2,0),(5,1.1,4.8),color)
            self.part('cabin-roof',(x,y-.45,0),(5.3,.35,5.1),'ivory')
            self.part('cabin-front-glazing',(x,y-1.15,-2.42),(4.55,1.25,.15),'glass')
            self.part('cabin-back-glazing',(x,y-1.15,2.42),(4.55,1.25,.15),'glass')
            for sign in (-1,1):
                self.part('cabin-side-glazing',(x+sign*2.42,y-1.15,0),(.15,1.25,4.55),'glass')
                for z in (-2.45,2.45):self.part('cabin-window-pillar',(x+sign*2.42,y-1.15,z),(.19,1.5,.19),'ivory')
            self.part('cabin-door-divider',(x,y-1.45,-2.55),(.18,2.15,.16),'ivory')
            self.part('cabin-door-handle',(x+.32,y-1.65,-2.65),(.12,.35,.1),'steel')
            self.beam('cabin-suspension-bracket',(x,y,-4),(x,y-.4,-2),.22,'steel')
            self.beam('cabin-suspension-bracket',(x,y,4),(x,y-.4,2),.22,'steel')
        self.group='';self.anchor='carousel-base';self.cylinder('carousel-roof',(925,11,155),25,2,'ivory')
        for i in range(12):self.cylinder('carousel-pole',(925+math.cos(i*math.tau/12)*20,6,155+math.sin(i*math.tau/12)*20),.25,10,'yellow')
        # Small furnished pockets outside all road corridors, grouped by use.
        self.anchor=''
        for i,(x,z) in enumerate([(145,330),(255,365),(280,610),(350,750),(450,825),(575,930),(905,885),(1015,760),(1045,550),(970,395),(550,425),(225,960),(460,1165),(1100,1140),(1340,935)]):
            if not self.location.landscape_clear(x,z,5):continue
            identity='dress-park-seat-'+str(i);self.data['boxes'].append(dict(id=identity,center=vec((x,.6,z)),size=vec((6,1.2,1.2)),material='wood',collision=True,yawDegrees=0));self.anchor=identity
            self.part('bench-back',(x,1.3,z+.6),(6,.8,.25),'wood');self.part('litter-bin',(x+4,.8,z),(1.2,1.6,1.2),'steel')
            self.part('planting-bed',(x,0.35,z+4),(9,.7,4),'earth')
            for offset in (-3,0,3):self.part('flower-shrub',(x+offset,1,z+4),(2.4,1.6,2.4),'park-leaf','SPHERE')
        # Lake-edge planting follows the actual shore, interrupted at bridge entries.
        shore=[(490,470),(620,400),(860,420),(1000,540),(1020,700),(920,870),(700,920),(520,800),(450,620)]
        for i,(aa,bb) in enumerate(zip(shore,shore[1:]+shore[:1])):
            for n in range(1,8):
                t=n/8;x=aa[0]+(bb[0]-aa[0])*t;z=aa[1]+(bb[1]-aa[1])*t;dx,dz=x-740,z-650;length=math.hypot(dx,dz);x+=dx/length*7;z+=dz/length*7
                if not self.location.landscape_clear(x,z,3):continue
                self.anchor='';self.part('shore-reeds',(x,.6,z),(3,1.2,2),'park-leaf','SPHERE')
                if n%3==0:self.part('shore-rock',(x+2,.4,z+1),(2.5,.8,2),'gravel','SPHERE')
        for i,(x,z) in enumerate([(1085,870),(1360,1170),(1110,1090)]):
            if self.location.landscape_clear(x,z,10):
                self.data['boxes'].append(dict(id='dress-park-parts-cradle-'+str(i),center=vec((x,1.5,z)),size=vec((14,3,7)),material='steel',collision=True,yawDegrees=12))
                self.part('stored-ride-module',(x,4,z),(8,4,5),'faded-red');self.beam('stored-ride-arm',(x-7,4,z),(x+7,4,z),1,'yellow')
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
        for group in self.groups:group.setdefault('radius',0)
        write_text_atomic(OUT/(self.location.resource+'.json'),json.dumps(self.data,ensure_ascii=False,indent=2)+'\n')
        self.location.write_design()
        data=dict(schemaVersion=2,arenaId=self.data['id'],source='src/tools/author_arena_art.py; src/tools/author_arena_models.py; src/tools/dress_construction.py; src/tools/dress_neon.py; src/tools/dress_parking.py; src/tools/dress_carnival.py; original revision 3 architecture',license='Original project geometry; locally vendored materials retain their individual provenance.',groups=self.groups,parts=self.parts,models=self.models,lights=self.lights,signs=self.signs)
        write_text_atomic(OUT/('arena-art-'+self.data['id'].replace('_','-')+'.json'),json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n');print(self.data['id'],len(self.parts),'parts',len(self.models),'models',len(self.lights),'lights')
if __name__=='__main__':
    for factory,method in [(construction,'construction'),(neon,'neon'),(carnival,'carnival')]:
        scene=Scene(factory());scene.common();getattr(scene,method)()
        if method=='construction':
            from dress_construction import dress
            dress(scene)
        elif method=='neon':
            from dress_neon import dress
            dress(scene)
        elif method=='carnival':
            from dress_carnival import dress
            dress(scene)
        scene.save()
    write_review_routes()
    expand_yard()
