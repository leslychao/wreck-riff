"""Purposeful revision-three construction equipment, authored in metres.

The main author calls dress(scene) before saving. Large components are canonical
BoxParts, so their silhouettes and collision share one placement. Small fasteners,
lifting eyes, gratings and braces are surface details. Nothing is placed in a road
or its turning allowance; there is no random filler/building grid.
"""
import math
from author_campaign_arenas import vec


class ConstructionDressing:
    def __init__(self, scene):
        self.scene=scene
        self.location=scene.location
        self.installed=[]
        self.skipped=[]

    def clear(self, x, z, radius):
        # This is deliberately measured against every route, including shortcuts,
        # ramps and the graph's directed launch approach, not only painted roads.
        for path in self.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.location.points[first],self.location.points[last]
                dx,dz=b[0]-a[0],b[2]-a[2]
                t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
                if math.hypot(x-a[0]-t*dx,z-a[2]-t*dz)<path['width']/2+radius+4:
                    return False
        # Supply remains collectable and neither boss entrance becomes a trap.
        for item in self.scene.data['spawns']+self.scene.data['pickups']:
            p=item['position']
            if math.hypot(x-p['x'],z-p['z'])<radius+7:return False
        for boss in self.scene.data['bosses']:
            for entrance in boss['entrances']:
                p=entrance.get('position',entrance)
                if math.hypot(x-p['x'],z-p['z'])<radius+10:return False
        for box in self.scene.data['boxes']:
            if not box['collision']:continue
            c,s=box['center'],box['size']
            # A roof above this equipment is allowed; its supporting columns are
            # separate solids and still checked. Foundations may share soil below.
            base=self.height(x,z)
            if c['y']-s['y']/2>base+11 or c['y']+s['y']/2<base+.2:continue
            angle=math.radians(box['yawDegrees']);dx,dz=x-c['x'],z-c['z']
            if abs(math.cos(angle)*dx-math.sin(angle)*dz)<s['x']/2+radius+1.5 \
                    and abs(math.sin(angle)*dx+math.cos(angle)*dz)<s['z']/2+radius+1.5:return False
        return True

    @staticmethod
    def height(x,z):
        # Authored excavation floor; all other selected workplaces are at grade.
        if 365<=x<=565 and 415<=z<=645:return -14
        return 0

    def place(self, name, candidates, footprint, builder, yaw=0):
        radius=math.hypot(*footprint)/2
        for x,z in candidates:
            if not self.clear(x,z,radius):continue
            self.origin=(x,self.height(x,z),z);self.yaw=yaw;self.name=name
            self.scene.anchor='';self.scene.group=''
            builder();self.installed.append(dict(id=name,x=x,z=z,radius=radius))
            return True
        self.skipped.append(name)
        return False

    def position(self,p):
        a=math.radians(self.yaw)
        return (self.origin[0]+math.cos(a)*p[0]+math.sin(a)*p[2],
                self.origin[1]+p[1],self.origin[2]-math.sin(a)*p[0]+math.cos(a)*p[2])

    def solid(self,label,p,size,material='concrete'):
        identity='dress-construction-'+self.name+'-'+label
        assert not any(b['id']==identity for b in self.scene.data['boxes']),identity
        self.scene.data['boxes'].append(dict(id=identity,center=vec(self.position(p)),size=vec(size),
                material=material,collision=True,yawDegrees=self.yaw))
        self.scene.anchor=identity
        return identity

    def detail(self,label,p,size,material):
        self.scene.part('dress-'+self.name+'-'+label,self.position(p),size,material,rotation=(0,self.yaw,0))

    def beam(self,label,a,b,width,material):
        self.scene.beam('dress-'+self.name+'-'+label,self.position(a),self.position(b),width,material)

    def precast_beams(self):
        # Three manufactured bridge beams on transport sleepers, with lifting eyes.
        for j,z in enumerate((-4,4)):
            self.solid('sleeper-'+str(j),(0,.45,z),(17,.9,1.5),'wood')
        for i,x in enumerate((-5.5,0,5.5)):
            self.solid('precast-'+str(i),(x,2.1,0),(3.6,2.4,16))
            self.detail('beam-top-flange-'+str(i),(x,3.45,0),(4.4,.3,16),'concrete')
            for z in (-5,5):
                self.beam('lifting-eye',(x-.5,3.6,z),(x+.5,3.6,z),.12,'steel')
                self.beam('lifting-leg',(x-.5,3.3,z),(x-.5,3.6,z),.12,'steel')

    def panel_cradle(self):
        # A-frame storage for facade panels, visibly different from beam delivery.
        for i,x in enumerate((-7,7)):
            self.solid('steel-foot-'+str(i),(x,.35,0),(2,.7,9),'steel')
            self.solid('rack-post-'+str(i),(x,4.8,0),(.8,8.2,.8),'steel')
            for side in (-1,1):self.beam('rack-brace',(x,.8,side*4),(x,7.5,0),.45,'steel')
        for i,z in enumerate((-2.5,-1.2,1.2,2.5)):
            self.solid('panel-'+str(i),(0,4.1,z),(18,6.6,.7),'concrete')
            for x in (-5,5):self.detail('lifting-socket',(x,7.45,z),(.7,.12,.7),'steel')
        self.detail('inventory-band',(8.5,3.5,-2.91),(.5,1.4,.12),'yellow')

    def aggregate_bay(self):
        # Open-front retaining bay and a slatted feeder at its back.
        self.solid('left-wall',(-10,2.4,0),(1.4,4.8,19))
        self.solid('right-wall',(10,2.4,0),(1.4,4.8,19))
        self.solid('back-wall',(0,2.4,9),(20,4.8,1.4))
        self.solid('feeder',(0,3,5),(12,3.4,6),'steel')
        for x in range(-5,6):self.detail('feeder-grate',(x,4.85,5),(.35,.25,5.5),'black')
        for x in (-7,7):self.detail('bay-edge-reflector',(x,3.2,8.21),(1.2,.7,.1),'yellow')

    def conveyor_drive(self):
        # Enclosed sorting head feeding a belt. The motor, service ladder and
        # structural trestles explain the silhouette instead of a plain garage.
        self.solid('head-base',(0,1,6),(12,2,10))
        self.solid('sorting-head',(0,6.7,6),(10,9.4,9),'steel')
        self.solid('belt-housing',(0,6,-5),(5.5,1.5,16),'steel')
        self.solid('drive-motor',(4.6,5.1,5),(3,2.8,4),'blue')
        for x in (-2.1,2.1):
            for z in (-11,-3):self.solid('trestle-'+str(x)+'-'+str(z),(x,2.65,z),(.6,5.3,.6),'steel')
            self.beam('belt-guard',(x,7.1,-13),(x,7.1,2),.15,'yellow')
        for z in range(-11,2,2):self.detail('belt-cleat',(0,6.86,z),(4,.16,.24),'rubber')
        self.detail('head-access',(0,5.5,1.42),(3.5,4,.18),'blue')
        for y in range(1,11):self.beam('service-ladder',(-5.25,y,4),(-5.25,y,6),.12,'steel')

    def reinforcement(self):
        # Visible future pile cap: concrete footing, rebar cage and form ties.
        self.solid('pile-cap',(0,.8,0),(10,1.6,9))
        for i,(x,z) in enumerate(((-3,-2.5),(3,-2.5),(-3,2.5),(3,2.5))):
            self.solid('starter-cage-'+str(i),(x,3.3,z),(.28,5,.28),'steel')
        for y in (2,3,4,5):
            for z in (-2.5,2.5):self.beam('tie',( -3,y,z),(3,y,z),.1,'rust')
            for x in (-3,3):self.beam('tie',(x,y,-2.5),(x,y,2.5),.1,'rust')
        for x in (-5.2,5.2):self.detail('formwork',(x,1,0),(.35,1.8,9),'wood')

    def drain(self):
        # Open, shallow concrete channel beside the foundations. Its two banks
        # and pump-end headwall are actual colliders, without painted floor sheets.
        for i,x in enumerate((-1.7,1.7)):
            self.solid('channel-bank-'+str(i),(x,.35,0),(.55,.7,20))
        self.solid('sump',(0,.45,9),(3,.9,2),'steel')
        for z in (8.3,8.8,9.3,9.8):self.detail('sump-grate',(0,.98,z),(2.6,.12,.14),'black')
        self.solid('pump-cabinet',(3.2,1.3,8),(1.8,2.6,2),'blue')
        self.detail('pump-warning',(3.2,1.5,6.93),(.65,.7,.1),'yellow')

    def working_terrace(self):
        # Stepped casting/work bench against the excavation side, with a compact
        # drainage run. Every exposed riser is part of the physical stepped solid.
        for j in range(3):
            height=1.2*(j+1)
            self.solid('casting-step-'+str(j),(0,height/2,-5+j*5),(22,height,5))
            for x in (-8,-3,3,8):self.detail('shutter-tie',(x,height-.3,-7.55+j*5),(.22,.22,.16),'steel')
        for x in (-11.6,11.6):self.solid('retaining-cheek-'+str(x),(x,2.1,0),(1.2,4.2,16))
        self.solid('drain-end',(0,.35,-9),(20,.7,1),'steel')

    def shuttering(self):
        self.solid('rack-base',(0,.35,0),(16,.7,9),'steel')
        for j,z in enumerate((-3,0,3)):
            self.solid('form-panel-'+str(j),(0,2.2,z),(15,3,.65),'wood')
            for x in (-6,-2,2,6):self.detail('panel-rib',(x,2.2,z-.45),(.3,3,.25),'yellow')
        for x in (-7.6,7.6):self.solid('rack-stop-'+str(x),(x,2,0),(.5,3.3,9),'steel')

    def cable_station(self):
        self.solid('transformer-foot',(0,.4,0),(7,.8,6))
        self.solid('transformer',(0,2.8,0),(5,4,4),'blue')
        for x in (-2.7,2.7):
            for z in (-1.5,-.75,0,.75,1.5):self.detail('cooling-fin',(x,2.8,z),(.3,3,.25),'steel')
        self.detail('electrical-warning',(0,3,-2.12),(1.1,1.2,.18),'yellow')
        for x in (-1.5,0,1.5):
            self.beam('insulator',(x,4.8,0),(x,5.7,0),.32,'ivory')
        self.solid('cable-tray',(0,.45,5),(6,.7,1.5),'black')


