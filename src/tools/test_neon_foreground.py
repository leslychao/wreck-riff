"""Exercise the actual new city furniture against authored drivable volumes."""
import copy
import json
import math
import unittest
from author_campaign_arenas import ROOT, neon, ccw, half, area
from author_arena_art import Scene
from dress_neon import dress, _box_polygon


class NeonForegroundTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene=Scene(neon());cls.scene.common();cls.scene.neon()
        cls.before=copy.deepcopy(cls.scene.data)
        cls.places=dress(cls.scene)

    def test_dressing_preserves_navigation_and_combat_placements(self):
        for key in ('nodes','edges','roads','spawns','pickups','launchPads','drops','destructibles','barriers','bosses'):
            self.assertEqual(self.before[key],self.scene.data[key],key)

    def test_new_furniture_leaves_the_vehicle_envelope_clear(self):
        corridors=[]
        for path in self.scene.location.paths:
            for first,last in zip(path['names'],path['names'][1:]):
                a,b=self.scene.location.points[first],self.scene.location.points[last]
                dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                nx,nz=dz/length*(path['width']/2+3),-dx/length*(path['width']/2+3)
                corridors.append((path['id'],min(a[1],b[1]),max(a[1],b[1])+4,
                    ccw([(a[0]+nx,a[2]+nz),(b[0]+nx,b[2]+nz),(b[0]-nx,b[2]-nz),(a[0]-nx,a[2]-nz)])))
        for box in self.scene.data['boxes']:
            if not box['id'].startswith('dress-neon-'):continue
            y,h=box['center']['y'],box['size']['y']
            for name,bottom,top,corridor in corridors:
                if y-h/2>=top or y+h/2<=bottom:continue
                intersection=_box_polygon(box)
                for a,b in zip(corridor,corridor[1:]+corridor[:1]):
                    intersection=half(intersection,a,b) if intersection else []
                self.assertLess(abs(area(intersection)) if intersection else 0,.001,box['id']+' enters '+name)

    def test_every_reviewed_area_has_nearby_foreground(self):
        routes=json.loads((ROOT/'src/tools/assets/architecture/review-routes.json').read_text(encoding='utf-8'))['routes']
        for route in routes:
            if route['arenaId']!='neon_zero':continue
            points=[tuple(p[k] for k in ('x','y','z')) for p in route['points']]
            lengths=[math.dist(a,b) for a,b in zip(points,points[1:])];remaining=sum(lengths)/2
            for index,length in enumerate(lengths):
                if remaining<=length:
                    point=tuple(a+(b-a)*remaining/length for a,b in zip(points[index],points[index+1]));break
                remaining-=length
            distance=min(math.dist(place['position'],point) for place in self.places)
            self.assertLessEqual(distance,65,route['kind']+' / '+route['id'])


if __name__=='__main__':unittest.main()
