"""Drive-clear furnishing and visible, physically supported park frontage."""
import copy
import json
import math
import unittest
from author_arena_art import Scene
from author_campaign_arenas import ROOT,carnival,ccw,half,area
from dress_carnival import dress,ParkDressing


class CarnivalForegroundTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene=Scene(carnival());cls.scene.common();cls.scene.carnival()
        cls.before=copy.deepcopy(cls.scene.data);cls.workplaces=dress(cls.scene)

    def test_verified_driving_and_supply_data_are_preserved(self):
        for key in ('meshes','nodes','edges','surfaces','roads','spawns','pickups','launchPads','drops','destructibles','barriers','bosses'):
            self.assertEqual(self.before[key],self.scene.data[key],key)

    def test_every_new_solid_leaves_the_full_driving_corridor_plus_four_metres_and_eight_metres_high(self):
        corridors=[]
        for path in self.scene.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.scene.location.points[first],self.scene.location.points[last]
                dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                nx,nz=dz/length*(path['width']/2+4),-dx/length*(path['width']/2+4)
                corridors.append((path['id'],ccw([(a[0]+nx,a[2]+nz),(b[0]+nx,b[2]+nz),(b[0]-nx,b[2]-nz),(a[0]-nx,a[2]-nz)])))
        for box in self.scene.data['boxes']:
            if not box['id'].startswith('dress-carnival-'):continue
            c,s=box['center'],box['size'];r=math.radians(box['yawDegrees'])
            # Structural lintels may span a portal above the vehicle/camera
            # volume. Floor-standing columns remain subject to the full test.
            if c['y']-s['y']/2>=8:continue
            poly=ccw([(c['x']+math.cos(r)*x+math.sin(r)*z,c['z']-math.sin(r)*x+math.cos(r)*z)
                for x,z in [(-s['x']/2,-s['z']/2),(s['x']/2,-s['z']/2),(s['x']/2,s['z']/2),(-s['x']/2,s['z']/2)]])
            for name,corridor in corridors:
                hit=poly
                for a,b in zip(corridor,corridor[1:]+corridor[:1]):hit=half(hit,a,b) if hit else []
                self.assertLess(abs(area(hit)) if hit else 0,.001,box['id']+' enters '+name)

    def test_new_free_standing_objects_leave_the_entire_launch_flight_clear(self):
        for workplace in self.workplaces:
            if 'existingEnvelope' in workplace or 'attachedTo' in workplace:continue
            for pad in self.scene.data['launchPads']:
                a=tuple(pad['source'][k] for k in ('x','y','z'));b=tuple(pad['target'][k] for k in ('x','y','z'))
                distance=ParkDressing.segment_distance(workplace['x'],workplace['z'],a,b)
                self.assertGreaterEqual(distance,workplace['radius']+8,workplace['id'])

    def test_all_six_districts_and_three_interiors_have_foreground_within_sixty_metres(self):
        routes=json.loads((ROOT/'src/tools/assets/architecture/review-routes.json').read_text(encoding='utf-8'))['routes']
        for route in routes:
            if route['arenaId']!='euphoria_park':continue
            points=[tuple(p[k] for k in ('x','y','z')) for p in route['points']]
            lengths=[math.dist(a,b) for a,b in zip(points,points[1:])];remaining=sum(lengths)/2
            for i,length in enumerate(lengths):
                if remaining<=length:
                    p=tuple(a+(b-a)*remaining/length for a,b in zip(points[i],points[i+1]));break
                remaining-=length
            distance=min(math.hypot(w['x']-p[0],w['z']-p[2]) for w in self.workplaces)
            self.assertLessEqual(distance,60,route['kind']+' / '+route['id'])

    def test_metadata_proxies_do_not_generate_floating_windows_or_roof_units(self):
        proxies={b['id'] for b in self.scene.data['boxes'] if not b['collision']}
        for p in self.scene.parts:
            if p['id'].startswith(('architectural-window','parapet-coping','roof-service-unit','street-address')):
                self.assertNotIn(p['anchor'],proxies,p['id'])

    def test_details_are_not_duplicate_coplanar_primitives(self):
        signatures=[json.dumps({k:v for k,v in part.items() if k not in ('id','anchor')},sort_keys=True) for part in self.scene.parts]
        self.assertEqual(len(signatures),len(set(signatures)))


if __name__=='__main__':unittest.main()
