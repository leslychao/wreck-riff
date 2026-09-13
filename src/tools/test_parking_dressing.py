"""Run: python -m unittest discover -s src/tools -p test_parking_dressing.py"""
import copy
import json
import struct
import unittest
from types import SimpleNamespace
from author_campaign_arenas import OUT, neon
from dress_parking import AREA_TOLERANCE, PAINT, _targets, dress, validate_partition


class ParkingDressingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = json.loads((OUT/'arena-neon-zero.json').read_text(encoding='utf-8'))
        # Published maps may already have these bays. Remove only their material
        # tags in this fixture, keeping the exact current geometry, to exercise
        # real painting rather than merely retesting an idempotent no-op.
        for mesh in _targets(cls.source):
            materials = [mesh['material'] if tag == PAINT else tag for tag in mesh['triangleMaterials']]
            mesh['triangleMaterials'] = materials if len(set(materials)) > 1 else []
        cls.scene = SimpleNamespace(data=copy.deepcopy(cls.source), location=neon())
        cls.report = dress(cls.scene)

    def test_bays_have_real_dimensions_on_all_three_floors(self):
        for floor in (0, 8, 16):
            bays = [bay for bay in self.report['bays'] if bay['floor'] == floor]
            self.assertGreaterEqual(len(bays), 4)
            self.assertLessEqual(len(bays), 20, 'Limited side pockets, not a floor-wide parking grid')
            for bay in bays:
                self.assertEqual(12, max(p[0] for p in bay['footprint'])-min(p[0] for p in bay['footprint']))
                self.assertEqual(18, max(p[1] for p in bay['footprint'])-min(p[1] for p in bay['footprint']))

    def test_geometry_and_simulation_owners_remain_exactly_the_same(self):
        result = self.scene.data
        self.assertIn('parking-first-floor', self.report['changedMeshes'])
        self.assertIn('parking-roof-deck', self.report['changedMeshes'])
        self.assertTrue(any(identity.startswith('ground-') for identity in self.report['changedMeshes']))
        for key in self.source:
            if key != 'meshes':
                self.assertEqual(self.source[key], result[key], key)
        self.assertEqual([mesh['id'] for mesh in self.source['meshes']], [mesh['id'] for mesh in result['meshes']])
        road_ids = {identity for road in self.source['roads'] for identity in road['geometryIds']}
        self.assertTrue(set(self.report['changedMeshes']).isdisjoint(road_ids))
        originals = {mesh['id']: mesh for mesh in self.source['meshes']}
        for mesh in result['meshes']:
            original = originals[mesh['id']]
            if mesh['id'] not in self.report['changedMeshes']:
                self.assertEqual(original, mesh)
                continue
            self.assertIn(PAINT, mesh['triangleMaterials'])
            self.assertEqual(len(mesh['indices'])//3, len(mesh['triangleMaterials']))
            for field in original:
                if field not in ('vertices', 'indices', 'triangleMaterials'):
                    self.assertEqual(original[field], mesh[field], f'{mesh["id"]}: {field}')
            self.assertEqual({v['y'] for v in original['vertices']}, {v['y'] for v in mesh['vertices']})
            self.assertAlmostEqual(self.report['areaBefore'][mesh['id']], self.report['areaAfter'][mesh['id']], delta=AREA_TOLERANCE)

    def test_subdivision_is_stable_and_preserves_native_float_upward_faces(self):
        def f(value):
            return struct.unpack('f', struct.pack('f', value))[0]
        for mesh in self.scene.data['meshes']:
            if mesh['id'] not in self.report['changedMeshes']:
                continue
            for offset in range(0, len(mesh['indices']), 3):
                a, b, c = [mesh['vertices'][i] for i in mesh['indices'][offset:offset+3]]
                # Exactly the y component of jME Vector3f (b-a).cross(c-a).
                normal_y = f(f(f(b['z'])-f(a['z']))*f(f(c['x'])-f(a['x'])))-f(f(f(b['x'])-f(a['x']))*f(f(c['z'])-f(a['z'])))
                self.assertGreater(normal_y, 1e-5, f'{mesh["id"]}, triangle {offset//3}')
        rerun = SimpleNamespace(data=copy.deepcopy(self.source), location=neon())
        self.assertEqual(self.report, dress(rerun))
        self.assertEqual(self.scene.data, rerun.data)
        dressed = copy.deepcopy(rerun.data)
        dress(rerun)
        self.assertEqual(dressed, rerun.data, 'Repeated dressing must not add duplicate painted triangles')

    def test_partition_checker_rejects_duplicate_geometry_even_inside_one_mesh(self):
        mesh = dict(id='duplicated', vertices=[dict(x=0, y=8, z=0), dict(x=12, y=8, z=0), dict(x=0, y=8, z=18)],
                    indices=[0, 2, 1, 0, 2, 1], material='concrete', triangleMaterials=[])
        with self.assertRaisesRegex(ValueError, 'overlap'):
            validate_partition([mesh])
        mesh['indices'] = [0, 2, 1]
        other = copy.deepcopy(mesh)
        other['id'] = 'separate-coplanar-overlay'
        with self.assertRaisesRegex(ValueError, 'overlap'):
            validate_partition([mesh, other])


if __name__ == '__main__':
    unittest.main()
