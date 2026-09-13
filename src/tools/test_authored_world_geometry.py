"""Offline geometric regression checks against the shipped authored triangles.

Run: python -m unittest discover -s src/tools -p test_authored_world_geometry.py
These checks supplement native driving/render review; they do not approve artwork.
"""
import collections
import json
import unittest
from author_campaign_arenas import OUT,area,ccw,half,rect,subtract

class AuthoredWorldGeometryTest(unittest.TestCase):
    def test_convex_subtraction_preserves_area_without_duplicate_faces(self):
        subject=rect(0,100,0,100);clip=rect(20,80,30,60)
        self.assertAlmostEqual(8200,sum(abs(area(p)) for p in subtract(subject,clip)))

    def test_flat_surfaces_have_no_competing_coplanar_meshes(self):
        for name in ('construction-17','neon-zero','euphoria-park'):
            with self.subTest(name=name):
                data=json.loads((OUT/f'arena-{name}.json').read_text(encoding='utf-8'))
                triangles=[];grid=collections.defaultdict(list)
                for mesh in data['meshes']:
                    if not mesh['collision']:continue
                    for i in range(0,len(mesh['indices']),3):
                        vertices=[mesh['vertices'][j] for j in mesh['indices'][i:i+3]]
                        if max(p['y'] for p in vertices)-min(p['y'] for p in vertices)>.0001:continue
                        poly=[(p['x'],p['z']) for p in vertices];height=round(vertices[0]['y'],3);index=len(triangles)
                        triangles.append((mesh['id'],poly))
                        keys=[(height,x,z) for x in range(int(min(p[0] for p in poly)//100),int(max(p[0] for p in poly)//100)+1)
                              for z in range(int(min(p[1] for p in poly)//100),int(max(p[1] for p in poly)//100)+1)]
                        for other in set(j for key in keys for j in grid[key]):
                            identity,q=triangles[other]
                            if identity==mesh['id']:continue
                            intersection=poly
                            for a,b in zip(ccw(q),ccw(q)[1:]+ccw(q)[:1]):intersection=half(intersection,a,b) if intersection else []
                            overlap=abs(area(intersection)) if intersection else 0
                            self.assertLessEqual(overlap,.01,f'{name}: {identity} / {mesh["id"]}: {overlap} m²')
                        for key in keys:grid[key].append(index)

    def test_generated_scene_ids_and_large_details_do_not_duplicate(self):
        for name in ('construction-17','neon-zero','euphoria-park'):
            data=json.loads((OUT/f'arena-{name}.json').read_text(encoding='utf-8'))
            for key in ('boxes','meshes','surfaces','roads','nodes','edges','pickups'):
                ids=[item['id'] for item in data[key]]
                self.assertEqual(len(ids),len(set(ids)),f'{name}: duplicate {key}')
            art=json.loads((OUT/f'arena-art-{name}.json').read_text(encoding='utf-8'))
            signatures=[json.dumps({k:v for k,v in part.items() if k not in ('id','anchor')},sort_keys=True) for part in art['parts']]
            self.assertEqual(len(signatures),len(set(signatures)),f'{name}: coincident decorative primitives')

    def test_authored_roads_reference_actual_geometry_and_navigation(self):
        for name in ('construction-17','neon-zero','euphoria-park'):
            data=json.loads((OUT/f'arena-{name}.json').read_text(encoding='utf-8'))
            self.assertTrue(data['roads'])
            self.assertEqual(3,data['layoutRevision'])
            meshes={m['id'] for m in data['meshes']};nodes={n['id'] for n in data['nodes']}
            for road in data['roads']:
                self.assertTrue(road['geometryIds'])
                self.assertGreaterEqual(len(road['navNodeIds']),2)
                self.assertTrue(set(road['geometryIds'])<=meshes)
                self.assertTrue(set(road['navNodeIds'])<=nodes)

    def test_open_tunnel_cuttings_have_full_height_retaining_faces_along_both_sides(self):
        data=json.loads((OUT/'arena-neon-zero.json').read_text(encoding='utf-8'))
        solids={box['id']:box for box in data['boxes']}
        for end,lo,hi in [('south',400,540),('north',820,960)]:
            for side,x,inside in [('west',1162,1163),('east',1218,1217)]:
                wall=solids[f'tunnel-{end}-{side}-retaining'];c,s=wall['center'],wall['size']
                self.assertTrue(wall['collision'])
                self.assertEqual(-90,wall['yawDegrees'])
                self.assertAlmostEqual(x,c['x'])
                self.assertAlmostEqual(lo,c['z']-s['x']/2)
                self.assertAlmostEqual(hi,c['z']+s['x']/2)
                self.assertAlmostEqual(-12,c['y']-s['y']/2)
                self.assertGreaterEqual(c['y']+s['y']/2,0)
                self.assertAlmostEqual(inside,c['x']+(s['z']/2 if side=='west' else -s['z']/2))
                # Wall is a vertical solid outside the complete 54m road.
                self.assertEqual(2,s['z'])

if __name__=='__main__':unittest.main()
