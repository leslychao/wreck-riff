"""Structural regressions inspect actual generated members and floor triangles."""
import math
import unittest
from author_arena_models import Model,architectural
from author_campaign_arenas import construction,neon,carnival


def point_segment(p,a,b):
    d=[b[k]-a[k] for k in range(3)];t=max(0,min(1,sum((p[k]-a[k])*d[k] for k in range(3))/sum(v*v for v in d)))
    return math.dist(p,[a[k]+t*d[k] for k in range(3)])


def segment_box(a,b,center,size,yaw=0,margin=0):
    angle=math.radians(yaw);c,s=math.cos(angle),math.sin(angle)
    def local(p):
        x,y,z=[p[k]-center[k] for k in range(3)];return (c*x-s*z,y,s*x+c*z)
    a,b=local(a),local(b);lo,hi=0,1
    for k in range(3):
        extent=size[k]/2+margin;delta=b[k]-a[k]
        if abs(delta)<1e-8:
            if abs(a[k])>extent:return False
        else:
            u,v=sorted(((-extent-a[k])/delta,(extent-a[k])/delta));lo=max(lo,u);hi=min(hi,v)
            if lo>hi:return False
    return True


def boxes_overlap(center,size,other,other_size,yaw=0):
    """Four-axis SAT in XZ plus exact vertical extents; no inflated proxy radius."""
    if abs(center[1]-other[1])>(size[1]+other_size[1])/2+.01:return False
    angle=math.radians(yaw);c,s=math.cos(angle),math.sin(angle)
    delta=(center[0]-other[0],center[2]-other[2])
    first=((1,0),(0,1));second=((c,-s),(s,c))
    for axis in first+second:
        projection=abs(sum(delta[k]*axis[k] for k in range(2)))
        radius=sum(abs(sum(a[k]*axis[k] for k in range(2)))*extent/2 for a,extent in zip(first,(size[0],size[2])))
        radius+=sum(abs(sum(a[k]*axis[k] for k in range(2)))*extent/2 for a,extent in zip(second,(other_size[0],other_size[2])))
        if projection>radius+.01:return False
    return True


