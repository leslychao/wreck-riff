"""Euphoria's public frontage, audience areas and working attraction equipment.

Every freestanding installation reserves a measured pocket outside all authored
driving corridors. Large components are canonical solids; decorative details are
attached to an existing solid or are inside its protected footprint. No random
buildings, ground overlays or road/nav changes are made by this dressing pass.
"""
import math
from author_campaign_arenas import vec


class ParkDressing:
    def __init__(self, scene):
        self.scene=scene;self.location=scene.location
        self.installed=[];self.skipped=[];self.origin=(0,0,0);self.yaw=0;self.name=''

    @staticmethod
    def segment_distance(x,z,a,b):
        dx,dz=b[0]-a[0],b[2]-a[2]
        t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
        return math.hypot(x-a[0]-t*dx,z-a[2]-t*dz)

    def road_clear(self,x,z,radius):
        for path in self.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                if self.segment_distance(x,z,self.location.points[first],self.location.points[last])<path['width']/2+radius+4:
                    return False
        # Keep the complete optional stunt flight clear, including a rotated
        # Emcee's body. A pad's source and target alone are insufficient.
        for pad in self.scene.data['launchPads']:
            a=tuple(pad['source'][k] for k in ('x','y','z'));b=tuple(pad['target'][k] for k in ('x','y','z'))
            if self.segment_distance(x,z,a,b)<radius+8:return False
        return True

    def clear(self,x,z,radius,y=0,ignore=()):
        if not self.road_clear(x,z,radius):return False
        for item in self.scene.data['spawns']+self.scene.data['pickups']:
            p=item['position']
            if abs(p['y']-y)<5 and math.hypot(x-p['x'],z-p['z'])<radius+7:return False
        for b in self.scene.data['boxes']:
            if b['id'] in ignore or not b['collision']:continue
            c,s=b['center'],b['size']
            if c['y']-s['y']/2>y+11 or c['y']+s['y']/2<y+.15:continue
            r=math.radians(b['yawDegrees']);dx,dz=x-c['x'],z-c['z']
            if abs(math.cos(r)*dx-math.sin(r)*dz)<s['x']/2+radius+1 \
                    and abs(math.sin(r)*dx+math.cos(r)*dz)<s['z']/2+radius+1:return False
        return True

    def place(self,name,candidates,footprint,builder,yaw=0,y=0):
        radius=math.hypot(*footprint)/2
        for x,z in candidates:
            if not self.clear(x,z,radius,y):continue
            self.origin=(x,y,z);self.yaw=yaw;self.name=name;self.scene.anchor='';self.scene.group=''
            builder();self.installed.append(dict(id=name,x=x,z=z,radius=radius,y=y));return True
        self.skipped.append(name);return False

    def position(self,p):
        a=math.radians(self.yaw)
        return (self.origin[0]+math.cos(a)*p[0]+math.sin(a)*p[2],self.origin[1]+p[1],self.origin[2]-math.sin(a)*p[0]+math.cos(a)*p[2])

    def solid(self,label,p,size,material='steel'):
        identity='dress-carnival-'+self.name+'-'+label
        assert not any(b['id']==identity for b in self.scene.data['boxes']),identity
        self.scene.data['boxes'].append(dict(id=identity,center=vec(self.position(p)),size=vec(size),material=material,collision=True,yawDegrees=self.yaw))
        self.scene.anchor=identity;return identity

    def detail(self,label,p,size,material,shape='BOX'):
        self.scene.part('dress-carnival-'+self.name+'-'+label,self.position(p),size,material,shape,(0,self.yaw,0))

    def beam(self,label,a,b,width,material='steel'):
        self.scene.beam('dress-carnival-'+self.name+'-'+label,self.position(a),self.position(b),width,material)

    def seating(self):
        # A bank of real stepped terraces, with a central stair gap. Individual
        # chairs sit above the supporting tier, never as a floating colour sheet.
        for row in range(4):
            height=.65*(row+1);z=-3.8+row*2.5
            for side in (-1,1):
                self.solid(f'tier-{row}-{side}',(side*5.2,height/2,z),(8.4,height,2.5),'concrete')
                for seat in range(5):
                    x=side*5.2-3.2+seat*1.6
                    self.detail('seat',(x,height+.45,z),(1.15,.25,1.15),'faded-red' if row%2 else 'blue')
                    self.detail('seat-back',(x,height+1,z+.55),(1.15,1.1,.25),'faded-red' if row%2 else 'blue')
            self.solid('stair-'+str(row),(0,height/2,z),(1.8,height,2.5),'concrete')
        for x in (-9.8,9.8):
            for z in (-4.8,5):self.solid('rail-post-'+str(x)+'-'+str(z),(x,2.2,z),(.22,4.4,.22),'steel')
            self.beam('stepped-handrail',(x,1.6,-4.8),(x,4.5,5),.18,'yellow')

    def pergola(self):
        for x in (-6,6):
            for z in (-3.4,3.4):self.solid(f'post-{x}-{z}',(x,2.7,z),(.5,5.4,.5),'wood')
            self.solid('length-beam-'+str(x),(x,5.2,0),(.55,.5,8),'wood')
        for x in range(-6,7,2):self.solid('roof-louvre-'+str(x),(x,5.55,0),(.7,.35,8.4),'wood')
        for z in (-2.4,2.4):
            self.solid('bench-'+str(z),(0,.65,z),(9,1.3,1.2),'wood')
            self.detail('bench-back',(0,1.6,z+(.55 if z>0 else -.55)),(9,1,.18),'wood')
        self.solid('planter',(7.5,.6,0),(2,1.2,5),'concrete')
        for z in (-1.5,0,1.5):self.detail('plant',(7.5,1.7,z),(1.7,1.8,1.7),'park-leaf','SPHERE')

    def ticket_board(self):
        # Park wayfinding reads as a framed map, opening hours and attraction
        # silhouettes. It is physically supported, visible from a moving car.
        for x in (-3.5,3.5):self.solid('post-'+str(x),(x,3,0),(.45,6,.45),'steel')
        self.solid('map-frame',(0,4.2,0),(8,4.2,.5),'wood')
        self.detail('map-face',(0,4.2,-.36),(7.2,3.4,.2),'ivory')
        self.detail('lake-symbol',(.1,4.2,-.51),(2.8,2.4,.1),'blue','SPHERE')
        for i,(x,y) in enumerate([(-2.5,4.8),(2.4,3.3),(2.5,5.1),(-2.3,3.5)]):
            self.detail('district-marker',(x,y,-.59),(.65,.65,.12),'faded-red' if i%2 else 'yellow')
        self.solid('canopy',(0,6.5,-.25),(9,.45,3.2),'faded-red')
        self.detail('lamp',(0,6.12,-1.3),(3,.16,.35),'light-amber')

    def game_front(self):
        self.solid('floor',(0,.2,0),(10,.4,6),'wood')
        self.solid('target-wall',(0,3,2.6),(10,5.6,.4),'blue')
        self.solid('counter',(0,1.3,-2.6),(10,2.2,.7),'wood')
        for x in (-4.8,4.8):self.solid('post-'+str(x),(x,3,0),(.35,6,.4),'ivory')
        self.solid('roof',(0,6,0),(10.5,.5,6.5),'faded-red')
        for row in range(2):
            for col in range(5):
                x=-3.6+col*1.8;y=2.2+row*1.6
                self.detail('target',(x,y,2.24),(1.15,1.15,.2),'yellow','SPHERE')
                self.detail('target-centre',(x,y,2.08),(.4,.4,.14),'faded-red','SPHERE')
        for x in (-3,0,3):self.detail('counter-pad',(x,2.51,-2.6),(2,.18,.65),'rubber')

    def ride_car(self):
        self.solid('service-plinth',(0,.3,0),(9,.6,6),'concrete')
        self.solid('chassis',(0,1.3,0),(7,.8,3.6),'steel')
        self.solid('front-cowl',(0,2,-1.6),(7,1.5,.65),'yellow')
        for x in (-3.3,3.3):self.solid('side-'+str(x),(x,2.1,0),(.6,1.8,3.6),'blue')
        self.solid('back-cowl',(0,2.1,1.6),(7,1.8,.55),'blue')
        for x in (-2.2,0,2.2):
            self.detail('seat',(x,2,0),(1.55,.3,1.7),'rubber')
            self.detail('backrest',(x,2.7,1.1),(1.55,1.4,.3),'rubber')
            self.beam('safety-restraint',(x-.7,3,-.6),(x+.7,3,-.6),.22,'steel')
        for x in (-2.4,2.4):
            for z in (-1.9,1.9):self.detail('wheel',(x,1,z),(.8,.8,.6),'rubber','SPHERE')

    def drive_motor(self):
        self.solid('machine-foundation',(0,.3,0),(9,.6,7),'concrete')
        self.solid('motor-housing',(-1,2,0),(4,3.2,4),'blue')
        self.solid('gearbox',(2.3,1.7,0),(2.5,2.6,3.3),'steel')
        for x in (-2.5,-1.5,-.5,.5):self.detail('cooling-fin',(x,2.2,0),(.18,3.6,4.4),'steel')
        self.beam('drive-shaft',(3.5,2,0),(4.3,2,0),.8,'steel')
        self.solid('control-cabinet',(-3.4,2.3,2.5),(1.5,3.8,1.2),'ivory')
        self.detail('control-display',(-3.4,2.8,1.8),(.75,.55,.18),'light-cyan')
        self.detail('warning',(-3.4,1.8,1.78),(.8,.6,.16),'yellow')
        group='dress-carnival-'+self.name+'-cooling-fan'
        self.scene.groups.append(dict(id=group,position=vec(self.position((-1,2,-2.3))),motion='ROTATE_Z',period=4,phase=0))
        self.scene.group=group
        self.scene.part(group+'-a',(0,0,0),(2.4,.25,.2),'steel')
        self.scene.part(group+'-b',(0,0,.24),(.25,2.4,.2),'steel')
        self.scene.group=''

    def workshop(self):
        # An open repair bay, including task tools, hanging hoses and supported
        # parts shelves. The through drive remains outside this eleven-metre bay.
        for x in (-4.5,4.5):
            for z in (-2,2):self.solid(f'leg-{x}-{z}',(x,1.15,z),(.45,2.3,.45),'steel')
        self.solid('worktop',(0,2.45,0),(10,.4,5),'wood')
        self.solid('tool-board',(0,4.3,2.2),(10,3.3,.35),'steel')
        for i in range(9):
            x=-4+i
            self.detail('hanging-tool',(x,4.4+(i%3)*.35,1.93),(.18,1.1,.18),'yellow' if i%2 else 'steel')
        self.solid('vice',(-3,2.95,-1.3),(1.2,.65,1),'steel')
        for x in (-1,1,3):self.detail('bearing',(x,2.94,-.3),(1.15,.7,1.15),'steel','SPHERE')
        self.solid('drawer-unit',(2,1.25,0),(4,2.2,3.3),'blue')
        for y in (.5,1.1,1.7):self.detail('drawer-handle',(2,y,-1.77),(2.5,.12,.18),'steel')
        self.solid('shelf-post',(-4.4,4.2,2.4),(.3,8.4,.3),'steel')
        self.solid('shelf-post-right',(4.4,4.2,2.4),(.3,8.4,.3),'steel')
        self.solid('high-shelf',(0,6.6,2.5),(9.5,.3,1.5),'steel')
        for i in range(4):self.detail('spare-case',(-3.2+i*2.1,7.3,2.5),(1.6,1.1,1.2),'wood')
        self.detail('task-lamp',(0,5.7,1.9),(5,.2,.4),'light-white')
        self.scene.light('dress-carnival-'+self.name+'-lamp',self.position((0,5.3,1.4)),(1,.8,.5),18)

    def stage_equipment(self):
        self.solid('case-foot',(0,.3,0),(11,.6,5),'steel')
        for x in (-4,4):
            self.solid('speaker-'+str(x),(x,2.4,0),(2.4,4.2,2),'black')
            for y in (1.3,3.3):self.detail('speaker-driver',(x,y,-1.14),(1.6,1.6,.2),'rubber','SPHERE')
        self.solid('mixing-desk',(0,1.65,0),(3.5,2.4,2.5),'steel')
        for i in range(6):self.detail('fader',(-1.25+i*.5,2.94,-.2),(.12,.14,1.3),'yellow')
        for x in (-5,5):self.solid('lighting-stand-'+str(x),(x,3,1.4),(.3,6,.3),'steel')
        self.solid('lighting-truss',(0,6.1,1.4),(10.5,.45,.45),'steel')
        for x in (-3,0,3):
            self.detail('spotlight',(x,5.7,1.25),(.9,.8,1.1),'black')
            self.detail('lens',(x,5.55,.63),(.6,.5,.15),'light-amber')

    def frontage(self,b):
        c,s=b['center'],b['size'];x,z=c['x'],c['z'];closest=None
        for path in self.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,q=self.location.points[first],self.location.points[last];dx,dz=q[0]-a[0],q[2]-a[2]
                t=max(0,min(1,((x-a[0])*dx+(z-a[2])*dz)/(dx*dx+dz*dz)))
                p=(a[0]+t*dx,a[2]+t*dz);distance=math.hypot(p[0]-x,p[1]-z)
                if closest is None or distance<closest[0]:closest=(distance,p)
        direction=(closest[1][0]-x,closest[1][1]-z)
        orientations=[b['yawDegrees']+90*i for i in range(4)]
        yaw=max(orientations,key=lambda angle:-math.sin(math.radians(angle))*direction[0]-math.cos(math.radians(angle))*direction[1])
        swapped=round((yaw-b['yawDegrees'])/90)%2
        width,depth=(s['z'],s['x']) if swapped else (s['x'],s['z'])
        self.origin=(x,c['y']-s['y']/2,z);self.yaw=yaw;self.name=b['id'];self.scene.anchor=b['id'];self.scene.group=''
        face=-depth/2;h=min(s['y']-.7,5.3)
        # Main opening is a framed dark recess; projecting counter/canopy solids
        # receive the same transform as the original stall footprint.
        self.detail('recess',(0,h/2+.5,face-.18),(width*.82,h,.3),'black')
        for xx in (-width*.44,width*.44):self.detail('front-jamb',(xx,3,face-.4),(.6,5.5,.5),'wood')
        self.detail('front-sign',(0,min(s['y']-.6,6.5),face-.4),(width*.88,1,.5),'yellow' if 'shop-' in b['id'] else 'ivory')
        titles={'sweet':'СЛАДОСТИ','bakery':'ПЕКАРНЯ','tea':'ЧАЙНАЯ','shooting':'ТИР','arcade':'ИГРОТЕКА','fortune':'ПРЕДСКАЗАНИЯ','prize':'ПРИЗЫ','souvenir':'СУВЕНИРЫ','photo':'ФОТО','puppet':'ТЕАТР КУКОЛ'}
        title=next((text for key,text in titles.items() if key in b['id']),'БИЛЕТЫ' if b['id'].startswith('ticket-office') else 'ЯРМАРКА')
        self.scene.sign(b['id']+'-title',title,self.position((0,min(s['y']-.6,6.5),face-.72)),yaw+180,width*.8,.8)
        # The flank has a timber frame, weatherboard courses and a service
        # hatch, so an oblique approach reads as a built kiosk rather than a box.
        # All pieces stay within decimetres of the existing solid wall.
        for side in (-1,1):
            xx=side*(width/2+.16)
            for zz in (-depth/2+.35,0,depth/2-.35):
                self.detail('side-pier',(xx,s['y']/2,zz),(.4,s['y'],.55),'wood')
            for yy in (.6,2.9,s['y']-.35):
                self.detail('side-weatherboard',(xx,yy,0),(.34,.38,depth),'ivory' if 'ticket-office' in b['id'] else 'wood')
            self.detail('side-hatch',(xx+side*.13,3.3,depth*.2),(.18,2.6,depth*.27),'steel')
            self.detail('hatch-head',(xx+side*.24,4.75,depth*.2),(.22,.28,depth*.32),'wood')
        front=self.position((0,0,face-1.3))
        # Existing authored stalls are close to the lane. A shallow overhang is
        # allowed only when the full footprint still leaves four clear metres.
        canopy_radius=math.hypot(width*.92,2.6)/2
        if self.road_clear(front[0],front[2],canopy_radius):
            self.solid('awning',(0,6.9,face-1.3),(width*.92,.45,2.6),'ivory')
            for n in range(0,max(2,int(width/3))):
                xx=-width*.44+(n+.5)*width*.88/max(2,int(width/3))
                self.detail('awning-stripe',(xx,7.2,face-1.3),(.85,.14,2.6),'faded-red' if 'fortune' not in b['id'] else 'purple')
        self.detail('counter',(0,1.6,face-.55),(width*.78,.35,1),'wood')
        label=b['id']
        for i in range(max(3,int(width/4))):
            xx=-width*.32+i*width*.64/max(1,int(width/4)-1)
            if any(n in label for n in ('sweet','bakery','tea')):
                self.detail('display-tray',(xx,2.1,face-.47),(2.1,.25,.7),'steel')
                self.detail('bakery-display',(xx,2.45,face-.52),(1.7,.65,.65),'yellow','SPHERE')
            elif any(n in label for n in ('shooting','arcade')):
                self.detail('game-target',(xx,3.9,face-.4),(1.7,1.7,.35),'yellow','SPHERE')
                self.detail('target-centre',(xx,3.9,face-.62),(.6,.6,.13),'faded-red','SPHERE')
            else:
                self.detail('prize',(xx,3.3,face-.45),(1.6,2.2,.6),'blue' if i%2 else 'purple')
                self.detail('prize-head',(xx,4.6,face-.45),(1.6,1.2,.65),'ivory','SPHERE')
        self.detail('service-door',(-width*.2,2.1,depth/2+.25),(2.5,4.2,.45),'steel')
        self.detail('rear-vent',(width*.2,3,depth/2+.28),(3,1.6,.5),'black')
        for offset in (-.3,0,.3):self.detail('vent-fin',(width*.2,3+offset,depth/2+.56),(3,.13,.13),'steel')
        self.pavilion_roof(b,width,depth)
        self.installed.append(dict(id=label+'-frontage',x=front[0],z=front[2],radius=0,y=0,attachedTo=b['id']))

    def pavilion_roof(self,building,width,depth):
        """Small venues have pitched, hipped or stepped roofs on their own walls.

        Upward roof faces and their thickness supply both visible and native
        geometry. The short open eave is a supported ventilated canopy, not an
        invisible box filling the sloped roof's bounding volume.
        """
        height=building['size']['y'];label=building['id']
        if any(word in label for word in ('tea','fortune','ticket')):
            style='hipped';rise=4.4 if 'fortune' in label else 3.2
            local=[(-width/2,height+.2,-depth/2),(width/2,height+.2,-depth/2),
                   (width/2,height+.2,depth/2),(-width/2,height+.2,depth/2),(0,height+rise,0)]
            faces=[(0,4,1),(1,4,2),(2,4,3),(3,4,0)]
        elif any(word in label for word in ('arcade','shooting','photo')):
            style='clerestory';rise=2.4
            local=[(-width/2,height+.2,-depth/2),(width/2,height+.2,-depth/2),
                   (-width/2,height+rise,depth*.15),(width/2,height+rise,depth*.15),
                   (-width/2,height+.2,depth/2),(width/2,height+.2,depth/2)]
            faces=[(0,2,1),(1,2,3),(2,4,3),(3,4,5)]
        else:
            style='gable';rise=3.6
            local=[(-width/2,height+.2,-depth/2),(width/2,height+.2,-depth/2),
                   (-width/2,height+rise,0),(width/2,height+rise,0),
                   (-width/2,height+.2,depth/2),(width/2,height+.2,depth/2)]
            faces=[(0,2,1),(1,2,3),(2,4,3),(3,4,5)]
        identity='dress-carnival-'+label+'-'+style+'-roof'
        material='purple' if 'fortune' in label else 'wood' if any(w in label for w in ('tea','bakery','souvenir')) else 'faded-red'
        self.scene.data['meshes'].append(dict(id=identity,vertices=[vec(self.position(p)) for p in local],
                indices=[i for triangle in faces for i in triangle],material=material,collision=True,triangleMaterials=[],thickness=.35))
        self.scene.data['surfaces'].append(dict(id=identity,geometryId=identity,level=1,grip=1))
        self.scene.anchor=building['id']
        for side in (-1,1):
            self.detail('eave-timber',(side*(width/2-.25),height,0),(.5,.45,depth),'wood')
            ridge_x=0 if style=='hipped' else side*(width/2-.25)
            self.beam('roof-rafter',(side*(width/2-.25),height+.05,-depth/2),
                      (ridge_x,height+rise-.25,0 if style!='clerestory' else depth*.15),.25,'wood')
            self.beam('roof-rafter',(ridge_x,height+rise-.25,0 if style!='clerestory' else depth*.15),
                      (side*(width/2-.25),height+.05,depth/2),.25,'wood')
        # A continuous service-side cornice and restrained narrow end boards
        # replace a single unexplained metal rectangle on each pavilion flank.
        for side in (-1,1):
            for i in range(max(3,int(depth/2.2))):
                zz=-depth/2+1+i*(depth-2)/max(1,int(depth/2.2)-1)
                self.detail('end-board',(side*(width/2+.38),height*.5,zz),(.18,height-.5,.22),'wood')

    def bridge_edges(self):
        """Vehicle guardrails attach to the three existing level bridge decks.

        Junction mouths stay open. Curbs/posts/rails are canonical collision
        solids; their inner faces leave at least width minus 1.4 metres clear.
        """
        for path in self.location.paths:
            if path['id'] not in ('west-lake-bridge','east-lake-bridge','north-lake-bridge'):continue
            points=[self.location.points[n] for n in path['names']]
            total=sum(math.dist(a,b) for a,b in zip(points,points[1:]));before=0;end_opening=path.get('junctionEnd',20)
            material='wood' if path['id'].startswith('west') else 'steel' if path['id'].startswith('east') else 'cast-concrete'
            for segment,(a,b) in enumerate(zip(points,points[1:])):
                dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                start=max(0,20-before);end=min(length,total-end_opening-before)
                self.origin=a;self.yaw=math.degrees(math.atan2(dx,dz));self.name=path['id']+'-guard';self.scene.group=''
                suffix='' if len(points)==2 else '-segment-'+str(segment)
                if end>start+.05:
                    for side in (-1,1):
                        edge=side*(path['width']/2-.4);mid=(start+end)/2
                        self.solid('curb-'+str(side)+suffix,(edge,.22,mid),(.6,.44,end-start),material)
                        self.solid('top-rail-'+str(side)+suffix,(edge,1.55,mid),(.3,.3,end-start),'wood' if material=='wood' else 'steel')
                        self.solid('middle-rail-'+str(side)+suffix,(edge,.95,mid),(.2,.2,end-start),material)
                        sections=math.ceil((total-40)/7)
                        for index in range(sections+1):
                            global_distance=20+index*(total-40)/sections
                            if global_distance<before+start-.0001 or global_distance>before+end+.0001:continue
                            if segment and abs(global_distance-before)<.0001:continue
                            along=global_distance-before
                            self.solid(f'post-{side}-{index}',(edge,.95,along),(.4,1.9,.4),material)
                            if index%4==0:self.detail('reflector',(edge,.98,along),(.46,.22,.46),'yellow')
                before+=length
            a,b=points[0],points[-1]
            self.installed.append(dict(id=self.name,x=(a[0]+b[0])/2,z=(a[2]+b[2])/2,radius=0,y=a[1],attachedTo=path['id']))

    def entrance_portal(self):
        # The two ticket houses support a real entrance span across the public
        # lane. Neither approach from the initial spawn is narrowed by a gate.
        self.origin=(0,0,0);self.yaw=0;self.name='main-entrance';self.scene.group=''
        for x in (136,259):
            self.solid('tower-'+str(x),(x,8,250),(4,16,6),'ivory')
            self.solid('tower-cap-'+str(x),(x,16.5,250),(6,1,8),'faded-red')
        self.solid('lintel',(197.5,15,250),(127,2.4,2.4),'steel')
        self.solid('sign-backing',(197.5,17.5,250),(69,5,.8),'faded-red')
        self.scene.sign('main-entrance-name','ЛУНАПАРК ЭЙФОРИЯ',(197.5,17.6,249.52),180,64,3.5)
        self.installed.append(dict(id=self.name,x=197.5,z=250,radius=0,y=14,attachedTo='ticket-office-west'))

    def fitted_equipment(self):
        # Replace the three monolithic depot workbench blocks with functional
        # open-legged benches within precisely the original protected envelopes.
        for b in list(self.scene.data['boxes']):
            if b['id'].startswith('repair-bench-'):
                self.scene.data['boxes'].remove(b);c=b['center'];self.origin=(c['x']-5 if b['id']=='repair-bench-0' else c['x'],0,c['z']);self.yaw=0;self.name=b['id'];self.workshop()
                self.installed.append(dict(id=self.name,x=c['x'],z=c['z'],radius=10,y=0,existingEnvelope=b['id']))
        core=next(b for b in self.scene.data['boxes'] if b['id']=='orbit-ride-core')
        c=core['center'];self.origin=(c['x'],c['y']+core['size']['y']/2,c['z']);self.yaw=0;self.name='orbit-drive';self.scene.anchor=core['id']
        self.solid('bearing-base',(0,1.1,0),(14,2.2,12),'steel')
        self.solid('central-shaft',(0,5.8,0),(3.2,9,3.2),'steel')
        self.scene.cylinder('orbit-bearing',self.position((0,2.5,0)),6,1.5,'ivory')
        for i,(dx,dz) in enumerate([(-1,0),(1,0),(0,-1),(0,1)]):
            self.beam('radial-arm',(dx*2,6,dz*2),(dx*17,6,dz*13),1.3,'yellow')
            self.detail('ride-seat-bank',(dx*19,5.7,dz*15),(7 if dx else 14,2.5,10 if dx else 5),'blue')
            self.detail('seat-bank-back',(dx*19,7,dz*15+2.3),(7 if dx else 14,1.3,.4),'rubber')
        self.installed.append(dict(id='orbit-drive',x=c['x'],z=c['z'],radius=20,y=self.origin[1],existingEnvelope=core['id']))
        for b in list(self.scene.data['boxes']):
            if b['id'] not in ('circus-seating-east','circus-seating-west'):continue
            c,s=b['center'],b['size'];self.scene.data['boxes'].remove(b);self.origin=(c['x']+7 if b['id']=='circus-seating-west' else c['x'],0,c['z']);self.yaw=0;self.name=b['id'];self.seating()
            self.installed.append(dict(id=self.name,x=c['x'],z=c['z'],radius=10,y=0,existingEnvelope=b['id']))

    def roof_structure(self):
        self.origin=(0,0,0);self.yaw=0;self.name='roof-structure';self.scene.anchor=''
        # The barrel shell bears on columns placed in the existing side-wall
        # envelopes; the openings retain their complete verified driving width.
        for i,(x,z) in enumerate([(1175,485),(1175,545),(1245,550),(1250,410),(1305,410),(1370,460),(1370,490)]):
            assert self.road_clear(x,z,1),('orbit support',x,z)
            local_x=x-1272.5;height=20+12*math.sin(math.pi*(local_x+102.5)/205)-.8
            self.solid('orbit-column-'+str(i),(x,height/2,z),(1.4,height,1.4),'steel')
            self.solid('orbit-column-foot-'+str(i),(x,.35,z),(2,.7,2),'cast-concrete')
        # The broad tent uses two real king poles, a perimeter tension ring and
        # an asymmetric set of edge poles: the missing sectors are vehicle doors.
        for i,(x,z) in enumerate([(347.5,1005),(397.5,1115)]):
            assert self.road_clear(x,z,1.2),('circus king pole',x,z)
            self.solid('circus-king-pole-'+str(i),(x,21,z),(1.5,42,1.5),'ivory')
            self.solid('circus-king-foot-'+str(i),(x,.45,z),(2.4,.9,2.4),'cast-concrete')
            self.beam('circus-ridge-stay',(x,40,z),(347.5,38,1065),.4,'steel')
        ring=[]
        for i in range(16):
            a=i*math.tau/16;x,z=347.5+102.5*math.cos(a),1065+90*math.sin(a);ring.append((x,17.45,z))
            if self.road_clear(x,z,.8):
                self.solid('circus-edge-pole-'+str(i),(x,8.7,z),(.9,17.4,.9),'ivory')
                self.beam('circus-tension-cable',(x,17.5,z),(347.5,37.5,1065),.18,'steel')
        for a,b in zip(ring,ring[1:]+ring[:1]):self.beam('circus-tension-ring',a,b,.45,'steel')
        # Fabric side walls turn the canopy into a tent. Full-width road mouths
        # interrupt the perimeter naturally; no curtain crosses a drive route.
        for sector,(a,b) in enumerate(zip(ring,ring[1:]+ring[:1])):
            dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz);sections=math.ceil(length/5)
            for panel in range(sections):
                t=(panel+.5)/sections;x,z=a[0]+dx*t,a[2]+dz*t
                if not self.road_clear(x,z,length/sections/2+.25):continue
                self.origin=(x,0,z);self.yaw=math.degrees(math.atan2(dx,dz));self.name=f'circus-curtain-{sector}-{panel}'
                self.solid('fabric',(0,8.65,0),(.35,17.3,length/sections+.02),'faded-red' if panel%2 else 'ivory')
                self.solid('ground-anchor',(0,.25,0),(.6,.5,length/sections+.02),'steel')
        self.origin=(0,0,0);self.yaw=0;self.name='roof-structure'
        # Depot columns coincide with the established solid side wall footprints.
        # The floor-to-eave columns also carry the longitudinal gantry runway.
        for i,(x,z) in enumerate([(1145,990),(1220,990),(1290,990),(1150,1120),(1200,1120)]):
            assert self.road_clear(x,z,1),('depot support',x,z)
            height=15+5*(1-abs((x-1230)/90))-.8
            self.solid('depot-column-'+str(i),(x,height/2,z),(1.4,height,1.4),'steel')
        for z in (990,1120):
            # Closed gable fascia reaches the unchanged side-wall top. The
            # roadway portals remain below the 14m clear eave, never sealed.
            self.solid('depot-eave-beam-'+str(z),(1230,14.15,z),(180,1.7,1.2),'steel')

    def venue_signs(self):
        # Flat panels attach to existing solid walls; names face the public
        # approaches. Their elevated position leaves the entire car volume clear.
        for name,title,anchor,p,yaw,width in [
            ('circus-name','ЦИРК ЭЙФОРИЯ','circus-se',(402.988,9.5,986.426),155.56,36),
            ('orbit-name','ОРБИТА','ridehall-south',(1310,9.5,408.82),180,26),
            ('depot-name','РЕМОНТНОЕ ДЕПО','depot-south-east',(1260,11,988.82),180,40),
            ('island-name','ОСТРОВНАЯ СЦЕНА','island-bandstand-proxy',(755,11.8,689),180,20),
        ]:
            self.scene.anchor=anchor;self.scene.group=''
            self.scene.part(name+'-panel',p,(width,2.5,.4),'ivory',rotation=(0,yaw,0))
            a=math.radians(yaw);front=(p[0]+math.sin(a)*.24,p[1],p[2]+math.cos(a)*.24)
            self.scene.sign(name,title,front,yaw,width-1,1.8)


