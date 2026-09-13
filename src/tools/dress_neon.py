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
            first=(len(self.scene.data['boxes']),len(self.scene.parts),len(self.scene.models),len(self.scene.lights),len(self.scene.signs))
            self.installed.append(dict(id=name,kind=kind,position=[x,floor,z]))
            if kind in ('news','cafe'):
                self.solid(name+'-back',(x,floor+2.1,z+depth/2-.25),(width,4.2,.5),'brick' if kind=='cafe' else 'steel')
                for side in (-1,1):self.solid(name+'-end',(x+side*(width/2-.25),floor+2.1,z),(.5,4.2,depth),'steel')
                self.solid(name+'-counter',(x,floor+1.05,z-depth/2+.65),(width-.7,2.1,1.3),'wood')
                self.solid(name+'-awning',(x,floor+4.5,z),(width+1,.5,depth+1),'blue' if kind=='news' else 'ivory')
                self.scene.part(name+'-sign',(x,floor+3.45,z-depth/2-.2),(width*.75,.8,.25),'light-cyan' if kind=='news' else 'light-amber')
                self.scene.sign(name+'-title','КОФЕ' if kind=='cafe' else 'ПРЕССА' if 'laundry' not in name else 'ПРАЧЕЧНАЯ',
                    (x,floor+3.5,z-depth/2-.37),180,width*.68,.58)
                for offset in (-.3,0,.3):
                    self.scene.part(name+'-menu',(x+width*offset,floor+2.4,z+depth/2-.6),(width*.22,1.1,.18),'ivory')
                    self.scene.part(name+'-display',(x+width*offset,floor+1.4,z-depth/2+.65),(width*.2,.55,.6),'faded-red' if kind=='news' else 'steel')
                self.scene.light(name+'-counter-light',(x,floor+3.9,z-2),(1,.72,.42) if kind=='cafe' else (.5,.8,1),14)
            elif kind=='directory':
                self.solid(name+'-base',(x,floor+.3,z),(4,.6,2),'dark-concrete')
                self.solid(name+'-frame',(x,floor+2.7,z),(3.4,4.8,.65),'steel')
                self.scene.part(name+'-screen',(x,floor+3,z-.4),(2.9,3.4,.18),'blue')
                title='ПАРКИНГ' if 'parking' in name else 'ZERO'
                self.scene.sign(name+'-directory',title+'\nВЫЕЗД', (x,floor+3.1,z-.54),180,2.6,1.2)
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
            self.face_route(x,z,floor,first)
            self.scene.anchor='';return
        raise ValueError('No safe authored city pocket: '+name)

    def face_route(self,x,z,floor,first):
        approaches=[]
        for path in self.scene.location.paths:
            for aa,bb in zip(path['names'],path['names'][1:]):
                a,b=self.scene.location.points[aa],self.scene.location.points[bb]
                if abs(a[1]-floor)>2 or abs(b[1]-floor)>2:continue
                dx,dz=b[0]-a[0],b[2]-a[2]
                t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
                target=(a[0]+dx*t,a[2]+dz*t)
                approaches.append((math.hypot(target[0]-x,target[1]-z),target))
        if not approaches:raise ValueError('City furniture has no street frontage')
        _,target=min(approaches);yaw=math.degrees(math.atan2(x-target[0],z-target[1]))
        r=math.radians(yaw)
        def turn(position):
            dx,dz=position['x']-x,position['z']-z
            position['x']=x+math.cos(r)*dx+math.sin(r)*dz
            position['z']=z-math.sin(r)*dx+math.cos(r)*dz
        for box in self.scene.data['boxes'][first[0]:]:turn(box['center']);box['yawDegrees']+=yaw
        for part in self.scene.parts[first[1]:]:turn(part['position']);part['rotation']['y']+=yaw
        for model in self.scene.models[first[2]:]:turn(model['position']);model['rotation']['y']+=yaw
        for light in self.scene.lights[first[3]:]:turn(light['position'])
        for sign in self.scene.signs[first[4]:]:turn(sign['position']);sign['yawDegrees']+=yaw
        self.installed[-1]['frontageYaw']=yaw


