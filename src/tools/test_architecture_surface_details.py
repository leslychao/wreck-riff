"""Regressions for outward shell faces and connected, non-overlapping curb edges."""
import collections
import math
import unittest
from author_arena_models import Model,architectural


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



if __name__=='__main__':unittest.main()
