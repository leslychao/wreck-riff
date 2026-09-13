"""Supply sockets must be accessible on the actual shipping floor and underbody."""
import collections
import json
import math
import unittest
from author_campaign_arenas import AMMO,AMMO_COUNTS,OUT,construction,neon,carnival,point,inside


class SupplyGeometryTest(unittest.TestCase):
    def test_every_large_map_socket_has_vehicle_height_and_every_boss_entry_starts_clear(self):
        for factory in (construction,neon,carnival):
            a=factory();a.data=json.loads((OUT/(a.resource+'.json')).read_text(encoding='utf8'))
            ammunition=[p for p in a.data['pickups'] if p['type'] in AMMO]
            self.assertEqual(dict(zip(AMMO,AMMO_COUNTS[a.data['id']])),dict(collections.Counter(p['type'] for p in ammunition)))
            for pickup in ammunition:
                p=point(pickup['position'])
                self.assertTrue(a.supply_headroom(p),(a.data['id'],pickup['id']))
                for boss in a.data['bosses']:
                    for entrance in boss['entrances']:
                        self.assertGreaterEqual(math.dist(p,point(entrance['position'])),5,(a.data['id'],pickup['id']))
            for district in a.data['districts']:
                polygon=[(p['x'],p['z']) for p in district['boundary']]
                local={p['type'] for p in ammunition if inside((p['position']['x'],p['position']['z']),polygon)}
                self.assertTrue(set(AMMO)<=local,(a.data['id'],district['id']))

    def test_classic_ammunition_is_never_buried_in_the_ramp_footprint(self):
        data=json.loads((OUT/'arena.json').read_text(encoding='utf8'))
        ammunition=[p for p in data['pickups'] if p['type'] in AMMO]
        self.assertEqual({kind:4 for kind in AMMO},dict(collections.Counter(p['type'] for p in ammunition)))
        for pickup in ammunition:
            p=pickup['position']
            for ramp in data['ramps']:
                if not (ramp['minX']-2<=p['x']<=ramp['maxX']+2 and ramp['minZ']-2<=p['z']<=ramp['maxZ']+2):continue
                axis=ramp['axis'];coordinate=p['x'] if axis=='X' else p['z'];lo=ramp['minX'] if axis=='X' else ramp['minZ'];hi=ramp['maxX'] if axis=='X' else ramp['maxZ']
                t=max(0,min(1,(coordinate-lo)/(hi-lo)));height=ramp['startY']+(ramp['endY']-ramp['startY'])*t
                self.assertLessEqual(height,p['y']+.05,(pickup['id'],ramp['id']))


if __name__=='__main__':unittest.main()
