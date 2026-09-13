"""Authored city sidewalks, front doors, service yards and courtyard furniture.

Road and building footprints decide where each object fits. There is no random
building grid. Pavement replaces the underlying ground triangles, never overlays
another visible floor. All substantial street furniture has the same visual and
collision geometry in ArenaDefinition.
"""
import math
from author_campaign_arenas import ccw, cut, area, vec, inside


def _rectangle(x, z, width, depth):
    return ccw([(x-width/2,z-depth/2),(x+width/2,z-depth/2),
                (x+width/2,z+depth/2),(x-width/2,z+depth/2)])


def _box_polygon(box):
    c,s=box['center'],box['size'];angle=math.radians(box['yawDegrees'])
    return ccw([(c['x']+math.cos(angle)*x+math.sin(angle)*z,
                 c['z']-math.sin(angle)*x+math.cos(angle)*z)
                for x,z in _rectangle(0,0,s['x'],s['z'])])


def _road_polygons(scene):
    result=[]
    for path in scene.location.paths:
        for first,last in zip(path['names'],path['names'][1:]):
            a,b=scene.location.points[first],scene.location.points[last]
            if abs(a[1])>.1 or abs(b[1])>.1:continue
            dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
            if length<.01:continue
            vx,vz=dz/length,-dx/length;half=path['width']/2
            poly=ccw([(a[0]+vx*half,a[2]+vz*half),(b[0]+vx*half,b[2]+vz*half),
                      (b[0]-vx*half,b[2]-vz*half),(a[0]-vx*half,a[2]-vz*half)])
            result.append((poly,a,b,path))
    return result


def _faces(polygons, height, material):
    vertices=[];indices=[];materials=[]
    for polygon in polygons:
        polygon=ccw(polygon)
        if len(polygon)<3 or abs(area(polygon))<.001:continue
        base=len(vertices);vertices.extend(vec((x,height,z)) for x,z in polygon)
        for index in range(1,len(polygon)-1):
            if abs(area([polygon[0],polygon[index],polygon[index+1]]))<.0001:continue
            indices.extend((base,base+index+1,base+index));materials.append(material)
    return vertices,indices,materials