def _building_use(identity):
    # The author already names the site and its wings. Use that purpose instead
    # of deriving another arbitrary pattern from position or a random seed.
    if identity.startswith(('courtyard-', 'east-apartments', 'garden-court', 'north-hotel')):
        return 'residential'
    if identity.startswith(('technical-', 'transit-depot', 'market-cold-store',
                            'western-substation', 'delivery-centre', 'residential-service')):
        return 'service'
    if identity.startswith(('market-corner', 'passage-shop', 'southern-frontage', 'arts-workshops')):
        return 'retail'
    if identity.startswith(('school', 'west-clinic', 'civic-archive')):
        return 'civic'
    return 'business'


class Frontage:
    """Shallow, attached architectural joinery on an existing solid wall.

    Coordinates are along the wall and above its base. Closed windows and doors
    keep the building's collision envelope; none suggest a new vehicle opening.
    All skins stand clear of the wall (no coplanar faces), by at most 0.44 metres.
    """
    def __init__(self, scene, name, center, width, height, yaw, base=0):
        self.scene,self.name,self.center=scene,name,center
        self.width,self.height,self.yaw,self.base=width,height,yaw,base

    def detail(self, label, along, y, span, tall, material, depth=.12, thick=.16):
        angle=math.radians(self.yaw);x,z=self.center
        self.scene.part(self.name+'-'+label,
            (x+math.cos(angle)*along+math.sin(angle)*depth,self.base+y,
             z-math.sin(angle)*along+math.cos(angle)*depth),
            (span,tall,thick),material,rotation=(0,self.yaw,0))

    def window(self, label, x, y, width, height, lit=False, detailed=True):
        if detailed:self.detail(label+'-reveal',x,y,width+.5,height+.45,'ivory',.13,.22)
        self.detail(label+'-pane',x,y,width,height,'light-amber' if lit else 'glass',.29,.1)

    def shutters(self, width, height, count, label='loading', detailed=True):
        for index in range(count):
            x=(index-(count-1)/2)*min(width+7,self.width/count)
            self.detail(label+'-frame',x,height/2+.25,width+.9,height+.5,'dark-concrete',.14,.24)
            self.detail(label+'-closed-door',x,height/2+.25,width,height,'steel',.31,.14)
            if detailed:
                for y in range(1,int(height)):
                    self.detail(label+'-door-rib',x,y+.25,width-.4,.09,'black',.415,.05)

    def compose(self, use, variant=0, distant=False):
        w,h=self.width,self.height
        self.detail(use+'-base',0,.6,w-1,1.2,'dark-concrete',.13,.24)
        self.detail(use+'-crown',0,h-.4,w-1,.7,'ivory' if use in ('residential','civic') else 'steel',.17,.28)
        if use=='service':
            # Depots have a loading elevation; end walls carry extraction plant.
            # No ground-level retail glazing on transformer or cold-store walls.
            self.shutters(min(10,w*.22),min(7,h*.55),min(3,max(1,int(w/35))),detailed=not distant)
            for x in (-w*.32,w*.32):
                self.detail('service-vent-case',x,h*.78,min(10,w*.18),min(3,h*.18),'black',.16,.26)
                if not distant:
                    for row in (-1,0,1):self.detail('service-louvre',x,h*.78+row*.55,min(10,w*.18)-.5,.22,'steel',.35,.16)
            if variant%2==0:
                self.detail('service-access-door',w*.4,1.65,2,3.3,'blue',.18,.24)
            return
        if use=='business':
            # Tall glazed groups, structural cores, and a separate lobby. The
            # mechanical storey interrupts the curtain wall instead of creating
            # an uninterrupted stripe around every floor of every building.
            columns=max(2,min(7,int(w/15)));pitch=(w-8)/columns
            bottom=8 if h>=22 else 2
            rows=max(1,min(6 if distant else 10,round((h-bottom-3)/10)))
            pitch_y=(h-bottom-2)/rows
            for col in range(columns):
                x=-w/2+4+(col+.5)*pitch
                if col==columns//2 and h>40:
                    self.detail('business-service-core',x,(h+bottom)/2,pitch*.48,h-bottom,'steel',.12,.2)
                    continue
                for row in range(rows):
                    if h>65 and row==rows//2:continue
                    y=bottom+(row+.5)*pitch_y
                    self.window('business-glazed-group',x,y,pitch*.76,pitch_y*.81,
                                lit=False,detailed=not distant and y<18)
            if h>=22:
                self.window('business-lobby',-w*.17,3.7,min(18,w*.3),5.8,detailed=not distant)
                self.detail('business-lobby-divider',-w*.17,3.7,.35,5.8,'steel',.36,.15)
            return
        # Housing has solid party walls and pairs of windows separated by a
        # stair bay. Civic buildings have taller reading/classroom openings.
        civic=use=='civic';retail=use=='retail'
        ground=7 if retail else 5 if civic else 2.8
        columns=max(2,min(7 if distant else 12,int(w/(13 if civic else 11))))
        pitch=(w-6)/columns;rows=max(0,min(8 if distant else 17,int((h-ground-1)/(5.4 if civic else 4.4))))
        pitch_y=(h-ground-1)/max(1,rows)
        for col in range(columns):
            x=-w/2+3+(col+.5)*pitch
            if col==columns//2 and not civic:
                self.detail('residential-stair-spandrel',x,(h+ground)/2,pitch*.55,h-ground,'ivory',.11,.18)
            for row in range(rows):
                y=ground+(row+.45)*pitch_y
                if civic:
                    self.window('civic-reading-bay',x,y,pitch*.58,min(4.3,pitch_y*.7),detailed=not distant and y<18)
                elif col==columns//2:
                    self.window('residential-stair-window',x,y,1.5,min(2.6,pitch_y*.58),detailed=not distant and y<18)
                else:
                    # Pairs share a wall bay; masonry remains visible between
                    # both rooms, neighbouring apartments and successive floors.
                    for side in (-1,1):
                        self.window('residential-room',x+side*pitch*.19,y,pitch*.26,min(2.7,pitch_y*.62),
                                    lit=(col*5+row*3+variant)%19==0 and side==1,detailed=not distant and y<18)
        if retail:
            count=max(1,min(4,int(w/20)))
            for col in range(count):
                x=(col-(count-1)/2)*(w-8)/count
                self.window('retail-display',x-1.6,2.7,min(9,(w-10)/count*.6),4.3,detailed=not distant)
                self.detail('retail-door',x+min(5,(w-10)/count*.32),2,2,4,'steel',.25,.2)
                self.detail('retail-fascia',x,5.65,min(15,(w-8)/count-2),.9,'ivory',.17,.2)
        elif civic:
            self.detail('civic-public-entrance',0,2.1,3.4,4.2,'steel',.22,.2)
        else:
            self.detail('residential-entrance',w*.12,1.6,2.2,3.2,'steel',.22,.2)


