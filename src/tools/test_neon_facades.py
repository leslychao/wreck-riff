"""Facade purposes, finite attached details, and the full expressway envelope."""
import copy
import math
import unittest
from author_arena_art import Scene
from author_campaign_arenas import neon, area, ccw, half
from dress_neon import _street_faces, _city_backdrop, _express_parapets


class NeonFacadesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene=Scene(neon());cls.scene.common();cls.scene.neon()
        cls.before=copy.deepcopy(cls.scene.data)
        _street_faces(cls.scene);_city_backdrop(cls.scene);_express_parapets(cls.scene)

    def parts(self, anchor):
        return [p for p in self.scene.parts if p['anchor']==anchor and p['id'].startswith('front-')]

    def test_named_buildings_have_their_actual_functions(self):
        cases={'courtyard-a-south':'residential-room','meridian-tower':'business-glazed-group',
               'technical-south':'service-vent-case','technical-west':'loading-closed-door',
               'transit-depot-wing-0':'loading-closed-door','school':'civic-reading-bay',
               'market-corner-0':'retail-display'}
        for anchor,feature in cases.items():
            self.assertTrue(any(feature in p['id'] for p in self.parts(anchor)),anchor+' lacks '+feature)
        for anchor in ('technical-west','technical-south','transit-depot-wing-0'):
            self.assertFalse(any(p['material']=='glass' for p in self.parts(anchor)),anchor+' has office glazing')

    def test_replaced_common_windows_and_unbroken_bands_are_absent(self):
        anchors={p['anchor'] for p in self.scene.parts if p['id'].startswith('front-')}
        for p in self.scene.parts:
            self.assertNotIn('window-band',p['id'])
            self.assertNotIn('city-side-glazing',p['id'])
            if p['anchor'] in anchors:
                self.assertFalse(p['id'].startswith(('architectural-window-','occupied-window-','street-address-')))
            if p['id'].startswith(('front-','boundary-','backdrop-')) and p['material'] in ('glass','light-amber'):
                self.assertLess(p['size']['x'],30,'A whole facade became one pane: '+p['id'])

    def test_facade_joinery_remains_attached_to_existing_envelopes(self):
        boxes={b['id']:b for b in self.before['boxes']}
        for p in self.scene.parts:
            if not p['id'].startswith('front-'):continue
            b=boxes[p['anchor']];c,s=b['center'],b['size'];pos=p['position'];size=p['size']
            self.assertGreater(min(size.values()),0,p['id'])
            self.assertTrue(all(math.isfinite(v) for v in (*pos.values(),*size.values())))
            angle=math.radians(b['yawDegrees']);dx,dz=pos['x']-c['x'],pos['z']-c['z']
            x,z=math.cos(angle)*dx-math.sin(angle)*dz,math.sin(angle)*dx+math.cos(angle)*dz
            face_angle=math.radians(p['rotation']['y']-b['yawDegrees'])
            ex=abs(math.cos(face_angle))*size['x']/2+abs(math.sin(face_angle))*size['z']/2
            ez=abs(math.sin(face_angle))*size['x']/2+abs(math.cos(face_angle))*size['z']/2
            self.assertLessEqual(abs(x)+ex,s['x']/2+.441,p['id'])
            self.assertLessEqual(abs(z)+ez,s['z']/2+.441,p['id'])
            self.assertGreaterEqual(pos['y']-size['y']/2,c['y']-s['y']/2-1e-6,p['id'])
            self.assertLessEqual(pos['y']+size['y']/2,c['y']+s['y']/2+1e-6,p['id'])

    def test_no_existing_geometry_navigation_or_resource_is_rewritten(self):
        for key,value in self.before.items():
            if key=='meshes':self.assertEqual(value,self.scene.data[key][:len(value)],key)
            else:self.assertEqual(value,self.scene.data[key],key)
        self.assertEqual(2,len(self.scene.data['meshes'])-len(self.before['meshes']))

    def test_parapets_are_closed_colliders_outside_every_full_driving_corridor(self):
        rails=self.scene.data['meshes'][len(self.before['meshes']):]
        surface_ids={s['geometryId'] for s in self.scene.data['surfaces']}
        for rail in rails:
            self.assertTrue(rail['collision']);self.assertEqual(2.55,rail['thickness'])
            self.assertNotIn(rail['id'],surface_ids,'Parapet must not become a navigable road')
            bottom=min(p['y'] for p in rail['vertices'])-rail['thickness']
            self.assertAlmostEqual(18.8,bottom)
            rail_poly=ccw([(p['x'],p['z']) for p in rail['vertices']])
            self.assertGreater(abs(area(rail_poly)),300,'Readable edge must span most of the long deck')
            for path in self.scene.location.paths:
                for first,last in zip(path['names'],path['names'][1:]):
                    a,b=self.scene.location.points[first],self.scene.location.points[last]
                    if max(a[1],b[1])+4<=bottom or min(a[1],b[1])>=bottom+rail['thickness']:continue
                    dx,dz=b[0]-a[0],b[2]-a[2];length=math.hypot(dx,dz)
                    nx,nz=dz/length*path['width']/2,-dx/length*path['width']/2
                    corridor=ccw([(a[0]+nx,a[2]+nz),(b[0]+nx,b[2]+nz),
                                  (b[0]-nx,b[2]-nz),(a[0]-nx,a[2]-nz)])
                    overlap=rail_poly
                    for aa,bb in zip(corridor,corridor[1:]+corridor[:1]):
                        overlap=half(overlap,aa,bb) if overlap else []
                    self.assertLess(abs(area(overlap)) if overlap else 0,.001,rail['id']+' clips '+path['id'])


if __name__=='__main__':unittest.main()
