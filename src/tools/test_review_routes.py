"""Inspection coverage follows actual roads and separates streets from interiors."""
import json
import unittest
from author_campaign_arenas import ROOT,construction,neon,carnival


class ReviewRoutesTest(unittest.TestCase):
    def test_all_districts_and_interiors_have_distinct_connected_inspections(self):
        routes=json.loads((ROOT/'src/tools/assets/architecture/review-routes.json').read_text(encoding='utf-8'))['routes']
        self.assertEqual(16,sum(route['kind']=='district' for route in routes))
        self.assertEqual(9,sum(route['kind']=='interior' for route in routes))
        def point(value):return tuple(round(value[key],4) for key in ('x','y','z'))
        for factory in (construction,neon,carnival):
            location=factory();local=[r for r in routes if r['arenaId']==location.data['id']]
            self.assertEqual({d[0] for d in location.district_specs},{r['id'] for r in local if r['kind']=='district'})
            edges={frozenset((tuple(round(x,4) for x in location.points[a]),tuple(round(x,4) for x in location.points[b])))
                   for path in location.paths for a,b in zip(path['names'],path['names'][1:])}
            signatures=set()
            for route in local:
                points=tuple(point(p) for p in route['points'])
                self.assertNotIn(points,signatures,route['id']+' duplicates another inspection')
                signatures.add(points)
                for a,b in zip(points,points[1:]):self.assertIn(frozenset((a,b)),edges,route['id']+' leaves the road network')


if __name__=='__main__':unittest.main()
