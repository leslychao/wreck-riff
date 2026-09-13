"""Original scenery for the three large campaign locations; explicit offline tool.

Uses the canonical layout, existing local texture sets, and deterministic original
geometry. Revision 1 scenery and authoring source live in assets/campaign-layouts-revision1.
Dead Air Yard retains its existing scene without any regeneration.
"""
import json
import math
from author_campaign_arenas import OUT,vec,construction,neon,carnival

class Scene:
    def __init__(self,location):
        self.location=location
        self.data=json.loads((OUT/(location.resource+'.json')).read_text(encoding='utf-8'))
        self.parts=[];self.groups=[];self.anchor='';self.group=''
    def part(self,name,pos,size,material,shape='BOX',rotation=(0,0,0)):
        self.parts.append(dict(id=f'{name}-{len(self.parts):05}',group=self.group,anchor=self.anchor,shape=shape,material=material,position=vec(pos),size=vec(size),rotation=vec(rotation)))
    def cylinder(self,name,p,r,h,material):self.part(name,p,(r*2,r*2,h),material,'CYLINDER',(90,0,0))
    def beam(self,name,a,b,width,material):
        dx,dy,dz=(b[i]-a[i] for i in range(3));length=math.sqrt(dx*dx+dy*dy+dz*dz)
        self.part(name,tuple((a[i]+b[i])/2 for i in range(3)),(width,width,length),material,'CYLINDER',(-math.degrees(math.asin(dy/length)),math.degrees(math.atan2(dx,dz)),0))
    def motion(self,name,p,kind,period=12):
        self.groups.append(dict(id=name,position=vec(p),motion=kind,period=period,phase=0));self.group=name
    def facade(self,box):
        self.anchor=box['id'];c,s=box['center'],box['size'];x,y,z=c['x'],c['y'],c['z'];w,h,d=s['x'],s['y'],s['z']
        if min(w,d)<10 or h<5:return
        for side in (-1,1):
            face=z+side*(d/2+.015)
            self.part('building-coping',(x,y+h/2-.5,face),(w,.4,.03),'steel')
            for floor in range(max(1,min(10,int(h/7)))):
                yy=y-h/2+4+floor*7
                if yy+2>y+h/2:continue
                for column in range(max(1,int(w/11))):
                    xx=x-w/2+5+column*11
                    self.part('window-recess',(xx,yy,face),(4,2.5,.03),'black')
                    color='light-cyan' if self.data['metadata']['theme']=='NEON' else 'light-amber'
                    if (column+floor)%4!=0:self.part('window-pane',(xx,yy,face+side*.016),(3.2,1.6,.015),color)
            for xx in (x-w/2+1,x+w/2-1):self.part('facade-pier',(xx,y,face),(1,h,.03),'dark-concrete')
        self.part('roof-mechanical',(x,y+h/2+2,z),(w*.3,4,d*.3),'black')
        self.cylinder('roof-extractor',(x+w*.25,y+h/2+2,z),2,3,'steel')
    def common(self):
        self.district_paving()
        for b in self.data['boxes']:
            if b['id'].find('-block-')>=0 or b['id'].startswith('silo-base'):self.facade(b)
        for b in self.data['boxes']:
            if not b['id'].endswith('-wall'):continue
            self.anchor=b['id'];c,s=b['center'],b['size'];side=-1 if '-north-' in b['id'] else 1
            # Structural wall panels stay flush; drive-through portals retain their full clearance.
            for x in range(int(c['x']-s['x']/2+10),int(c['x']+s['x']/2-8),18):
                self.part('hall-column-seam',(x,c['y'],c['z']+side*1.015),(.12,s['y']-.2,.025),'black')
                self.part('clerestory',(x,s['y']*.78,c['z']+side*1.024),(11,3,.02),'black')
                self.part('clerestory-glass',(x,s['y']*.78,c['z']+side*1.04),(9,1.7,.018),'light-white')
        # Authored roads are texture-backed flush overlays on known collision surfaces.
        done=set();junctions={}
        for edge in self.data['edges']:
            if edge['type'] in ('LAUNCH','OPENABLE'):continue
            a,b=(self.data['nodes'][edge[k]] for k in ('from','to'))
            p,q=a['position'],b['position'];key=tuple(sorted((a['id'],b['id'])))
            if key in done:continue
            done.add(key)
            dx,dy,dz=q['x']-p['x'],q['y']-p['y'],q['z']-p['z'];horizontal=math.hypot(dx,dz);length=math.hypot(horizontal,dy)
            angle=-math.degrees(math.atan2(dz,dx));slope=math.degrees(math.atan2(dy,horizontal));rotation=(0,angle,slope)
            self.anchor=a['surfaceId']
            centre=((p['x']+q['x'])/2,(p['y']+q['y'])/2+.018,(p['z']+q['z'])/2)
            self.part('road-shoulder',centre,(length+.25,.01,edge['width']+4),'road-shoulder',rotation=rotation)
            self.part('road-asphalt',centre,(length+.25,.012,edge['width']),'road-surface',rotation=rotation)
            for sign in (-1,1):
                side=sign*(edge['width']/2-.65)
                self.part('road-edge-line',(centre[0]+dz/horizontal*side,centre[1]+.007,centre[2]-dx/horizontal*side),
                    (length,.004,.32),'road-marking',rotation=rotation)
            for n in range(max(1,int(length/21))):
                t=(n+.5)/max(1,int(length/21));x,y,z=p['x']+dx*t,p['y']+dy*t,p['z']+dz*t
                # Nine-metre markings remain part of the structural LOD for a legible whole-map road network.
                self.part('lane-dash',(x,y+.027,z),(9,.004,.32),'road-marking',rotation=rotation)
            if abs(dy)<.001:
                for node in (a,b):junctions[node['id']]=max(junctions.get(node['id'],0),edge['width']/2)
            if abs(dy)<.001 and a['id']%4==0:
                self.part('asphalt-repair',((p['x']+q['x'])/2,p['y']+.029,(p['z']+q['z'])/2),(3,.008,5),'road-patch','PATCH',(0,angle,0))
        for node_id,radius in junctions.items():
            node=self.data['nodes'][node_id];p=node['position'];x,y,z=p['x'],p['y'],p['z']
            if not self.supported_tile(x,z,y,radius*2+4):continue
            self.anchor=node['surfaceId']
            self.cylinder('road-shoulder-junction',(x,y+.018,z),radius+2,.01,'road-shoulder')
            self.cylinder('road-asphalt-junction',(x,y+.02,z),radius,.012,'road-surface')
    def supported_tile(self,x,z,y,size):
        for dx,dz in [(0,0),(-.5,-.5),(.5,-.5),(.5,.5),(-.5,.5)]:
            try:self.location.surface((x+dx*size,y,z+dz*size))
            except ValueError:return False
        return True
    def district_paving(self):
        # Broad, flush material regions distinguish actual facilities without adding walls or collision.
        sites={
            'CONSTRUCTION':[(380,580,360,580,-14,'district-earth'),(420,910,730,885,0,'district-service'),
                (930,1310,440,650,0,'district-slate'),(910,1320,95,265,0,'district-earth'),(910,1230,820,1040,16,'district-service')],
            'NEON':[(790,1130,220,530,0,'district-slate'),(285,730,385,625,0,'district-warm'),
                (340,680,810,1160,0,'district-garden'),(805,995,545,855,-12,'district-service'),(1130,1520,840,1150,0,'district-service')],
            'CARNIVAL':[(95,500,70,320,0,'district-warm'),(170,455,335,565,0,'district-fair'),
                (640,810,580,750,3,'district-warm'),(995,1340,455,810,0,'district-slate'),
                (135,565,980,1190,0,'district-fair'),(1025,1400,855,1130,0,'district-service')]}
        for x1,x2,z1,z2,y,material in sites[self.data['metadata']['theme']]:
            for x in range(x1+15,x2,30):
                for z in range(z1+15,z2,30):
                    if not self.supported_tile(x,z,y,30):continue
                    self.anchor=self.location.surface((x,y,z))
                    self.part('district-paving',(x,y+.012,z),(30,.004,30),material)
    def crane(self,name,x,z,base=0,height=85,reach=110,anchor=None):
        # Gantry parts are high above drivable space; solid tower base is canonical geometry.
        self.anchor=anchor or ('pit-crane-foundation' if name=='pit' else 'silo-base-1')
        for y in range(int(base),int(base+height),10):
            for sign in (-1,1):
                self.beam('crane-lattice-leg',(x+sign*5,y,z-5),(x+sign*5,y+10,z-5),.8,'yellow')
                self.beam('crane-lattice-brace',(x-5,y,z-5),(x+5,y+10,z-5),.35,'steel')
                self.beam('crane-lattice-leg',(x+sign*5,y,z+5),(x+sign*5,y+10,z+5),.8,'yellow')
                self.beam('crane-lattice-brace',(x-5,y,z+5),(x+5,y+10,z+5),.35,'steel')
        self.beam('crane-boom',(x-reach/3,base+height,z),(x+reach,base+height,z),2,'yellow')
        self.beam('crane-tension',(x,base+height+15,z),(x+reach,base+height,z),.35,'steel')
        self.part('crane-counterweight',(x-reach/3,base+height,z),(14,7,12),'concrete')
    def construction(self):
        # Industrial outskirts are asymmetrical works complexes, all beyond the solid perimeter.
        self.anchor='exterior'
        for x,z,w,d,h in [(-105,250,180,320,25),(260,-130,410,180,32),(1110,-155,550,200,22),
                           (1730,910,210,430,36),(980,1320,450,180,18)]:
            self.part('external-factory',(x,h/2,z),(w,h,d),'rust')
            for n in range(3):
                self.part('factory-sawtooth-roof',(x+(n-1)*w/3,h+4,z),(w/3-2,8,d),'steel',rotation=(0,0,7))
        for x,z in [(1480,-95),(1535,-95),(1590,-95),(-95,960),(-155,960),(-215,960)]:
            self.cylinder('external-cement-silo',(x,45,z),24,90,'ivory')
            self.cylinder('silo-crown',(x,93,z),17,6,'steel')
            self.beam('silo-feed-pipe',(x,80,z),(x,80,z+60),3,'rust')
        for x,z,h in [(80,-170,160),(1770,980,130),(1810,980,190)]:
            self.cylinder('industrial-chimney',(x,h/2,z),10,h,'rust')
            for y in range(35,h,35):self.cylinder('chimney-band',(x,y,z),10.2,9,'ivory')
        self.crane('outer-rail',240,1300,0,95,150,'exterior')
        self.crane('outer-yard',1690,240,0,140,110,'exterior')
        self.crane('pit',355,330,0,95,120);self.crane('plant',1120,390,48,65,100)
        self.anchor='unfinished-floor-32'
        for x in range(440,900,25):
            for z in (780,840):
                self.part('unfinished-frame',(x,37,z),(1.2,30,1.2),'concrete')
                self.part('exposed-rebar',(x,55,z),(.12,7,.12),'rust')
        self.anchor='interchange-deck'
        for x in range(910,1240,18):self.part('roof-expansion-joint',(x,16.024,930),(.06,.004,245),'roof-seam')
        for b in self.data['boxes']:
            if '-block-' not in b['id']:continue
            self.anchor=b['id'];c,s=b['center'],b['size']
            for n in range(4):self.part('construction-panel',(c['x'],c['y']+s['y']/2+.2+n*.65,c['z']),(s['x']*.85,.4,s['z']*.85),'concrete')
    def neon(self):
        self.anchor='exterior'
        # Unequal joined city blocks continue four different street edges beyond the playable district.
        for index,(x,z,w,d,h) in enumerate([(-125,280,210,420,92),(-160,1120,260,380,180),(270,-135,370,220,70),
            (650,-185,260,300,230),(1140,-160,420,250,116),(1610,-120,280,210,58),
            (1970,240,280,360,205),(1960,890,250,550,82),(1910,1350,160,230,165),
            (340,1540,580,240,45),(890,1580,310,300,260),(1470,1540,490,240,126)]):
            self.part('city-backdrop-podium',(x,15,z),(w+20,30,d+20),'dark-concrete')
            self.part('city-backdrop-tower',(x,h/2,z),(w,h,d),'black' if index%3 else 'blue')
            self.part('city-backdrop-setback',(x-w*.15,h+18,z),(w*.58,36,d*.6),'dark-concrete')
            color='light-cyan' if index%2 else 'light-magenta'
            for sign in (-1,1):
                face=z+sign*(d/2+.03)
                for level in range(1,int(h/11)):
                    self.part('city-ribbon-window',(x,level*11,face),(w*.86,.75,.035),color)
                self.part('city-sign',(x-w*.35,h*.58,face+sign*.02),(5,h*.55,.04),color)
        for b in self.data['boxes']:
            if '-block-' not in b['id']:continue
            self.anchor=b['id'];c,s=b['center'],b['size'];x,y,z=c['x'],c['y'],c['z'];top=y+s['y']/2
            color='light-cyan' if int(x)%3 else 'light-magenta'
            for side in (-1,1):self.part('neon-tower-trim',(x+side*(s['x']/2-.1),y,z-s['z']/2-.028),(.16,s['y'],.035),color)
            self.part('neon-tower-roof',(x,top+.2,z),(s['x'],.2,s['z']),color)
        self.anchor='shopping-passage-north-wall';self.motion('market-screen',(510,19,466.9),'SCREEN',8)
        for i in range(12):self.part('market-screen-pixel',((i-6)*6,0,0),(4,9,.03),'light-magenta' if i%3 else 'light-cyan')
        self.group=''
    def carnival(self):
        self.anchor='exterior'
        # The park runs into wooded banks and an open northern shore, rather than a city-box horizon.
        for i,(x,z,w,d,h) in enumerate([(-120,170,220,360,55),(-175,575,320,530,90),(-105,1110,180,400,48),
             (245,-170,600,280,60),(890,-170,770,290,85),(1610,310,190,430,55),(1640,880,250,640,100)]):
            self.part('wooded-bank',(x,-12,z),(w,h,d),'park-ground','SPHERE')
            for n in range(9):
                tx=x+math.cos(n*2.1+i)*w*.32;tz=z+math.sin(n*1.7+i)*d*.34
                ty=9+(n*7)%18
                self.cylinder('park-tree-trunk',(tx,ty/2,tz),1.3,ty,'rust')
                self.part('park-tree-crown',(tx,ty+5,tz),(18+(n%3)*5,20+(n%4)*3,22),'park-leaf','SPHERE')
        self.part('outer-lake-coast',(830,-3,1560),(1600,3,450),'cyan')
        for x,z,w in [(110,1355,220),(360,1430,320),(1370,1410,280)]:
            self.part('shore-bank',(x,0,z),(w,30,100),'park-ground','SPHERE')
        self.anchor='ferris-foundation'
        for side in (-1,1):self.beam('ferris-support',(1125+side*20,4,650),(1125,75,650),2,'ivory')
        self.motion('euphoria-ferris-wheel',(1125,75,650),'ROTATE_Z',60)
        radius=65
        for n in range(32):
            angle=n*math.tau/32;next_angle=(n+1)*math.tau/32
            p=(math.cos(angle)*radius,math.sin(angle)*radius,0);q=(math.cos(next_angle)*radius,math.sin(next_angle)*radius,0)
            self.beam('ferris-rim',p,q,1.3,'ivory')
            if n%2==0:
                self.beam('ferris-spoke',(0,0,0),p,.35,'steel');self.part('ferris-cabin',p,(7,5,5),'faded-red' if n%4 else 'blue')
        self.group='';self.anchor='circus-roof'
        for n in range(20):self.part('circus-canopy',(160+n*20,33,1090),(10,1,68),'ivory' if n%2 else 'faded-red')
        self.anchor='carousel-base'
        self.cylinder('carousel-crown',(310,12,265),24,2,'ivory')
        for n in range(12):
            angle=n*math.tau/12;x,z=310+math.cos(angle)*21,265+math.sin(angle)*21
            self.cylinder('carousel-pole',(x,7,z),.35,10,'yellow')
        for b in self.data['boxes']:
            if '-block-' not in b['id']:continue
            self.anchor=b['id'];c,s=b['center'],b['size']
            for side in (-1,1):
                self.part('fair-awning',(c['x'],c['y']+s['y']/2-1,c['z']+side*s['z']/2),(s['x'],2,.025),'faded-red')
                for n in range(8):self.part('fair-marquee',(c['x']-s['x']/2+4+n*7,c['y']+s['y']/2+.2,c['z']+side*s['z']/2),(.5,.5,.05),'light-amber')
    def save(self):
        data=dict(schemaVersion=1,arenaId=self.data['id'],source='src/tools/author_arena_art.py; original Wreck Riff large locations, 2026-09-13',
            license='Original project-authored geometry; uses existing locally licensed surface materials.',groups=self.groups,parts=self.parts)
        path=OUT/('arena-art-'+self.data['id'].replace('_','-')+'.json')
        path.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8');print(path.name,len(self.parts),'parts')

if __name__=='__main__':
    for factory,method in [(construction,'construction'),(neon,'neon'),(carnival,'carnival')]:
        location=factory();scene=Scene(location);scene.common();getattr(scene,method)();scene.save()
