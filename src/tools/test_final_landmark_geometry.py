"""Actual support, road volume and fixed supplies for the final authored changes."""
import json
import math
import unittest
from author_arena_models import architectural
from author_campaign_arenas import SOURCE,area,ccw,half,inside,point,construction,neon,carnival


class FinalLandmarkGeometryTest(unittest.TestCase):
    def test_irregular_excavation_has_real_benches_and_preserves_all_four_haul_entries(self):
        a=construction();a.compile_geometry()
        self.assertEqual(8,len(a.holes[0]))
        self.assertLess(abs(area(a.holes[0])),370*400)
        for label,p in [('west',(322.5,-7,530)),('east',(607.5,-7,530)),('south',(465,-7,372.5)),('north',(465,-7,687.5))]:
            self.assertEqual('pit-'+label+'-slope',a.surface_at(p))
        for suffix,centre in [(3,(587.5,642.5)),(7,(343.75,403.75))]:
            mesh=next(m for m in a.data['meshes'] if m['id']=='pit-corner-'+str(suffix))
            flat=[]
            for i in range(0,len(mesh['indices']),3):
                ps=[mesh['vertices'][j] for j in mesh['indices'][i:i+3]]
                if all(abs(p['y']+6)<1e-4 for p in ps):flat.append([(p['x'],p['z']) for p in ps])
            self.assertGreater(sum(abs(area(poly)) for poly in flat),200)
            self.assertEqual(mesh['id'],a.surface_at((centre[0],-6,centre[1])))
        self.assertEqual(.8,next(s['grip'] for s in a.data['surfaces'] if s['geometryId']=='pit-floor'))

    def test_curved_eastern_bridge_is_physical_connected_deck_and_keeps_all_three_crossings(self):
        a=carnival();a.compile_geometry()
        bridges=[p for p in a.paths if p['id'].endswith('-lake-bridge')]
        self.assertEqual(3,len(bridges));east=next(p for p in bridges if p['id']=='east-lake-bridge')
        points=[a.points[n] for n in east['names']]
        self.assertGreater(max(p[2] for p in points)-min(p[2] for p in points),10)
        for first,last in zip(points,points[1:]):
            for t in (.1,.5,.9):
                p=tuple(first[k]+(last[k]-first[k])*t for k in range(3))
                self.assertTrue(a.surface_at(p).startswith('road-east-lake-bridge-'))

    def test_existing_spawn_yaws_and_all_supply_positions_are_pinned_without_redistribution(self):
        layouts=json.loads((SOURCE/'supply-layout.json').read_text(encoding='utf8'))['arenas']
        for factory in (construction,neon,carnival):
            a=factory();data=a.compile();layout=layouts[data['id']]
            self.assertEqual(layout['spawns'],data['spawns']);self.assertEqual(layout['pickups'],data['pickups'])
            for pickup in data['pickups']:self.assertTrue(a.surface_at(point(pickup['position'])),pickup['id'])

    def test_east_abutment_turning_apron_closes_the_native_gap_without_covering_lower_roads(self):
        a=carnival();a.compile_geometry()
        for p in [(1034.6,3,642.35),(1032.72,3,641.06)]:self.assertEqual('east-bridge-turning-apron',a.surface_at(p))
        apron=next(poly for name,poly,*_ in a.fixed if name=='east-bridge-turning-apron')
        for poly,first,last,path in a.footprints:
            if first[1] or last[1]:continue
            hit=apron
            for p,q in zip(ccw(poly),ccw(poly)[1:]+ccw(poly)[:1]):hit=half(hit,p,q) if hit else []
            self.assertLess(abs(area(hit)) if hit else 0,.001,path['id'])
        for support in (b for b in a.data['boxes'] if b['id'].startswith('east-abutment-support-')):
            c,s=support['center'],support['size']
            self.assertAlmostEqual(1.8,c['y']+s['y']/2)
            for dx in (-1.5,1.5):
                for dz in (-1.5,1.5):self.assertTrue(a.surface_at((c['x']+dx,3,c['z']+dz)))
        wings=[poly for poly,_,_,path in a.footprints if path['id']=='east-bridge-fall' and len(poly)==3]
        self.assertEqual(2,len(wings))
        for wing in wings:
            for road,first,last,path in a.footprints:
                if first[1] or last[1]:continue
                hit=wing
                for p,q in zip(ccw(road),ccw(road)[1:]+ccw(road)[:1]):hit=half(hit,p,q) if hit else []
                self.assertLess(abs(area(hit)) if hit else 0,.001,path['id'])
        for name in ('east_turn_s','east_turn_w'):
            x,y,z=a.points[name]
            for dx in (-4,4):
                for dz in (-4,4):self.assertTrue(a.surface_at((x+dx,y,z+dz)))

    def test_coaster_actual_mesh_leaves_eight_metre_road_volume_and_has_lower_lod(self):
        a=carnival();model=architectural('comet-coaster');low=architectural('comet-coaster',True)
        self.assertLess(sum(map(len,low.materials.values())),sum(map(len,model.materials.values())))
        polygons=[]
        for path in a.paths:
            for na,nb in zip(path['names'],path['names'][1:]):
                first,last=a.points[na],a.points[nb]
                if min(first[1],last[1])>0:continue
                dx,dz=last[0]-first[0],last[2]-first[2];length=math.hypot(dx,dz);width=path['width']/2+4
                nx,nz=dz/length*width,-dx/length*width
                poly=ccw([(first[0]+nx,first[2]+nz),(last[0]+nx,last[2]+nz),(last[0]-nx,last[2]-nz),(first[0]-nx,first[2]-nz)])
                polygons.append((path['id'],poly))
        for material,vertices in model.materials.items():
            for i in range(0,len(vertices),3):
                triangle=[v[0] for v in vertices[i:i+3]]
                if min(v[1] for v in triangle)>=8:continue
                poly=[(v[0]+1310,v[2]+315) for v in triangle]
                for name,corridor in polygons:
                    if max(p[0] for p in poly)<min(p[0] for p in corridor) or min(p[0] for p in poly)>max(p[0] for p in corridor) or max(p[1] for p in poly)<min(p[1] for p in corridor) or min(p[1] for p in poly)>max(p[1] for p in corridor):continue
                    hit=poly
                    for p,q in zip(corridor,corridor[1:]+corridor[:1]):hit=half(hit,p,q) if hit else []
                    self.assertLess(abs(area(hit)) if hit else 0,.001,(material,i,name))
        blueprint=next(item for item in a.models if item['id']=='comet-coaster')
        self.assertGreaterEqual(len(blueprint['supports']),12)
        for support in blueprint['supports']:
            x,y,z=support['position'];self.assertTrue(a.landscape_clear(x+1310,z+315,10,ignore=('comet-coaster-proxy',)))

    def test_sawtooth_glazing_stops_inside_closed_end_wedges(self):
        for low in (False,True):
            m=architectural('plant-sawtooth',low)
            self.assertLessEqual(max(abs(v[0][2]) for v in m.materials['glass']),99.7)
            end_faces=[m.materials['rust'][i:i+3] for i in range(0,len(m.materials['rust']),3) if all(abs(abs(v[0][2])-100)<1e-7 for v in m.materials['rust'][i:i+3])]
            self.assertTrue(end_faces)

    def test_coaster_columns_meet_footing_tops_without_buried_or_competing_faces(self):
        for low in (False,True):
            model=architectural('comet-coaster',low)
            footings=[(c,s) for c,s in model.solids if s==(2.1,.6,2.1)]
            self.assertTrue(footings)
            for c,s in footings:
                for material in ('blue','cast-concrete'):
                    vertices=model.materials[material]
                    for i in range(0,len(vertices),3):
                        triangle=[v[0] for v in vertices[i:i+3]]
                        if max(v[0] for v in triangle)<c[0]-.41 or min(v[0] for v in triangle)>c[0]+.41 or max(v[2] for v in triangle)<c[2]-.41 or min(v[2] for v in triangle)>c[2]+.41:continue
                        if material=='blue':self.assertGreaterEqual(min(v[1] for v in triangle),.6-1e-7)
                        if not all(abs(v[1]-.6)<1e-7 for v in triangle):continue
                        hole=[(c[0]-.4,c[2]-.4),(c[0]+.4,c[2]-.4),(c[0]+.4,c[2]+.4),(c[0]-.4,c[2]+.4)]
                        hit=[(v[0],v[2]) for v in triangle]
                        for p,q in zip(hole,hole[1:]+hole[:1]):hit=half(hit,p,q) if hit else []
                        self.assertLess(abs(area(hit)) if hit else 0,1e-7)


if __name__=='__main__':unittest.main()
