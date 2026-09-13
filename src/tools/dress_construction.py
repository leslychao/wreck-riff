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
        # Equipment needs one level foundation. Reject the excavation slopes;
        # using the old grade there would leave the entire machine floating.
        if 365<=x<=565 and 415<=z<=645:
            if min(x-365,565-x,z-415,645-z)<radius:return False
        elif 280-radius<x<650+radius and 330-radius<z<730+radius:
            return False
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

    def tank(self,label,p,radius,height):
        # Reuse the authored silo's conical hopper, rings and access ladder at
        # equipment scale. The visible GLB, including its legs, is the collider.
        identity=self.solid(label+'-proxy',(p[0],p[1]+height/2,p[2]),(radius*2.2,height,radius*2.2),'ivory')
        self.scene.data['boxes'][-1]['collision']=False
        self.scene.model(identity+'-model','cement-silo',self.position(p),
                (radius/17,height/47,radius/17),(0,self.yaw,0),[identity],180)

    def operator_post(self):
        # A glazed operator station has an actual door recess, console and roof;
        # its purpose is visible from the road instead of another blank shed.
        self.solid('operator-plinth',(0,.35,0),(7,.7,5.5),'concrete')
        self.solid('operator-rear',(0,2.5,2.2),(6,4.3,.3),'blue')
        for i,x in enumerate((-3,3)):
            self.solid('operator-side-'+str(i),(x,2.5,0),(.3,4.3,4.7),'blue')
            self.detail('side-glazing',(x*1.06,3,0),(.12,1.9,3.4),'glass')
        self.solid('operator-front',(1,1.5,-2.2),(4,2.3,.3),'blue')
        self.solid('operator-window',(1,3.35,-2.2),(4,1.4,.22),'glass')
        self.solid('operator-roof',(0,4.8,0),(7,.45,5.8),'steel')
        self.detail('console',(1,1.8,-1.2),(3,1.1,.8),'black')
        self.detail('console-display',(.6,2.5,-1.24),(.9,.5,.1),'light-cyan')
        for x in (-1.8,1.8):self.detail('front-corner-stripe',(x,1.2,-2.42),(.25,1,.13),'yellow')
        self.beam('roof-antenna',(2,4.8,1),(2,7,1),.12,'steel')
        self.detail('task-light',(0,4.48,-2.5),(1.6,.16,.4),'light-white')
        self.scene.light('dress-'+self.name+'-task-light',self.position((0,4.2,-2.5)),(1,.82,.57),24)

    def batching_cell(self):
        # Twin metering hoppers feed one mixing/discharge body, with an outbound
        # conveyor and a separate motor. Cylindrical silhouettes read at 30–60 m.
        self.solid('batch-foot',(0,.4,0),(13,.8,12),'concrete')
        for i,x in enumerate((-3.4,3.4)):
            self.tank('metering-hopper-'+str(i),(x,.8,1),2.3,10)
            self.beam('feed-spout',(x,2.5,0),(x,2.5,-3),.7,'steel')
        self.solid('mixer',(0,2.8,-3),(10,3,4),'blue')
        self.solid('discharge-belt',(0,2,-8),(3.8,.9,8),'steel')
        for x in (-1.6,1.6):
            self.solid('belt-foot-'+str(x),(x,.9,-10),(.6,1.8,.7),'steel')
            self.beam('conveyor-guard',(x,2.65,-12),(x,2.65,-4),.14,'yellow')
        for z in (-11,-9,-7,-5):self.detail('belt-flight',(0,2.55,z),(3.1,.18,.22),'rubber')
        self.solid('mixer-motor',(6,2.3,-3),(1.8,2,3),'steel')
        # Cosmetic motor fan follows the existing match-tick animation owner;
        # pause stops it and its large housing remains the sole physical body.
        group='dress-'+self.name+'-motor-fan'
        self.scene.groups.append(dict(id=group,position=vec(self.position((6,2.3,-4.64))),motion='ROTATE_Z',period=3.5,phase=.2))
        self.scene.group=group
        self.scene.part(group+'-blade-a',(0,0,0),(1.35,.2,.15),'yellow')
        self.scene.part(group+'-blade-b',(0,0,.18),(.2,1.35,.15),'yellow')
        self.scene.group=''

    def pallet_rack(self):
        # The former solid rack silhouette becomes two open storage bays with
        # braces, load beams, pallet feet and several different stored loads.
        for x in (-7,0,7):
            for z in (-3,3):self.solid(f'rack-upright-{x}-{z}',(x,4,z),(.4,8,.4),'blue')
            for y in (1,3.5,6):
                self.solid(f'rack-depth-{x}-{y}',(x,y,0),(.3,.35,6.5),'yellow')
        for y in (1,3.5,6):
            for z in (-3,3):self.solid(f'rack-load-beam-{y}-{z}',(0,y,z),(14.6,.42,.35),'yellow')
            for bay,x in enumerate((-3.5,3.5)):
                self.solid(f'pallet-{y}-{bay}',(x,y+.45,0),(5.8,.45,5.4),'wood')
                self.solid(f'stored-load-{y}-{bay}',(x,y+1.3,0),(4.8,1.3,4.7),'concrete' if bay==0 else 'steel')
                for zz in (-1.6,1.6):self.detail('shipping-band',(x,y+2.02,zz),(4.9,.12,.12),'black')
        for x in (-7,7):
            self.beam('back-cross',(x,1,3),(0,7.5,3),.17,'steel')
            self.solid('post-protector-'+str(x),(x,.7,-3.7),(.9,1.4,.9),'yellow')

    def fabrication_bench(self):
        # Rebar cutting/bending line: bundles at one end, roller bed and a press.
        for x in (-7,7):self.solid('bed-leg-'+str(x),(x,1,0),(.8,2,4),'steel')
        self.solid('roller-bed',(0,2.1,0),(17,.5,3.4),'steel')
        for x in range(-7,8,2):self.beam('roller',(x,2.5,-1.6),(x,2.5,1.6),.42,'steel')
        self.solid('press-base',(4,1.4,-3),(4,2.8,3),'blue')
        for x in (2.5,5.5):self.solid('press-column-'+str(x),(x,3.8,-3),(.6,4.8,.6),'steel')
        self.solid('press-head',(4,6,-3),(4,.8,3.5),'yellow')
        for z in (-.9,-.3,.3,.9):self.beam('bar-bundle',(-8,2.85,z),(3,2.85,z),.14,'rust')
        self.solid('cuttings-bin',(-5,.8,4),(4,1.6,3),'blue')
        self.detail('control-face',(5.95,3,-3),(.12,.8,1),'black')

    def formwork_station(self):
        self.solid('casting-table',(0,1,0),(15,2,10),'concrete')
        for x in (-7.5,7.5):self.solid('side-shutter-'+str(x),(x,2,0),(.35,2,10.4),'wood')
        for z in (-5,5):self.solid('end-shutter-'+str(z),(0,2,z),(15,2,.35),'wood')
        for x in (-6,-3,0,3,6):
            self.beam('reinforcing-mat',(x,2.7,-4.5),(x,2.7,4.5),.12,'steel')
        for z in (-4,-2,0,2,4):self.beam('reinforcing-mat',(-7,2.8,z),(7,2.8,z),.12,'steel')
        for x in (-6,6):
            self.solid('hoist-leg-'+str(x),(x,5,-5.8),(.7,10,.7),'yellow')
            self.beam('hoist-brace',(x,0,-5.8),(x*.7,3,-5.8),.3,'steel')
        self.solid('hoist-beam',(0,10,-5.8),(13.5,.8,1),'yellow')
        self.detail('chain-block',(2,9,-5.8),(1.2,1.4,1.2),'blue')
        self.beam('lifting-chain',(2,8.3,-5.8),(2,4,-5.8),.13,'steel')

    def dock_station(self):
        # Loading dock for palletised panels; the recess, buffers and roller line
        # explain truck access without putting a new obstacle in that access.
        self.solid('dock-sill',(0,.65,0),(17,1.3,8),'concrete')
        for x in (-6,6):self.solid('dock-buffer-'+str(x),(x,.95,-4.4),(1,1.5,.8),'rubber')
        for x in (-7,7):self.solid('dock-post-'+str(x),(x,4,3),(.65,8,.65),'steel')
        self.solid('dock-head',(0,8,3),(15,.6,1),'steel')
        self.solid('packaged-panel',(0,2.1,1),(11,2.7,4),'concrete')
        for x in (-4,0,4):self.detail('panel-straps',(x,3.52,1),(.16,.12,4.1),'black')
        self.solid('dock-control',(7,2.2,-2),(1.6,2.5,1.1),'blue')

    def bridge_assembly(self):
        # Ground assembly mast and bearing preparation alongside the span.
        # The high mast is legible from deck level; it never crosses the road.
        self.solid('mast-foot',(0,.5,0),(9,1,9),'concrete')
        for x in (-2,2):
            for z in (-2,2):self.solid(f'erection-leg-{x}-{z}',(x,10,z),(.7,20,.7),'yellow')
        for y in (3,7,11,15,19):
            self.beam('mast-brace',(-2,y-3,-2),(2,y,-2),.32,'steel')
            self.beam('mast-brace',(-2,y-3,2),(2,y,2),.32,'steel')
        self.solid('mast-head',(0,20,0),(7,1,7),'yellow')
        self.solid('lifting-winch',(1,18,0),(2,2,3),'blue')
        for i,x in enumerate((-5,5)):
            self.solid('bearing-cradle-'+str(i),(x,1.5,6),(4,3,5),'steel')
            self.detail('elastomeric-bearing',(x,3.3,6),(3.5,.6,3.5),'rubber')

    def pipe_station(self):
        self.solid('pump-bed',(0,.3,0),(10,.6,9),'concrete')
        self.tank('water-separator',(-2,.6,0),2.1,7)
        self.solid('electric-pump',(3,1.4,0),(3,2.1,3),'blue')
        for z in (-3,3):
            self.beam('water-main',(-4,1.4,z),(4,1.4,z),.55,'steel')
            self.beam('water-riser',(3,1.4,z),(3,3.8,z),.5,'steel')
            self.detail('valve-wheel',(3,4,z),(.9,.2,.9),'red')

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
    # Replace the three anonymous solid rack blocks within their original safe
    # envelopes. They never owned a driving surface, route or destructible state.
    rack_ids={f'warehouse-rack-{i}' for i in range(3)}
    scene.data['boxes']=[b for b in scene.data['boxes'] if b['id'] not in rack_ids]
    scene.parts=[p for p in scene.parts if p['anchor'] not in rack_ids]
    for i,x in enumerate((1150,1210,1315)):
        d.origin=(x,0,201.5);d.yaw=0;d.name='warehouse-original-rack-'+str(i)
        d.pallet_rack()
        d.installed.append(dict(id=d.name,x=x,z=201.5,radius=9))
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

    # Foreground compositions are authored beside specific passages, not sampled
    # across empty land. Each pocket is checked against every retained corridor.
    # A frame bay contains casting, reinforcement and its operator's station.
    foreground=[
        ('frame-west-casting',[(397,899),(407,900)],(17,15),d.formwork_station,0),
        ('frame-central-fabrication',[(470,897),(462,900)],(20,12),d.fabrication_bench,0),
        ('frame-casting-control',[(432,895),(432,907)],(8,7),d.operator_post,0),
        ('frame-turn-supervision',[(520,827),(516,828)],(8,7),d.operator_post,90),
        ('frame-south-panels',[(410,826),(485,823)],(21,12),d.panel_cradle,0),
        ('frame-north-electricity',[(470,980),(470,1010)],(10,15),d.cable_station,0),
        ('frame-exit-operator',[(470,1025),(480,1035),(535,1045)],(8,7),d.operator_post,90),
        # Dosing, reclaim water, production control and dispatch form a connected
        # process around the existing mixer core and the actual side exit.
        ('plant-west-batching',[(1025,564),(1025,552)],(16,25),d.batching_cell,180),
        ('plant-inner-batching',[(1115,643),(1115,652)],(16,25),d.batching_cell,0),
        ('plant-production-control',[(1080,635),(1090,638)],(8,7),d.operator_post,0),
        ('plant-reclaim-water',[(1192,572),(1190,560)],(12,12),d.pipe_station,0),
        ('plant-north-dispatch',[(1118,742),(1112,736)],(20,15),d.dock_station,90),
        ('plant-east-operator',[(1390,747),(1390,730),(1360,748)],(8,7),d.operator_post,180),
        # The L-shaped warehouse's second row faces the road. The old south
        # rack blocks were replaced above; no giant shelving-shaped solid remains.
        ('warehouse-north-rack-a',[(1165,278),(1150,282)],(17,10),d.pallet_rack,0),
        ('warehouse-north-rack-b',[(1218,282),(1208,282)],(17,10),d.pallet_rack,0),
        ('warehouse-turn-rack',[(1255,205),(1250,205)],(17,10),d.pallet_rack,0),
        ('warehouse-packing-control',[(1188,280),(1188,292)],(8,7),d.operator_post,0),
        ('warehouse-east-dock',[(1298,279),(1290,280)],(20,15),d.dock_station,90),
        ('warehouse-receiving-dock',[(1098,282),(1090,279)],(20,15),d.dock_station,0),
        ('warehouse-north-release',[(1300,362),(1304,375)],(21,12),d.panel_cradle,90),
        # The excavation has real casting/rebar/water operations around its
        # crossing, all on the flat floor and outside the four haul-road mouths.
        ('pit-rebar-bending',[(422,570),(425,577)],(20,12),d.fabrication_bench,0),
        ('pit-dewatering',[(527,565),(540,565)],(12,12),d.pipe_station,0),
        ('pit-casting-control',[(430,490),(430,477),(495,490)],(8,7),d.operator_post,180),
        ('pit-west-landing-store',[(240,575),(230,585)],(17,10),d.pallet_rack,0),
        # An erection mast makes the assembly work visible from the 18 m deck.
        # Bearings and delivery frames stay beside, never across, the ramp/run-up.
        ('interchange-bearing-assembly',[(1305,979),(1310,975)],(17,19),d.bridge_assembly,0),
        ('interchange-south-casting',[(1185,890),(1195,885)],(17,15),d.formwork_station,40),
        ('interchange-north-control',[(1380,1060),(1390,1055),(1320,1020)],(8,7),d.operator_post,0),
        ('interchange-erection-power',[(1288,945),(1295,934),(1340,945)],(10,15),d.cable_station,0),
        ('interchange-exit-beam-delivery',[(1460,1085),(1475,1080),(1490,1075)],(20,20),d.precast_beams,40),
    ]
    for name,candidates,footprint,builder,yaw in foreground:
        d.place(name,candidates,footprint,builder,yaw)
    scene.anchor='';scene.group=''
    print('Construction dressing:',len(d.installed),'workplaces;',len(d.skipped),'rejected by route/solid clearance')
    return d.installed