def _street_faces(scene):
    buildings=[b for b in scene.data['boxes'] if b['collision'] and b['size']['y']>=8
               and (min(b['size']['x'],b['size']['z'])>=24 or b['id'] in ('technical-west','technical-south')) and 'roof' not in b['id']
               and not b['id'].startswith(('edge-','dress-'))]
    # common() predates purpose-specific facades. Replace its windows, rather
    # than layering a second architectural language on top of the same wall.
    anchors={b['id'] for b in buildings}
    replaced=('architectural-window-','occupied-window-','street-address-','parapet-coping-')
    scene.parts[:]=[p for p in scene.parts if not (p['anchor'] in anchors and p['id'].startswith(replaced))]
    for building in buildings:
        c,s=building['center'],building['size'];w,h,d=s['x'],s['y'],s['z'];base=c['y']-h/2
        scene.anchor=building['id'];use=_building_use(building['id'])
        for axis,side,yaw in (('z',1,0),('x',1,90),('z',-1,180),('x',-1,-90)):
            width=d if axis=='x' else w
            if width<24:continue
            offset=(side*w/2,0) if axis=='x' else (0,side*d/2)
            angle=math.radians(building['yawDegrees'])
            center=(c['x']+math.cos(angle)*offset[0]+math.sin(angle)*offset[1],
                    c['z']-math.sin(angle)*offset[0]+math.cos(angle)*offset[1])
            front=Frontage(scene,'front-'+building['id']+'-'+axis+str(side),center,width,h,yaw+building['yawDegrees'],base)
            front.compose(use,variant=0 if side==1 else 1)
    scene.anchor=''