def _sidewalks(scene):
    roads=_road_polygons(scene);claimed=[];walks=[]
    buildings=[_box_polygon(b)
               for b in scene.data['boxes'] if b['collision'] and b['size']['y']>2]
    cuts=[road[0] for road in roads]+buildings+scene.location.holes
    for number,(_,a,b,path) in enumerate(roads):
        if path['width']<26 or path['kind']!='ROAD':continue
        dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
        if length<65:continue
        ux,uz=dx/length,dz/length;vx,vz=uz,-ux
        for side in (-1,1):
            inner,outer=path['width']/2+.05,path['width']/2+5.5
            polygon=ccw([(a[0]+ux*18+vx*inner*side,a[2]+uz*18+vz*inner*side),
                         (b[0]-ux*18+vx*inner*side,b[2]-uz*18+vz*inner*side),
                         (b[0]-ux*18+vx*outer*side,b[2]-uz*18+vz*outer*side),
                         (a[0]+ux*18+vx*outer*side,a[2]+uz*18+vz*outer*side)])
            pieces=cut([polygon],cuts+claimed)
            if not pieces:continue
            vertices,indices,_=_faces(pieces,.14,'district-slate')
            if not indices:continue
            identity=f'dress-neon-sidewalk-{number}-{side}'
            mesh=dict(id=identity,vertices=vertices,indices=indices,material='district-slate',collision=True,triangleMaterials=[],thickness=.14)
            scene.data['meshes'].append(mesh)
            scene.data['surfaces'].append(dict(id=identity,geometryId=identity,level=0,grip=1))
            walks.extend(pieces);claimed.extend(pieces)
    # Remove terrain beneath the concrete, including normal/specular shading.
    # The 14 cm curb does not rely on a depth offset to hide a duplicate sheet.
    for mesh in scene.data['meshes']:
        if not mesh['id'].startswith('ground-'):continue
        vertices=[];indices=[];materials=[]
        for offset in range(0,len(mesh['indices']),3):
            tri=[mesh['vertices'][mesh['indices'][offset+i]] for i in range(3)]
            polygon=[(v['x'],v['z']) for v in tri]
            material=mesh['triangleMaterials'][offset//3] if mesh['triangleMaterials'] else mesh['material']
            vv,ii,mm=_faces(cut([ccw(polygon)],walks),tri[0]['y'],material)
            base=len(vertices);vertices.extend(vv);indices.extend(base+i for i in ii);materials.extend(mm)
        mesh.update(vertices=vertices,indices=indices,triangleMaterials=materials if len(set(materials))>1 else [])
    empty={m['id'] for m in scene.data['meshes'] if not m['indices']}
    scene.data['meshes'][:]=[m for m in scene.data['meshes'] if m['id'] not in empty]
    scene.data['surfaces'][:]=[s for s in scene.data['surfaces'] if s['geometryId'] not in empty]


class City:
    def __init__(self,scene):self.scene=scene;self.count=0;self.installed=[]
    def clear(self,x,z,radius,ignore=(),floor=0,height=6.5):
        # A roof above an interior is not an occupied ground footprint. Check
        # height as well as plan position, keeping every actual road corridor open.
        for path in self.scene.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.scene.location.points[first],self.scene.location.points[last]
                if min(a[1],b[1])>floor+height or max(a[1],b[1])<floor-2:continue
                dx,dz=b[0]-a[0],b[2]-a[2]
                t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
                if math.hypot(x-a[0]-t*dx,z-a[2]-t*dz)<path['width']/2+radius+3:return False
        if floor==0 and any(inside((x,z),hole) for hole in self.scene.location.holes):return False
        for pickup in self.scene.data['pickups']:
            p=pickup['position']
            if abs(p['y']-floor)<4 and math.hypot(p['x']-x,p['z']-z)<radius+8:return False
        for box in self.scene.data['boxes']:
            if not box['collision'] or box['id'] in ignore:continue
            c,s=box['center'],box['size']
            if c['y']-s['y']/2>floor+height or c['y']+s['y']/2<floor+.2:continue
            angle=math.radians(box['yawDegrees']);dx,dz=x-c['x'],z-c['z']
            if abs(math.cos(angle)*dx-math.sin(angle)*dz)<s['x']/2+radius and abs(math.sin(angle)*dx+math.cos(angle)*dz)<s['z']/2+radius:return False
        return True
    def solid(self,name,position,size,material='concrete'):
        identity=f'dress-neon-{name}-{self.count}';self.count+=1
        self.scene.data['boxes'].append(dict(id=identity,center=vec(position),size=vec(size),
            material=material,collision=True,yawDegrees=0))
        self.scene.anchor=identity;return identity
    def garden(self,name,x,z,width=10,depth=5):
        if not self.clear(x,z,math.hypot(width,depth)/2):return
        self.solid(name+'-planter',(x,.45,z),(width,.9,depth),'dark-concrete')
        self.scene.part(name+'-soil',(x,.93,z),(width-.6,.12,depth-.6),'earth')
        for fraction in (-.3,0,.3):
            self.scene.model(name+'-tree-'+str(self.count)+'-'+str(fraction),'park-tree',
                (x+width*fraction,.95,z),(.35,.5,.35),lod=200)
        self.solid(name+'-bench',(x,.7,z-depth/2-1.6),(width*.7,1.4,1.1),'wood')
        self.scene.part(name+'-seat',(x,1.45,z-depth/2-1.6),(width*.7+.2,.16,1.2),'wood')
    def shelter(self,name,x,z):
        if not self.clear(x,z,9):return
        for sign in (-1,1):self.solid(name+'-column',(x+sign*6,2.4,z),( .35,4.8,.35),'steel')
        self.solid(name+'-canopy',(x,5,z),(14,.4,5),'steel')
        self.solid(name+'-rear-glass',(x,2.2,z+2),(12,4.4,.22),'glass')
        self.solid(name+'-seat',(x,1,z+1),(8,.45,1.1),'wood')
        self.scene.part(name+'-light',(x,4.74,z),(9,.1,.35),'light-white')
        self.scene.light(name+'-illumination',(x,4,z),( .65,.8,1),16)
    def parking_sign(self,name,x,z):
        if not self.clear(x,z,5):return
        self.solid(name+'-mast',(x,4,z),(.6,8,.6),'steel')
        self.solid(name+'-board',(x,9,z),(6,6,.4),'blue')
        for pos,size in [((-1.4,0),(.6,4)),((-.2,1.7),(2.9,.6)),((-.2,0),(2.9,.6)),((1.2,.85),(.6,2.3))]:
            self.scene.part(name+'-letter-p',(x+pos[0],9+pos[1],z-.26),(size[0],size[1],.12),'light-white')
    def service_yard(self,name,x,z):
        if not self.clear(x,z,16):return
        for index in range(3):
            xx=x-8+index*8
            self.solid(name+'-switchgear',(xx,1.6,z),(5,3.2,6),'steel')
            for offset in (-1.3,0,1.3):self.scene.cylinder(name+'-bushing',(xx+offset,4.2,z),.25,2,'ivory')
            self.scene.part(name+'-vent',(xx,1.7,z-3.15),(3.8,1.6,.25),'black')
        for sign in (-1,1):self.solid(name+'-impact-barrier',(x+sign*14,.55,z),(1,1.1,14),'yellow')

    def pocket(self,name,candidates,kind,floor=0):
        dimensions={'news':(10,7),'cafe':(14,8),'directory':(5,4),'workbench':(10,7),
                    'pump':(9,8),'charging':(7,5),'garden':(12,6)}
        width,depth=dimensions[kind];radius=math.hypot(width,depth)/2
        for x,z in candidates:
            if not self.clear(x,z,radius,floor=floor):continue
            self.installed.append(dict(id=name,kind=kind,position=[x,floor,z]))
            if kind in ('news','cafe'):
                self.solid(name+'-back',(x,floor+2.1,z+depth/2-.25),(width,4.2,.5),'brick' if kind=='cafe' else 'steel')
                for side in (-1,1):self.solid(name+'-end',(x+side*(width/2-.25),floor+2.1,z),(.5,4.2,depth),'steel')
                self.solid(name+'-counter',(x,floor+1.05,z-depth/2+.65),(width-.7,2.1,1.3),'wood')
                self.solid(name+'-awning',(x,floor+4.5,z),(width+1,.5,depth+1),'blue' if kind=='news' else 'ivory')
                self.scene.part(name+'-sign',(x,floor+3.45,z-depth/2-.2),(width*.75,.8,.25),'light-cyan' if kind=='news' else 'light-amber')
                for offset in (-.3,0,.3):
                    self.scene.part(name+'-menu',(x+width*offset,floor+2.4,z+depth/2-.6),(width*.22,1.1,.18),'ivory')
                    self.scene.part(name+'-display',(x+width*offset,floor+1.4,z-depth/2+.65),(width*.2,.55,.6),'faded-red' if kind=='news' else 'steel')
                self.scene.light(name+'-counter-light',(x,floor+3.9,z-2),(1,.72,.42) if kind=='cafe' else (.5,.8,1),14)
            elif kind=='directory':
                self.solid(name+'-base',(x,floor+.3,z),(4,.6,2),'dark-concrete')
                self.solid(name+'-frame',(x,floor+2.7,z),(3.4,4.8,.65),'steel')
                self.scene.part(name+'-screen',(x,floor+3,z-.4),(2.9,3.4,.18),'blue')
                for index in range(4):self.scene.part(name+'-destinations',(x,floor+4.1-index*.65,z-.53),(2.3,.16,.08),'light-cyan' if index%2 else 'light-white')
            elif kind=='workbench':
                for side in (-1,1):self.solid(name+'-leg',(x+side*3,floor+1.05,z),(1.1,2.1,4),'steel')
                self.solid(name+'-top',(x,floor+2.2,z),(9,.3,5),'wood')
                self.solid(name+'-tool-board',(x,floor+3.2,z+2),(9,2,.35),'steel')
                for offset in (-3,-1,1,3):self.scene.part(name+'-tools',(x+offset,floor+3.4,z+1.7),(.22,1.15,.15),'ivory')
                self.scene.cylinder(name+'-motor',(x,floor+2.7,z),.65,1,'blue')
            elif kind=='pump':
                self.solid(name+'-skid',(x,floor+.3,z),(8,.6,6),'steel')
                for offset in (-2,2):
                    self.solid(name+'-motor-hull',(x+offset,floor+1.5,z),(2.8,2.4,3.5),'blue')
                    self.scene.cylinder(name+'-riser',(x+offset,floor+3.7,z+1),.55,4,'steel')
                    self.scene.beam(name+'-pipe',(x+offset,floor+5.6,z+1),(x+offset,floor+5.6,z-2),.7,'steel')
                    self.scene.part(name+'-pressure-dial',(x+offset,floor+3,z-.7),(.7,.7,.3),'ivory')
            elif kind=='charging':
                for offset in (-2,2):
                    self.solid(name+'-terminal',(x+offset,floor+1.6,z),(1.2,3.2,1.1),'steel')
                    self.scene.part(name+'-indicator',(x+offset,floor+2.3,z-.65),(.8,.45,.16),'light-cyan')
                    self.scene.beam(name+'-cable',(x+offset+.55,floor+2.4,z),(x+offset+1.25,floor+.4,z),.14,'black')
                self.solid(name+'-wheel-stop',(x,floor+.22,z-2.2),(6,.44,.55),'yellow')
            else:
                self.solid(name+'-bed',(x,floor+.6,z),(width,1.2,depth),'dark-concrete')
                self.scene.part(name+'-soil',(x,floor+1.24,z),(width-.5,.12,depth-.5),'earth')
                for offset in (-3,3):self.scene.model(name+'-tree-'+str(offset),'park-tree',(x+offset,floor+1.3,z),(.4,.7,.4),lod=220)
            self.scene.anchor='';return
        raise ValueError('No safe authored city pocket: '+name)


def _street_faces(scene):
    # East/west faces used to be entirely blank even on occupied office blocks.
    # Continuous floor bands and deeper ground-floor shopfronts identify the building.
    for building in list(scene.data['boxes']):
        c,s=building['center'],building['size'];w,h,d=s['x'],s['y'],s['z']
        if not building['collision'] or min(w,d)<24 or h<8 or 'roof' in building['id'] or building['id'].startswith(('edge-','dress-')):continue
        scene.anchor=building['id'];base=c['y']-h/2
        for side in (-1,1):
            x=c['x']+side*(w/2+.24)
            for floor in range(max(1,min(32,int(h/4.5)))):
                yy=base+3+floor*4.5
                scene.part('city-side-glazing',(x,yy,c['z']),(.32,2.1,d-3),'glass')
                for col in range(max(2,min(16,int(d/8)))):
                    zz=c['z']-d/2+4+col*(d-8)/max(1,min(16,int(d/8))-1)
                    scene.part('city-window-mullion',(x+side*.2,yy,zz),(.2,2.45,.22),'steel')
                    if (floor*3+col)%9==0:scene.part('city-side-occupied',(x+side*.26,yy,zz+1.6),(.13,1.5,2.8),'light-amber')
            scene.part('city-plinth',(x,base+.5,c['z']),(.55,1,d),'dark-concrete')
            scene.part('city-eaves',(x,c['y']+h/2,c['z']),(.65,.5,d+.7),'steel')
        # Retail windows and entrance pilasters face the public street, while
        # existing loading shutters remain on the delivery side.
        if h<40:
            for side in (-1,1):
                z=c['z']+side*(d/2+.45)
                for col in range(max(2,min(12,int(w/12)))):
                    xx=c['x']-w/2+6+col*(w-12)/max(1,min(12,int(w/12))-1)
                    scene.part('city-shopfront',(xx,base+2.25,z),(7,3.5,.5),'glass')
                    scene.part('city-shopfront-cap',(xx,base+4.25,z+side*.2),(7.4,.35,.5),'steel')
        scene.anchor=''


def _foreground(city):
    # Every choice is tied to a particular frontage or interior, in camera range
    # of its through route. Fallbacks move along that same place, never across town.
    for name,choices,kind in [
        ('meridian-wayfinding',[(525,990),(500,990),(555,995)],'directory'),
        ('meridian-coffee',[(455,1000),(455,990),(480,980)],'cafe'),
        ('exchange-court-planters',[(650,1010),(685,1035),(680,1000)],'garden'),
        ('gallery-news',[(775,691),(755,691),(790,691)],'news'),
        ('gallery-coffee',[(893,712),(902,700),(906,720)],'cafe'),
        ('atrium-directory',[(875,825),(875,810),(825,830)],'directory'),
        ('gallery-north-garden',[(805,835),(788,851),(900,850)],'garden'),
        ('court-laundry-pickup',[(425,365),(440,368),(425,335)],'news'),
        ('court-garden',[(480,430),(510,450),(470,445)],'garden'),
        ('court-mail-directory',[(595,430),(610,425),(630,415)],'directory'),
        ('tunnel-maintenance-bench',[(1067,1047),(1065,1025),(1070,1060)],'workbench'),
        ('tunnel-pump-bank',[(1120,1018),(1125,1013),(1115,1018)],'pump'),
        ('technical-route-board',[(1055,976),(1065,955),(1140,1033)],'directory'),
        ('parking-payment-west',[(1435,317),(1440,315),(1460,316)],'directory'),
        ('parking-charge-west',[(1450,308),(1450,395),(1445,390)],'charging'),
        ('parking-charge-east',[(1590,395),(1580,310),(1575,405)],'charging'),
        ('transport-transfer-guide',[(1350,910),(1345,915),(1340,920)],'directory'),
    ]:city.pocket(name,choices,kind)
    for floor in (8,16):
        city.pocket('parking-upper-charge-'+str(floor),[(1525,375),(1535,365),(1550,310)],'charging',floor)
    city.pocket('parking-upper-east-payment',[(1650,323),(1650,355),(1650,315)],'directory',8)
    scene=city.scene
    for z in (580,650,735,800):
        for x,side in ((1164,1),(1216,-1)):
            # Mounted above the vehicle envelope; their complete casing is solid.
            identity=city.solid('tunnel-vent-case',(x,-3,z),(1.4,4,5),'steel')
            city.installed.append(dict(id='tunnel-vent-case',kind='ventilation',position=[x,-3,z]))
            scene.anchor=identity
            for offset in (-1.5,-.5,.5,1.5):scene.part('tunnel-vent-grille',(x+side*.8,-3+offset,z),(.25,.16,4.2),'black')
    scene.anchor=''


def dress(scene):
    _sidewalks(scene);city=City(scene)
    _street_faces(scene);_foreground(city)
    for row in [('meridian-square',505,1165,15,6),('exchange-square',740,1115,12,6),
                ('archive-garden',285,1240,18,7),('clinic-forecourt',115,620,13,6),
                ('court-a',363,353,13,5),('court-b',510,380,13,5),('court-c',310,492,12,6),
                ('east-court',1525,755,12,6),('market-south',770,610,15,6),
                ('market-north',925,865,12,6),('parking-forecourt',1695,410,12,6),
                ('south-frontage',605,115,10,5),('business-pocket',865,460,10,5)]:city.garden(*row)
    for row in [('business-stop',265,1060),('market-stop',1010,855),('residential-stop',210,435),
                ('transport-stop',1350,1190),('parking-stop',1730,405)]:city.shelter(*row)
    city.parking_sign('parking-west',1360,310);city.parking_sign('parking-east',1720,305)
    city.service_yard('traction-substation',1140,1200);city.service_yard('distribution-yard',1250,250)
    # Entrances, rain canopies and loading doors belong to existing buildings.
    # They keep a visible public front and a separate service side.
    prefixes=('market-corner','southern-frontage','arts-workshops','west-clinic','north-hotel','delivery-centre')
    for building in list(scene.data['boxes']):
        if not building['id'].startswith(prefixes):continue
        c,s=building['center'],building['size'];scene.anchor=building['id']
        x,z=c['x'],c['z']-s['z']/2
        width=min(18,s['x']*.65)
        # The canopy is a real obstruction above head height, outside the driving corridor.
        if city.clear(x,z-2.7,math.hypot(width,5)/2,ignore=(building['id'],)):
            city.solid(building['id']+'-entrance-canopy',(x,5.8,z-2.7),(width,.45,5),'steel')
        scene.anchor=building['id']
        scene.part('entry-doors',(x,2.2,z-.18),(min(6,s['x']*.45),4.4,.28),'glass')
        scene.part('entry-lintel',(x,4.75,z-.35),(min(8,s['x']*.5),.45,.7),'light-cyan')
        back=c['z']+s['z']/2
        scene.part('service-shutter',(x,2.6,back+.18),(min(8,s['x']*.45),5.2,.28),'steel')
        for sign in (-1,1):scene.part('service-corner-guard',(x+sign*4,1.4,back+.4),(.4,2.8,.5),'yellow')
    scene.anchor='';print('neon dressing:',city.count,'solids;',len(city.installed),'foreground places; sidewalks replace underlying terrain')
    return city.installed
