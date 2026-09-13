"""Regressions for outward shell faces and connected, non-overlapping curb edges."""
import collections
import math
import unittest
from author_arena_models import Model,architectural,metres_per_tile


class ArchitectureSurfaceDetailsTest(unittest.TestCase):
    def test_roof_outer_fascias_and_both_end_caps_face_outward(self):
        model=Model();model.roof(40,30,8,'concrete',8)
        observed=collections.Counter()
        for vertices in model.materials.values():
            for index in range(0,len(vertices),3):
                triangle=vertices[index:index+3]
                for axis,extent in ((0,20),(2,15)):
                    for sign in (-1,1):
                        if all(abs(p[axis]-extent*sign)<1e-6 for p,_,_ in triangle):
                            self.assertGreater(triangle[0][1][axis]*sign,.99)
                            observed[axis,sign]+=1
        self.assertEqual({(0,-1),(0,1),(2,-1),(2,1)},set(observed))

    def test_bandstand_rim_closes_upper_and_lower_roof_perimeters(self):
        for low,count in ((False,16),(True,8)):
            vertices=architectural('island-bandstand',low).materials['wood'];rim=[]
            for index in range(0,len(vertices),3):
                triangle=vertices[index:index+3]
                if all(abs(math.hypot(p[0],p[2])-30)<1e-5 for p,_,_ in triangle) and {round(p[1],1) for p,_,_ in triangle}=={9.4,10}:
                    rim.append(triangle)
                    centre=[sum(p[k] for p,_,_ in triangle)/3 for k in range(3)]
                    self.assertGreater(centre[0]*triangle[0][1][0]+centre[2]*triangle[0][1][2],0)
            self.assertEqual(count*2,len(rim))

    def test_lobed_foliage_is_closed_outward_and_retains_tree_footprint_in_both_lods(self):
        model=Model();model.crown((0,0,0),(3,4,5),'leaf')
        edges=collections.Counter()
        for i in range(0,len(model.materials['leaf']),3):
            triangle=model.materials['leaf'][i:i+3]
            for p,n,_ in triangle:self.assertGreater(sum(p[k]*n[k] for k in range(3)),0)
            points=[tuple(round(v,6) for v in p) for p,_,_ in triangle]
            for a,b in zip(points,points[1:]+points[:1]):edges[tuple(sorted((a,b)))]+=1
        self.assertEqual({2},set(edges.values()))
        counts=[]
        for low in (False,True):
            tree=architectural('park-tree',low);vertices=[p for group in tree.materials.values() for p,_,_ in group]
            self.assertLessEqual(max(math.hypot(p[0],p[2]) for p in vertices),6)
            self.assertAlmostEqual(24.5,max(p[1] for p in vertices))
            self.assertAlmostEqual(0,min(p[1] for p in vertices))
            counts.append(len(vertices))
        self.assertLess(counts[1],counts[0])

    def test_roof_supports_leave_real_road_envelopes_clear(self):
        from author_campaign_arenas import construction,neon
        for factory,name,origin in [(construction,'warehouse-trusses',(1235,15,247.5)),(neon,'passage-glass',(822.5,18,765)),(neon,'technical-rooftop',(1092.5,14,1005))]:
            location=factory();model=architectural(name)
            # Every low vertex belongs to actual support geometry, including
            # its whole footprint rather than only a nominal column centre.
            low_vertices={tuple(p[k]+origin[k] for k in range(3)) for group in model.materials.values() for p,_,_ in group if p[1]+origin[1]<8}
            self.assertTrue(low_vertices,name)
            for p in low_vertices:
                for path in location.paths:
                    for aa,bb in zip(path['names'],path['names'][1:]):
                        a,b=location.points[aa],location.points[bb]
                        if min(a[1],b[1])>8:continue
                        dx,dz=b[0]-a[0],b[2]-a[2];t=max(0,min(1,((p[0]-a[0])*dx+(p[2]-a[2])*dz)/(dx*dx+dz*dz)))
                        self.assertGreaterEqual(math.hypot(p[0]-a[0]-t*dx,p[2]-a[2]-t*dz),path['width']/2+4,(name,p,path['id']))

    def test_metric_uvs_match_existing_scene_material_capture_scale(self):
        for material,scale in [('cast-concrete',1.8),('park-paving',1.8),('steel',3),('wood',1.5),('brick',1),('concrete',4),('grass',2.51),('district-earth',2),('earth',1.3),('park-leaf',2),('road-surface',2),('road-wet',2)]:
            self.assertEqual(scale,metres_per_tile(material))
            model=Model();model.face([(0,0,0),(0,0,scale),(scale,0,scale)],material)
            self.assertEqual({(0,0),(0,1),(1,1)},{uv for _,_,uv in model.materials[material]})



if __name__=='__main__':unittest.main()
