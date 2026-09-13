"""Foreground must stay visible along the inspected passages and outside their driving envelopes."""
import copy
import json
import math
import unittest
from author_arena_art import Scene
from author_campaign_arenas import ROOT,construction,ccw,half,area
from dress_construction import dress


class ConstructionForegroundTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene=Scene(construction());cls.scene.common();cls.scene.construction()
        cls.before=copy.deepcopy(cls.scene.data)
        cls.workplaces=dress(cls.scene)

    def test_foreground_preserves_the_verified_roads_support_and_combat_positions(self):
        for key in ('meshes','nodes','edges','surfaces','roads','spawns','pickups','launchPads','drops','destructibles','barriers','bosses'):
            self.assertEqual(self.before[key],self.scene.data[key],key)

    def test_all_solid_footprints_leave_four_metres_beyond_every_driving_corridor(self):
        corridors=[]
        for path in self.scene.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.scene.location.points[first],self.scene.location.points[last]
                dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                nx,nz=dz/length*(path['width']/2+4),-dx/length*(path['width']/2+4)
                corridors.append((path['id'],ccw([(a[0]+nx,a[2]+nz),(b[0]+nx,b[2]+nz),(b[0]-nx,b[2]-nz),(a[0]-nx,a[2]-nz)])))
        for box in self.scene.data['boxes']:
            if not box['id'].startswith('dress-construction-'):continue
            # Edge guards are structurally attached below the deck and receive
            # the exact road-edge/support test below, not the equipment setback.
            if box['id'].startswith('dress-construction-guardrail-'):continue
            c,s=box['center'],box['size'];r=math.radians(box['yawDegrees'])
            poly=ccw([(c['x']+math.cos(r)*x+math.sin(r)*z,c['z']-math.sin(r)*x+math.cos(r)*z)
                      for x,z in [(-s['x']/2,-s['z']/2),(s['x']/2,-s['z']/2),(s['x']/2,s['z']/2),(-s['x']/2,s['z']/2)]])
            for name,corridor in corridors:
                intersection=poly
                for a,b in zip(corridor,corridor[1:]+corridor[:1]):intersection=half(intersection,a,b) if intersection else []
                self.assertLess(abs(area(intersection)) if intersection else 0,.001,box['id']+' enters '+name)

    def test_deck_guards_leave_the_full_driving_top_open_and_brackets_touch_its_side(self):
        guards=[b for b in self.scene.data['boxes'] if b['id'].startswith('dress-construction-guardrail-')]
        self.assertTrue(guards)
        for box in guards:
            c,s=box['center'],box['size'];angle=math.radians(box['yawDegrees'])
            if '-bracket-' in box['id']:
                self.assertLess(c['y']+s['y']/2,18)
                self.assertGreater(c['y']-s['y']/2,16.8)
                # A mounting arm penetrates the real slab side by 0.2 m.
                points=[(c['x']+math.cos(angle)*xx+math.sin(angle)*zz,c['z']-math.sin(angle)*xx+math.cos(angle)*zz)
                        for xx in (-s['x']/2,s['x']/2) for zz in (-s['z']/2,s['z']/2)]
                supported=False
                for path in self.scene.location.paths:
                    if path['id']!='interchange-span':continue
                    for first,last in zip(path['names'],path['names'][1:]):
                        a,b=self.scene.location.points[first],self.scene.location.points[last]
                        dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                        supported|=any(abs((x-a[0])*dz-(z-a[2])*dx)/length<path['width']/2 for x,z in points)
                self.assertTrue(supported,box['id'])
            else:
                # The nearest post/rail face is beyond the road by at least 0.15m.
                for path in self.scene.location.paths:
                    for first,last in zip(path['names'],path['names'][1:]):
                        a,b=self.scene.location.points[first],self.scene.location.points[last]
                        if max(a[1],b[1])<12:continue
                        dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                        nx,nz=dz/length*(path['width']/2+.15),-dx/length*(path['width']/2+.15)
                        corridor=ccw([(a[0]+nx,a[2]+nz),(b[0]+nx,b[2]+nz),(b[0]-nx,b[2]-nz),(a[0]-nx,a[2]-nz)])
                        poly=ccw([(c['x']+math.cos(angle)*x+math.sin(angle)*z,c['z']-math.sin(angle)*x+math.cos(angle)*z)
                                  for x,z in [(-s['x']/2,-s['z']/2),(s['x']/2,-s['z']/2),(s['x']/2,s['z']/2),(-s['x']/2,s['z']/2)]])
                        for p,q in zip(corridor,corridor[1:]+corridor[:1]):poly=half(poly,p,q) if poly else []
                        self.assertLess(abs(area(poly)) if poly else 0,.001,box['id']+' enters '+path['id'])

    def test_each_district_and_interior_has_a_real_workplace_within_sixty_metres(self):
        routes=json.loads((ROOT/'src/tools/assets/architecture/review-routes.json').read_text(encoding='utf-8'))['routes']
        for route in routes:
            if route['arenaId']!='construction_17':continue
            points=[tuple(p[k] for k in ('x','y','z')) for p in route['points']]
            lengths=[math.dist(a,b) for a,b in zip(points,points[1:])];remaining=sum(lengths)/2
            for index,length in enumerate(lengths):
                if remaining<=length:
                    p=tuple(a+(b-a)*remaining/length for a,b in zip(points[index],points[index+1]));break
                remaining-=length
            distance=min(math.hypot(w['x']-p[0],w['z']-p[2]) for w in self.workplaces)
            self.assertLessEqual(distance,60,route['kind']+' / '+route['id'])

    def test_metering_hoppers_use_the_visible_model_collider_and_racks_are_open(self):
        identities={b['id']:b for b in self.scene.data['boxes']}
        self.assertFalse(any(name in identities for name in ('warehouse-rack-0','warehouse-rack-1','warehouse-rack-2')))
        models=[m for m in self.scene.models if m['id'].startswith('dress-construction-')]
        self.assertTrue(models)
        for model in models:
            self.assertTrue(model['collisionGeometryIds'])
            for proxy in model['collisionGeometryIds']:self.assertFalse(identities[proxy]['collision'])
            self.assertTrue((ROOT/'src/main/resources'/model['asset']).is_file())
            self.assertTrue((ROOT/'src/main/resources'/model['distantAsset']).is_file())

    def test_visible_details_do_not_duplicate_coplanar_primitives(self):
        signatures=[json.dumps({k:v for k,v in part.items() if k not in ('id','anchor')},sort_keys=True) for part in self.scene.parts]
        self.assertEqual(len(signatures),len(set(signatures)))


if __name__=='__main__':unittest.main()
