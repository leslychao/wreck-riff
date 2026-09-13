"""Authored city sidewalks, front doors, service yards and courtyard furniture.

Road and building footprints decide where each object fits. There is no random
building grid. Pavement replaces the underlying ground triangles, never overlays
another visible floor. All substantial street furniture has the same visual and
collision geometry in ArenaDefinition.
"""
import math
from collections import defaultdict
from author_campaign_arenas import ccw, cut, area, vec


def _rectangle(x, z, width, depth):
    return ccw([(x-width/2,z-depth/2),(x+width/2,z-depth/2),
                (x+width/2,z+depth/2),(x-width/2,z+depth/2)])


def _box_polygon(box):
    c,s=box['center'],box['size'];angle=math.radians(box['yawDegrees'])
    return ccw([(c['x']+math.cos(angle)*x+math.sin(angle)*z,
                 c['z']-math.sin(angle)*x+math.cos(angle)*z)
                for x,z in _rectangle(0,0,s['x'],s['z'])])


def _add_curbs(walks,height=.14):
    """Close the exposed outside edge, never duplicate an internal polygon seam."""
    edges=[];points=set()
    for mesh,polygons in walks:
        for polygon in polygons:
            for a,b in zip(ccw(polygon),ccw(polygon)[1:]+ccw(polygon)[:1]):
                edges.append((mesh,a,b));points.add(a);points.add(b)
    segments=defaultdict(list)
    for mesh,a,b in edges:
        dx,dz=b[0]-a[0],b[1]-a[1];length2=dx*dx+dz*dz
        if length2<1e-10:continue
        splits=[0.,1.]
        for p in points:
            t=((p[0]-a[0])*dx+(p[1]-a[1])*dz)/length2
            if 1e-6<t<1-1e-6 and abs((p[0]-a[0])*dz-(p[1]-a[1])*dx)<1e-5*math.sqrt(length2):splits.append(t)
        splits=sorted(set(round(t,9) for t in splits))
        for start,end in zip(splits,splits[1:]):
            p=(round(a[0]+start*dx,5),round(a[1]+start*dz,5));q=(round(a[0]+end*dx,5),round(a[1]+end*dz,5))
            if p!=q:segments[tuple(sorted((p,q)))].append((mesh,p,q))
    for matching in segments.values():
        if len(matching)!=1:continue
        mesh,a,b=matching[0];base=len(mesh['vertices'])
        mesh['vertices'].extend(vec(p) for p in [(a[0],0,a[1]),(b[0],0,b[1]),(b[0],height,b[1]),(a[0],height,a[1])])
        mesh['indices'].extend((base,base+3,base+2,base,base+2,base+1))


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
    roads=_road_polygons(scene);claimed=[];walks=[];walk_meshes=[]
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
            mesh=dict(id=identity,vertices=vertices,indices=indices,material='district-slate',collision=True,triangleMaterials=[])
            scene.data['meshes'].append(mesh);walk_meshes.append((mesh,pieces))
            scene.data['surfaces'].append(dict(id=identity,geometryId=identity,level=0,grip=1))
            walks.extend(pieces);claimed.extend(pieces)
    _add_curbs(walk_meshes)
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
    def __init__(self,scene):self.scene=scene;self.count=0
    def clear(self,x,z,radius,ignore=()):
        if not self.scene.location.landscape_clear(x,z,radius+3,ignore):return False
        for pickup in self.scene.data['pickups']:
            p=pickup['position']
            if abs(p['y'])<4 and math.hypot(p['x']-x,p['z']-z)<radius+8:return False
        for box in self.scene.data['boxes']:
            if not box['collision'] or box['id'] in ignore:continue
            c,s=box['center'],box['size']
            if c['y']-s['y']/2>5:continue
            if abs(x-c['x'])<s['x']/2+radius and abs(z-c['z'])<s['z']/2+radius:return False
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


def dress(scene):
    _sidewalks(scene);city=City(scene)
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
    scene.anchor='';print('neon dressing:',city.count,'solids; sidewalks replace underlying terrain')