def _city_backdrop(scene):
    # Neighbourhoods continue beyond each boundary, with different massing
    # sections on the existing buildings. At distance the broad masonry/core
    # divisions survive; hundreds of luminous slits are not a skyline treatment.
    def face(name,center,width,height,yaw,use,base=0,distant=False):
        front=Frontage(scene,name,center,width,height,yaw,base)
        if distant:
            count=max(2,math.ceil(width/85));pitch=width/count
            for section in range(count):
                along=-width/2+(section+.5)*pitch
                front.detail('section-pier',along-pitch/2+1,height/2,2,height,'cast-concrete',.14,.24)
                angle=math.radians(yaw)
                subcenter=(center[0]+math.cos(angle)*along,center[1]-math.sin(angle)*along)
                # A single real service core belongs to the entire large mass;
                # the remaining wings retain the building's common purpose.
                section_use='service' if section==count//2 else use
                Frontage(scene,name+'-wing-'+str(section),subcenter,pitch-4,height,yaw,base).compose(section_use,section,distant=True)
        else:front.compose(use,variant=int(center[0]+center[1])%5,distant=True)
    for building in scene.data['boxes']:
        if not building['id'].startswith('edge-'):continue
        side,start=map(int,building['id'].split('-')[1:]);c,s=building['center'],building['size']
        scene.anchor=building['id'];axis='x' if side<2 else 'z';sign=1 if side in (0,2) else -1
        center=(c['x']+sign*s['x']/2,c['z']) if axis=='x' else (c['x'],c['z']+sign*s['z']/2)
        # West: homes, clinic, financial centre. East: logistics behind parking,
        # apartments behind the gardens, railway operations behind transport.
        if side==0:use='residential' if start<650 else 'civic' if start<850 else 'business'
        elif side==1:use='service' if start<600 or start>=1050 else 'residential'
        elif side==2:use='residential' if start<750 else 'business' if start<1100 else 'service'
        else:use='civic' if start<700 else 'business' if start<1300 else 'service'
        face('boundary-'+building['id'],center,s['z'] if axis=='x' else s['x'],s['y'],
             (90*sign if axis=='x' else 0 if sign==1 else 180),use)
    masses=[p for p in scene.parts if p['id'].startswith('city-continuation-')]
    for building in masses:
        c,s=building['position'],building['size'];scene.anchor='exterior'
        use='residential' if c['x']<0 and c['z']<700 or c['z']<0 else 'service' if c['x']>1800 and c['z']<700 else 'business' if c['y']>45 else 'civic'
        for axis in ('x','z'):
            sign=1 if c[axis]<(900 if axis=='x' else 700) else -1
            center=(c['x']+sign*s['x']/2,c['z']) if axis=='x' else (c['x'],c['z']+sign*s['z']/2)
            face('backdrop-'+building['id']+'-'+axis,center,s['z'] if axis=='x' else s['x'],s['y'],
                 (90*sign if axis=='x' else 0 if sign==1 else 180),use,c['y']-s['y']/2,True)
    scene.anchor=''