def dress(scene):
    d=ParkDressing(scene)
    for b in list(scene.data['boxes']):
        if b['id'].startswith(('fair-stall-','fair-shop-','ticket-office-')):d.frontage(b)
    d.fitted_equipment()
    d.roof_structure()
    d.venue_signs()
    d.bridge_edges()
    d.entrance_portal()
    # Named public/service functions have explicit alternative positions within
    # their own district. The choices are only collision-safe siting alternatives.
    sites=[
        ('arrival-map',[(160,285),(150,302),(148,320)],(10,5),d.ticket_board,0),
        ('entrance-rest',[(236,310),(240,325),(245,350)],(17,10),d.pergola,0),
        ('ticket-queue-map',[(223,175),(218,205),(218,230)],(10,5),d.ticket_board,180),
        ('fair-west-game',[(235,645),(226,634),(224,618)],(12,8),d.game_front,90),
        ('fair-east-game',[(367,580),(366,601),(365,625)],(12,8),d.game_front,-90),
        ('fair-sitting',[(357,760),(357,790),(365,820)],(17,10),d.pergola,0),
        ('fair-north-map',[(355,872),(359,897),(378,877)],(10,5),d.ticket_board,0),
        ('circus-arena-seats',[(366,1055),(375,1049),(373,1054)],(21,12),d.seating,90),
        ('circus-backstage-desk',[(371,1128),(373,1144),(398,1140)],(12,7),d.workshop,180),
        ('circus-audio',[(293,1008),(297,1045),(293,1060)],(12,7),d.stage_equipment,90),
        ('circus-arrival-map',[(282,927),(291,910),(280,894)],(10,5),d.ticket_board,0),
        ('circus-exit-rest',[(473,1110),(485,1112),(514,1130)],(17,10),d.pergola,180),
        ('orbit-operator',[(1200,494),(1218,502),(1210,535)],(12,7),d.workshop,90),
        ('orbit-drive-display',[(1340,468),(1350,455),(1325,480)],(10,8),d.drive_motor,0),
        ('orbit-car-return',[(1308,537),(1340,552),(1360,567)],(10,7),d.ride_car,180),
        ('rides-entry-map',[(1158,399),(1156,419),(1165,390)],(10,5),d.ticket_board,0),
        ('ferris-drive',[(1250,709),(1250,733),(1263,743)],(10,8),d.drive_motor,0),
        ('ferris-queue-rest',[(1317,755),(1322,776),(1333,754)],(17,10),d.pergola,90),
        ('rides-shore-rest',[(1138,648),(1148,671),(1140,695)],(17,10),d.pergola,90),
        ('depot-service-bench',[(1180,1080),(1165,1080),(1195,1084)],(12,7),d.workshop,180),
        ('depot-assembled-car',[(1290,1080),(1288,1095),(1300,1095)],(10,7),d.ride_car,90),
        ('depot-drive-rebuild',[(1220,1090),(1220,1070),(1204,1100)],(10,8),d.drive_motor,180),
        ('depot-unloading-car',[(1350,1000),(1355,977),(1340,965)],(10,7),d.ride_car,90),
        ('depot-dispatch-car',[(1185,1193),(1200,1190)],(10,7),d.ride_car,180),
        ('north-service-desk',[(1330,930),(1330,904),(1320,953)],(12,7),d.workshop,180),
        ('lake-west-map',[(452,735),(458,760),(450,780)],(10,5),d.ticket_board,90),
        ('lake-south-rest',[(685,381),(687,354),(713,365)],(17,10),d.pergola,0),
        ('lake-north-rest',[(872,971),(904,943),(930,924)],(17,10),d.pergola,180),
    ]
    for name,candidates,size,builder,yaw in sites:d.place(name,candidates,size,builder,yaw)
    # Stage equipment stands on the existing bandstand plinth, inside the eight
    # structural posts and outside all island road and turning envelopes.
    d.origin=(760,4.2,719);d.yaw=180;d.name='island-stage';d.stage_equipment()
    d.installed.append(dict(id='island-stage',x=760,z=719,radius=6,y=4.2,existingEnvelope='island-stage-base'))
    scene.anchor='';scene.group=''
    scene.location.park_foreground=dict(installed=d.installed,skipped=d.skipped)
    return d.installed