def dress(scene):
    d=ConstructionDressing(scene)
    # Dispatch yard: precast elements grouped by lifting/transport method.
    for i,(x,z) in enumerate(((910,105),(1030,85),(1380,155),(1460,160),(1080,155))):
        d.place('dispatch-beams-'+str(i),[(x,z)],(20,20),d.precast_beams,yaw=0 if i%2 else 90)
    for i,p in enumerate(((1090,88),(1330,100),(1450,330),(1000,195))):
        d.place('facade-delivery-'+str(i),[p],(21,12),d.panel_cradle)
    # Aggregate receipt, screening and outbound loading around the actual plant.
    for i,p in enumerate(((900,480),(940,740),(1320,705),(1460,615))):
        d.place('aggregate-receipt-'+str(i),[p],(24,22),d.aggregate_bay,yaw=90 if i%2 else 0)
    for i,p in enumerate(((955,665),(1360,665),(1320,520))):
        d.place('sorting-line-'+str(i),[p],(15,32),d.conveyor_drive,yaw=90 if i%2 else 0)
    # Four working corners of the excavation leave both crossing haul roads open.
    for i,p in enumerate(((393,476),(525,470),(408,593),(524,607))):
        d.place('pile-reinforcement-'+str(i),[p],(12,11),d.reinforcement)
    for i,p in enumerate(((391,570),(534,580),(389,485),(534,482))):
        d.place('foundation-drain-'+str(i),[p],(10,23),d.drain)
    for i,p in enumerate(((540,438),(544,629),(384,627))):
        d.place('casting-terrace-'+str(i),[p],(26,22),d.working_terrace,yaw=180 if i==0 else 0)
    # Reusable shuttering and curing elements explain the next construction stage.
    for i,p in enumerate(((260,960),(330,1060),(585,1080),(735,855),(765,1010))):
        d.place('formwork-store-'+str(i),[p],(20,12),d.shuttering,yaw=0 if i%2 else 90)
    for i,p in enumerate(((260,1010),(610,835),(750,970),(955,1000),(1470,1000))):
        d.place('site-power-'+str(i),[p],(10,15),d.cable_station)
    # Temporary beam yards serve the unfinished interchange, away from its run-up.
    for i,p in enumerate(((1030,1080),(1190,1110),(1410,965),(1070,860),(1020,1160),(1520,930),(920,1050))):
        d.place('viaduct-beams-'+str(i),[p],(20,20),d.precast_beams,yaw=40)
    scene.anchor='';scene.group=''
    print('Construction dressing:',len(d.installed),'workplaces;',len(d.skipped),'rejected by route/solid clearance')
    return d.installed