class StructureLoadPathTest(unittest.TestCase):
    def test_gables_have_interior_exterior_and_closed_edge_faces(self):
        m=Model();m.gable(230,135,6,'skin')
        for facez,normal in [(-67.5,-1),(-67.2,1),(67.2,-1),(67.5,1)]:
            faces=[m.materials['skin'][i:i+3] for i in range(0,len(m.materials['skin']),3) if all(abs(v[0][2]-facez)<1e-5 for v in m.materials['skin'][i:i+3])]
            self.assertTrue(faces)
            self.assertTrue(all(face[0][1][2]*normal>.99 for face in faces))
        # Independently count undirected geometric edges: each closed prism edge
        # belongs to exactly two triangles, including the underside and slopes.
        from collections import Counter
        edges=Counter()
        for i in range(0,len(m.materials['skin']),3):
            p=[tuple(round(x,6) for x in v[0]) for v in m.materials['skin'][i:i+3]]
            for a,b in zip(p,p[1:]+p[:1]):edges[tuple(sorted((a,b)))]+=1
        self.assertEqual({2},set(edges.values()))

    def test_warehouse_upper_chord_and_web_heads_follow_the_actual_six_metre_roof(self):
        m=architectural('warehouse-trusses')
        roof=lambda x:6*(1-abs(x)/115)-.95
        heads=[]
        for a,b,width in m.members:
            if abs(a[0]-b[0])<1e-7 and abs(a[2]-b[2])<1e-7 and abs(a[1]+2)<1e-7:
                self.assertAlmostEqual(roof(b[0]),b[1],places=6);heads.append(b)
        self.assertTrue(heads)
        for head in heads:
            self.assertGreater(sum(point_segment(head,a,b)<1e-6 for a,b,_ in m.members),1,'A web must join another physical member at its head')

    def test_steel_roof_members_form_connected_frames_bearing_on_real_grounded_solids(self):
        cases=[(construction,'plant-sawtooth',(1105,23,605)),(construction,'warehouse-trusses',(1235,15,247.5)),(neon,'passage-glass',(822.5,18,765)),(carnival,'depot-gantry',(1230,15,1055)),(carnival,'orbit-shell',(1272.5,20,480))]
        for factory,name,origin in cases:
            location=factory();model=architectural(name)
            if name=='orbit-shell':
                from author_arena_art import Scene
                from dress_carnival import ParkDressing
                scene=Scene(location);ParkDressing(scene).roof_structure();location.data=scene.data
            members=[(tuple(a[k]+origin[k] for k in range(3)),tuple(b[k]+origin[k] for k in range(3)),w) for a,b,w in model.members]
            grounded=[]
            for box in location.data['boxes']:
                center=tuple(box['center'][k] for k in ('x','y','z'));size=tuple(box['size'][k] for k in ('x','y','z'))
                if box['collision'] and center[1]-size[1]/2<=.05:grounded.append((center,size,box['yawDegrees']))
            for c,s in model.solids:
                center=tuple(c[k]+origin[k] for k in range(3))
                if center[1]-s[1]/2<=.05:grounded.append((center,s,0))
            neighbours=[set() for _ in members];bearing=set()
            for i,(a,b,width) in enumerate(members):
                if any(segment_box(a,b,c,s,yaw,width/2) for c,s,yaw in grounded):bearing.add(i)
                for j in range(i):
                    aa,bb,ww=members[j]
                    if min(point_segment(a,aa,bb),point_segment(b,aa,bb),point_segment(aa,a,b),point_segment(bb,a,b))<=(width+ww)/2+.01:
                        neighbours[i].add(j);neighbours[j].add(i)
            reached=set(bearing);queue=list(bearing)
            while queue:
                for other in neighbours[queue.pop()]-reached:reached.add(other);queue.append(other)
            self.assertEqual(set(range(len(members))),reached,name+' has a disconnected roof member')
            for i,(a,b,width) in enumerate(members):
                for endpoint in (a,b):
                    attached=any(segment_box(endpoint,endpoint,c,s,yaw,width/2) for c,s,yaw in grounded) or any(point_segment(endpoint,aa,bb)<=(width+ww)/2+.01 for j,(aa,bb,ww) in enumerate(members) if j!=i)
                    self.assertTrue(attached,(name,i,endpoint))

    def test_every_raised_parking_surface_has_a_closed_physical_underside(self):
        a=neon();a.compile_geometry()
        raised=[m for m in a.data['meshes'] if m['id'] in ('parking-first-floor','parking-roof-deck') or m['id'].startswith('road-parking-') and max(v['y'] for v in m['vertices'])>0]
        self.assertTrue(raised)
        for mesh in raised:self.assertEqual(1.2,mesh['thickness'],mesh['id'])
        surfaces={s['geometryId']:s for s in a.data['surfaces']}
        for mesh in raised:
            if min(v['y'] for v in mesh['vertices'])==16:
                self.assertEqual(2,surfaces[mesh['id']]['level'],mesh['id'])
        for column in (b for b in a.data['boxes'] if b['id'].startswith('parking-column-')):
            p=column['center'];top=p['y']+column['size']['y']/2
            candidates=[]
            for mesh in raised:
                for i in range(0,len(mesh['indices']),3):
                    triangle=[mesh['vertices'][j] for j in mesh['indices'][i:i+3]]
                    if max(v['y'] for v in triangle)-min(v['y'] for v in triangle)>.001:continue
                    height=triangle[0]['y']
                    if abs(height-top)>.81:continue
                    from author_campaign_arenas import inside
                    if inside((p['x'],p['z']),[(v['x'],v['z']) for v in triangle]):candidates.append(height-mesh['thickness'])
            self.assertTrue(candidates,column['id']+' must meet the real slab, outside its ramp holes')
            self.assertGreaterEqual(top,min(candidates))
        model=architectural('parking-ceiling')
        # The decorative model must not recreate a broad slab inside the actual
        # floor shell; its exposed beams attach to the shell's 6.8m underside.
        for c,s in model.solids:
            self.assertLess(s[0],12)
            if s[1]>.5:self.assertGreaterEqual(c[1]+s[1]/2+7.2,6.8)

    def test_concrete_slab_components_connect_to_actual_grounded_columns(self):
        for factory,name,origin in [(construction,'unfinished-frame',(477.5,16,885)),(neon,'technical-rooftop',(1092.5,14,1005))]:
            location=factory();model=architectural(name)
            solids=[(tuple(c[k]+origin[k] for k in range(3)),s) for c,s in model.solids]
            ground=[]
            for b in location.data['boxes']:
                if b['collision'] and b['center']['y']-b['size']['y']/2<=.05:
                    ground.append((tuple(b['center'][k] for k in ('x','y','z')),tuple(b['size'][k] for k in ('x','y','z')),b['yawDegrees']))
            reached={i for i,(c,s) in enumerate(solids) if c[1]-s[1]/2<=.05 or any(boxes_overlap(c,s,cc,ss,yaw) for cc,ss,yaw in ground)}
            while True:
                before=len(reached)
                for i,(c,s) in enumerate(solids):
                    if any(all(abs(c[k]-solids[j][0][k])<=(s[k]+solids[j][1][k])/2+.01 for k in range(3)) for j in reached):reached.add(i)
                if len(reached)==before:break
            self.assertEqual(set(range(len(solids))),reached,name)

    def test_circus_cloth_ribs_and_closed_ring_connect_to_the_grounded_mast_system(self):
        from author_arena_art import Scene
        from dress_carnival import ParkDressing
        scene=Scene(carnival());recorded=[];original=scene.beam
        def capture(name,a,b,width,material):
            recorded.append((a,b,width));original(name,a,b,width,material)
        scene.beam=capture;ParkDressing(scene).roof_structure()
        canopy=architectural('circus-canopy');origin=(347.5,18,1065)
        members=recorded+[(tuple(a[k]+origin[k] for k in range(3)),tuple(b[k]+origin[k] for k in range(3)),w) for a,b,w in canopy.members]
        # Derive the central mast from actual exported cylinder vertices, not a
        # separately invented attachment point. Its base overlaps the rib apex.
        vertices=[p for p,_,_ in canopy.materials['yellow']]
        low,high=min(p[1] for p in vertices),max(p[1] for p in vertices)
        members.append(((347.5,low+18,1065),(347.5,high+18,1065),.8))
        anchors=[]
        for box in scene.data['boxes']:
            if 'circus-' not in box['id'] or not box['collision']:continue
            c=tuple(box['center'][k] for k in ('x','y','z'));s=tuple(box['size'][k] for k in ('x','y','z'))
            if c[1]-s[1]/2<=.05:anchors.append((c,s,box['yawDegrees']))
        connected=[set() for _ in members];reached=set()
        for i,(a,b,width) in enumerate(members):
            if any(segment_box(a,b,c,s,yaw,width/2) for c,s,yaw in anchors):reached.add(i)
            for j,(aa,bb,ww) in enumerate(members[:i]):
                if min(point_segment(a,aa,bb),point_segment(b,aa,bb),point_segment(aa,a,b),point_segment(bb,a,b))<=(width+ww)/2+.01:
                    connected[i].add(j);connected[j].add(i)
        queue=list(reached)
        while queue:
            for j in connected[queue.pop()]-reached:reached.add(j);queue.append(j)
        self.assertEqual(set(range(len(members))),reached)

    def test_three_lake_bridges_have_bearings_from_bed_to_the_closed_deck(self):
        a=carnival();a.compile_geometry();boxes={b['id']:b for b in a.data['boxes']}
        for direction in ('west','east','north'):
            caps=[b for key,b in boxes.items() if key.startswith(direction+'-bridge-cap-')]
            piers=[b for key,b in boxes.items() if key.startswith((direction+'-bridge-pier-',direction+'-bridge-pile-'))]
            self.assertTrue(caps and piers)
            for cap in caps:
                x,z=cap['center']['x'],cap['center']['z'];surface=a.surface_at((x,3,z));deck=next(m for m in a.data['meshes'] if m['id']==surface)
                self.assertAlmostEqual(1.2,deck['thickness'])
                self.assertAlmostEqual(3-deck['thickness']+.05,cap['center']['y']+cap['size']['y']/2)
                self.assertTrue(any(abs(p['center']['x']-x)<=(p['size']['x']+cap['size']['x'])/2 and abs(p['center']['z']-z)<=(p['size']['z']+cap['size']['z'])/2 and p['center']['y']+p['size']['y']/2>=cap['center']['y']-cap['size']['y']/2 for p in piers))
            for pier in piers:
                self.assertLessEqual(pier['center']['y']-pier['size']['y']/2,-18)

    def test_interchange_portal_caps_follow_the_ramp_and_leave_eight_metres_below(self):
        a=construction();a.compile_geometry()
        for name,start,end in [('interchange-rise-portal','ramp_s','deck_s'),('interchange-descent-portal','deck_n','ramp_n')]:
            mesh=next(m for m in a.data['meshes'] if m['id']==name+'-cap');s,e=a.points[start],a.points[end];dx,dz=e[0]-s[0],e[2]-s[2]
            for p in mesh['vertices']:
                expected=s[1]+(e[1]-s[1])*((p['x']-s[0])*dx+(p['z']-s[2])*dz)/(dx*dx+dz*dz)-1.2
                self.assertAlmostEqual(expected,p['y'],places=4)
                self.assertGreaterEqual(p['y']-mesh['thickness'],8)
            for leg in [b for b in a.data['boxes'] if b['id'].startswith(name+'-leg-')]:
                self.assertAlmostEqual(0,leg['center']['y']-leg['size']['y']/2)


if __name__=='__main__':unittest.main()