def _express_parapets(scene):
    # A closed concrete edge on the diagonal deck, with exactly the same mesh
    # shown and collided. Its inner face touches the OUTSIDE of the complete
    # 34 m roadway. Raised sides are not extra driving surfaces or road overlays.
    path=next(p for p in scene.location.paths if p['id']=='express-diagonal')
    a,b=(scene.location.points[n] for n in path['names'])
    dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
    nx,nz=dz/length,-dx/length;half=path['width']/2
    if abs(a[1]-b[1])>.001:raise ValueError('Diagonal parapets require the authored level deck')
    # Trim the ends before the ramp junctions. This keeps the complete width of
    # both the deck and the turning transition through each neighbouring ramp.
    margin=12;ux,uz=dx/length,dz/length
    for side in (-1,1):
        polygon=ccw([(a[0]+ux*margin+nx*offset*side,a[2]+uz*margin+nz*offset*side)
                     for offset in (half,half+.7)]+
                    [(b[0]-ux*margin+nx*offset*side,b[2]-uz*margin+nz*offset*side)
                     for offset in (half+.7,half)])
        vertices,indices,_=_faces([polygon],a[1]+1.35,'cast-concrete')
        identity='neon-express-parapet-'+str(side)
        scene.data['meshes'].append(dict(id=identity,vertices=vertices,indices=indices,
            material='cast-concrete',collision=True,triangleMaterials=[],thickness=2.55))
        # The lower 1.2 m joins the existing deck's side over its full depth,
        # instead of balancing the parapet on a single unattached edge line.
        # Reflective caps sit directly on the crown, within the wall footprint.
        scene.anchor=identity
        for number in range(1,int((length-2*margin)/35)+1):
            t=margin+number*35
            scene.part('express-edge-reflector',(a[0]+ux*t+nx*(half+.35)*side,a[1]+1.39,
                       a[2]+uz*t+nz*(half+.35)*side),(.5,.08,.65),'ivory',
                       rotation=(0,math.degrees(math.atan2(dx,dz)),0))
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
        # The exterior market route approaches the corner shop from the south,
        # independently of the indoor gallery. Its street directory belongs to
        # that shop forecourt; it does not create another isolated retail hut.
        ('market-street-directory',[(650,575),(640,575),(665,552)],'directory'),
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
    _street_faces(scene);_city_backdrop(scene);_express_parapets(scene);_foreground(city)
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
    # Rain canopies belong to the actual public entrances. Doors themselves
    # are part of each purpose-specific facade, not another generic glass layer.
    prefixes=('market-corner','southern-frontage','arts-workshops','west-clinic','north-hotel','delivery-centre')
    for building in list(scene.data['boxes']):
        if not building['id'].startswith(prefixes):continue
        c,s=building['center'],building['size'];scene.anchor=building['id']
        x,z=c['x'],c['z']-s['z']/2
        use=_building_use(building['id'])
        if use=='residential':x-=s['x']*.12
        elif use=='retail':
            count=max(1,min(4,int(s['x']/20)))
            # Match the actual south-facing shop door, whose local +X points
            # west. In an even row the entrance is not at the building centre.
            along=(count//2-(count-1)/2)*(s['x']-8)/count+min(5,(s['x']-10)/count*.32)
            x-=along
        width=min(18,s['x']*.65)
        # The canopy is a real obstruction above head height, outside the driving corridor.
        if city.clear(x,z-2.7,math.hypot(width,5)/2,ignore=(building['id'],)):
            city.solid(building['id']+'-entrance-canopy',(x,5.8,z-2.7),(width,.45,5),'steel')
    # The destination is attached to the existing structure, not a floating HUD label.
    for name,text,position,size,yaw,anchor in [
        ('gallery-name','ZERO / ГАЛЕРЕЯ',(825,9,674),(42,3,.5),180,'shopping-passage-roof'),
        ('technical-name','СЛУЖБА ТОННЕЛЯ',(1100,7.5,973),(28,2.5,.5),180,'technical-complex-roof'),
        ('parking-west-name','P / ПАРКИНГ',(1398,6,350),(.5,2.5,24),-90,'parking-ground-floor-roof'),
    ]:
        scene.anchor=anchor;scene.part(name+'-panel',position,size,'blue')
        rad=math.radians(yaw);front=(position[0]+math.sin(rad)*.4,position[1],position[2]+math.cos(rad)*.4)
        scene.sign(name,text,front,yaw,22 if 'parking' in name else size[0]*.9,1.5)
    from dress_parking import dress as dress_parking
    parking=dress_parking(scene)
    scene.anchor='';print('neon dressing:',city.count,'solids;',len(city.installed),'foreground places;',len(parking['bays']),'painted parking bays; sidewalks replace underlying terrain')
    return city.installed
