"""Original Wreck Riff scenery authoring source; run explicitly, never during build/play.

All coordinates below are metres in the game's (X,Y,Z) convention. Inputs are the
existing authoritative collision definitions. Outputs are deterministic local scene
resources: no download, image synthesis, physics mutation or runtime layout service.
New solid decoration is exterior to the arena or inside an existing solid's XZ
footprint; road decals are flush with a named traversable surface. Dynamic gates and
barriers deliberately receive no static overlays. The original ArenaFactory yard
mesh generator remains intact, with its existing provenance in docs/asset-history.
"""
import json
import math
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/config"
SOURCE = "src/tools/author_arena_art.py; original Wreck Riff authored geometry, 2026-09-09"


def vec(values):
    return dict(zip(("x", "y", "z"), (round(float(v), 4) for v in values)))


class Scene:
    def __init__(self, resource):
        self.arena = json.loads((OUT / (resource + ".json")).read_text(encoding="utf-8-sig"))
        self.parts, self.groups = [], []
        self.boxes = {b["id"]: b for b in self.arena["boxes"]}
        self.bounds = self.arena["bounds"]
        self.surfaces = {s["geometryId"] for s in self.arena["surfaces"]}
        self.dynamic = {d["geometryId"] for d in self.arena["destructibles"] + self.arena["barriers"]}
        self.theme = self.arena["metadata"]["theme"]
        self.active_group = ""
        self.anchor = "exterior"

    def part(self, name, pos, size, material, shape="BOX", rotation=(0, 0, 0)):
        self.parts.append(dict(id=f"{name}-{len(self.parts):04d}", group=self.active_group,
                               anchor=self.anchor, shape=shape, material=material,
                               position=vec(pos), size=vec(size), rotation=vec(rotation)))

    def cylinder(self, name, pos, radius, height, material):
        self.part(name, pos, (radius * 2, radius * 2, height), material, "CYLINDER", (90, 0, 0))

    def beam(self, name, start, end, thickness, material):
        dx, dy, dz = (end[i] - start[i] for i in range(3))
        length = math.sqrt(dx * dx + dy * dy + dz * dz)
        # Cylinder local Z axis points to the endpoint (roll is immaterial).
        pitch = -math.degrees(math.asin(dy / length))
        yaw = math.degrees(math.atan2(dx, dz))
        self.part(name, tuple((start[i] + end[i]) / 2 for i in range(3)),
                  (thickness, thickness, length), material, "CYLINDER", (pitch, yaw, 0))

    def group(self, name, position, motion="STATIC", period=12, phase=0):
        self.groups.append(dict(id=name, position=vec(position), motion=motion, period=period, phase=phase))
        self.active_group = name

    def end_group(self):
        self.active_group = ""

    def steam(self, name, position, phase=0):
        self.group(name, position, "STEAM", 7.5, phase)
        for i in range(3):
            self.part("vent-steam", (.28 * i, i * .8, 0), (1.1 + i * .4, 1.6, 1.1 + i * .4), "steam", "SPHERE")
        self.end_group()

    def light(self, name, position, size, color="light-amber", pulse=False, phase=0):
        if pulse:
            self.group(name, position, "PULSE", 4.8, phase)
            self.part(name, (0, 0, 0), size, color)
            self.end_group()
        else:
            self.part(name, position, size, color)

    def facade(self, identity, tint, accent="light-amber", windows=True):
        b = self.boxes[identity]
        assert identity not in self.dynamic
        self.anchor = identity
        x, y, z = (b["center"][k] for k in ("x", "y", "z"))
        width, height, depth = (b["size"][k] for k in ("x", "y", "z"))
        # Insets only, with face overlays thinner than 4 cm, never new driving obstacles.
        for side in (-1, 1):
            face = z + side * (depth / 2 + .012)
            self.part("facade-plinth", (x, y - height * .41, face), (width - .1, height * .15, .025), tint)
            self.part("facade-cornice", (x, y + height * .44, face), (width - .1, .15, .025), "steel")
            columns = max(1, int(width / 4))
            for n in range(columns):
                px = x - width / 2 + (n + .5) * width / columns
                self.part("panel-joint", (px, y, face), (.05, height * .86, .03), "black")
                if windows and width / columns > 2:
                    self.part("inset-window", (px, y + height * .18, face + side * .008),
                              (min(2.3, width / columns - .5), min(1.25, height * .28), .03), "black")
                    self.light("window-light", (px, y + height * .18, face + side * .024),
                               (min(1.9, width / columns - .8), min(.75, height * .17), .025),
                               accent if n % 3 else "light-white")
            self.part("service-door", (x - width * .21, y - height * .13, face + side * .025),
                      (min(2.4, width / 4), height * .58, .028), tint)
        for side in (-1, 1):
            face = x + side * (width / 2 + .018)
            self.part("end-facade", (face, y, z), (.025, height * .78, depth * .8), tint)
            for row in range(3):
                self.part("end-louver", (face + side * .018, y + row * .23, z), (.024, .07, depth * .55), "black")
        self.anchor = "exterior"

    def backdrop_building(self, name, x, z, width, depth, height, tint, lit="light-amber"):
        self.anchor = "exterior"
        self.part(name, (x, height / 2, z), (width, height, depth), tint)
        self.part("setback-roof", (x + width * .12, height + 1.6, z), (width * .60, 3.2, depth * .7), "black")
        for side in (-1, 1):
            face = z + side * (depth / 2 + .04)
            for floor in range(max(1, int(height / 6))):
                for column in range(max(2, int(width / 5))):
                    xx = x - width / 2 + 2.3 + column * 5
                    self.light("distant-window", (xx, 3 + floor * 6, face), (1.6, 1.3, .03),
                               lit if (floor + column) % 4 else "black")
            self.part("roof-lip", (x, height + .1, face), (width + .2, .3, .3), "steel")

    def common(self):
        xmin, xmax, zmin, zmax = (self.bounds[k] for k in ("minX", "maxX", "minZ", "maxZ"))
        # A ring outside bounds leaves construction pit and final-gallery openings untouched.
        for pos, size in [((xmin-30, -.28, (zmin+zmax)/2), (60, .5, zmax-zmin+120)),
                          ((xmax+30, -.28, (zmin+zmax)/2), (60, .5, zmax-zmin+120)),
                          (((xmin+xmax)/2, -.28, zmin-30), (xmax-xmin, .5, 60)),
                          (((xmin+xmax)/2, -.28, zmax+30), (xmax-xmin, .5, 60))]:
            self.part("exterior-ground", pos, size, "dark-concrete")
        # Concrete, gates and panels break the old repeating rust boundary into believable bays.
        boundary = [b for b in self.boxes.values() if b["id"].startswith("boundary-") or b["id"].endswith("fence")]
        for b in boundary:
            self.anchor = b["id"]
            x, y, z = (b["center"][k] for k in ("x", "y", "z"))
            w, h, d = (b["size"][k] for k in ("x", "y", "z"))
            along_x = w > d
            extent = w if along_x else d
            count = int(extent / 8)
            for n in range(count):
                offset = -extent / 2 + (n + .5) * extent / count
                tint = ["dark-concrete", "stone" if self.theme == "NECROPOLIS" else "blue", "concrete"][n % 3]
                pos = (x + offset, y, z) if along_x else (x, y, z + offset)
                size = (extent/count-.15, h-.15, d+.03) if along_x else (w+.03, h-.15, extent/count-.15)
                self.part("boundary-bay", pos, size, tint)
        self.anchor = "exterior"
        # Deterministic road wear. Every decal is attached to a real flat drivable surface.
        rng = random.Random(self.arena["id"])
        for surface in self.arena["surfaces"]:
            b = self.boxes.get(surface["geometryId"])
            if not b:
                continue
            x, y, z = (b["center"][k] for k in ("x", "y", "z"))
            w, h, d = (b["size"][k] for k in ("x", "y", "z"))
            if min(w, d) < 12:
                continue
            self.anchor = b["id"]
            for n in range(min(40, max(5, int(w*d/500)))):
                px, pz = x + rng.uniform(-w/2+4, w/2-4), z + rng.uniform(-d/2+4, d/2-4)
                top = y + h/2
                # Lower floor beneath a deck must not receive a decal up on an unrelated plane.
                self.part("asphalt-repair", (px, top+.012, pz), (rng.uniform(1.5,4), .008, rng.uniform(2,5)),
                          "road-wet" if self.theme == "NEON" and n % 2 else "road-patch")
                if n % 3 == 0:
                    for sign in (-1, 1):
                        self.part("tire-scar", (px+sign*.85, top+.02, pz), (.085, .004, 4.0), "black")
            if surface["level"] == 1:
                # Thin edge dashes preserve the exact open landing and ramp topology.
                for n in range(int(w/7)):
                    self.part("upper-route-dash", (x-w/2+2+n*7, y+h/2+.025, z-d/2+2.3),
                              (2.7, .01, .18), "ivory")
        self.anchor = "exterior"

    def save(self):
        data = dict(schemaVersion=1, arenaId=self.arena["id"], source=SOURCE,
                    license="Original project-authored geometry; uses existing locally licensed surface materials.",
                    groups=self.groups, parts=self.parts)
        target = OUT / ("arena-art-" + self.arena["id"].replace("_", "-") + ".json")
        # One record per line keeps the authored scene diff reviewable despite many instanced parts.
        rows = [json.dumps(p, ensure_ascii=False, separators=(",", ":")) for p in self.parts]
        head = json.dumps({k: v for k, v in data.items() if k != "parts"}, ensure_ascii=False, indent=2)
        target.write_text(head[:-2] + ',\n  "parts": [\n    ' + ',\n    '.join(rows) + '\n  ]\n}\n', encoding="utf-8")
        print(f"{target.name}: {len(self.parts)} authored parts, {len(self.groups)} motion groups")


def yard():
    s = Scene("arena");s.common()
    # Retains the original lattice mast and garage meshes, enriching their authored faces.
    for identity in ("garage-south-wall", "garage-north-wall"):
        s.facade(identity, "dark-concrete", "light-amber")
    s.anchor = "mast-base"
    for y in (26, 38, 44):
        s.light("mast-beacon-"+str(y), (0, y, 0), (.4,.5,.4), "light-amber", True, y*.11)
    s.anchor = "garage-roof"
    for i, x in enumerate((-56, -48, -40, -32)):
        s.steam("garage-vent-"+str(i), (x, 7.4, 22), i*.21)
    s.anchor = "exterior"
    for x, z in ((-101,-35),(106,36)):
        s.backdrop_building("service-works",x,z,22,28,13,"dark-concrete")
    s.save()


def construction():
    s = Scene("arena-construction-17");s.common()
    for identity in ("container-island-south","container-island-north"):
        s.facade(identity,"yellow","light-white",False)
    for identity in ("upper-island-west","upper-island-east","parking-pier-west","parking-pier-east"):
        s.facade(identity,"dark-concrete","light-white",False)
    # Tower crane is wholly outside the northern boundary, visible from both combat levels.
    x,z=139,254
    for level in range(10):
        lo, hi = level*5, (level+1)*5
        for dx in (-2.8,2.8):
            for dz in (-2.8,2.8):
                s.beam("crane-lattice-leg",(x+dx,lo,z+dz),(x+dx,hi,z+dz),.38,"yellow")
            s.beam("crane-cross-brace",(x+dx,lo,z-2.8),(x+dx,hi,z+2.8),.17,"steel")
        s.beam("crane-level",(x-2.8,hi,z-2.8),(x+2.8,hi,z-2.8),.2,"yellow")
    for n in range(13):
        xx=x-22+n*6
        for zz in (z-2,z+2):
            s.beam("crane-jib-upper",(xx,53,zz),(xx+6,53,zz),.25,"yellow")
            s.beam("crane-jib-lower",(xx,49,zz),(xx+6,49,zz),.25,"yellow")
            s.beam("crane-jib-diagonal",(xx,49,zz),(xx+6,53,zz),.18,"steel")
    s.part("crane-counterweight",(x-20,48,z),(10,4,5),"dark-concrete")
    s.part("crane-cab",(x+7,49,z),(5,4,4),"yellow")
    s.light("crane-cab-glass",(x+7,49,z-2.03),(4,2,.03),"light-white")
    s.beam("crane-hoist",(x+42,49,z),(x+42,17,z),.1,"black")
    s.part("suspended-concrete",(x+42,15,z),(8,3.5,5),"concrete")
    s.light("crane-beacon",(x,55,z),(.7,.5,.7),"light-amber",True)
    for x,z,h in ((-23,70,29),(264,66,36),(51,250,24)):
        # Open concrete frames, without enormous generic slabs enclosing the scene.
        for yy in range(0,h,7):
            for xx in (-10,0,10):
                for zz in (-9,9):
                    s.part("building-frame-column",(x+xx,yy+3.5,z+zz),(.85,7,.85),"concrete")
            s.part("unfinished-slab",(x,yy+.2,z),(24,.5,22),"concrete")
        s.light("site-floodlight",(x,h,z-10),(2.6,1.2,.2),"light-white")
    for i,(x,z) in enumerate(((-8,95),(248,155),(190,232))):
        s.beam("floodlight-mast",(x,0,z),(x,15,z),.25,"steel")
        s.light("floodlight",(x,15,z),(4,.9,.6),"light-white")
        s.steam("site-dust-"+str(i),(x,1,z),i*.27)
    s.save()


def neon():
    s=Scene("arena-neon-zero");s.common()
    for identity,accent in (("shop-west","light-cyan"),("shop-east","light-magenta"),
                            ("upper-service-block","light-cyan"),("lower-west-island","light-magenta")):
        s.facade(identity,"black",accent)
        b=s.boxes[identity];x,y,z=(b["center"][k] for k in ("x","y","z"));w,h,d=(b["size"][k] for k in ("x","y","z"))
        s.anchor=identity
        s.light(identity+"-neon-header",(x,y+h*.38,z-d/2-.04),(w*.88,.18,.045),accent,True,len(s.groups)*.13)
        s.anchor="exterior"
    for i,(x,z,w,d,h) in enumerate(((-24,47,30,35,48),(-28,154,34,40,69),(310,55,40,36,53),
                                    (315,177,42,45,81),(57,270,38,35,58),(162,279,50,52,77),(256,281,42,44,45))):
        accent="light-cyan" if i%2 else "light-magenta"
        s.backdrop_building("neon-tower",x,z,w,d,h,"dark-concrete",accent)
        s.light("vertical-neon",(x-w*.32,h*.55,z-d/2-.08),(.6,h*.6,.1),accent,True,i*.17)
        s.part("billboard-case",(x,h*.72,z-d/2-.2),(w*.65,6,.25),"black")
        s.group("neon-advert-"+str(i),(x,h*.72,z-d/2-.37),"SCREEN",8,i*.2)
        for stripe in range(4):
            s.part("ad-bars",(-w*.2+stripe*w*.13,0,0),(.5,4-stripe*.45,.08),accent)
        s.end_group()
    for i,identity in enumerate(("shop-west","shop-east","upper-service-block")):
        b=s.boxes[identity];s.anchor=identity
        x,y,z=(b["center"][k] for k in ("x","y","z"));top=y+b["size"]["y"]/2
        s.cylinder("roof-exhaust",(x,top+.55,z),.7,1.1,"steel")
        s.steam("city-exhaust-"+str(i),(x,top+1.5,z),i*.31)
    s.save()


def carnival():
    s=Scene("arena-euphoria-park");s.common()
    for identity,tint in (("southern-stage","faded-red"),("west-pavilion-core","faded-red"),
                          ("east-pavilion-core","purple"),("stage-figure","yellow")):
        s.facade(identity,tint,"light-amber",False)
    s.anchor="stage-figure"
    # A theatrical mask is flush inside the existing figure, with asymmetrical lit eyes.
    s.part("stage-mask-face",(76,12,168.97),(13,6,.03),"ivory")
    for dx in (-3.2,3.2):
        s.part("mask-eye",(76+dx,13,168.94),(2.4,1.1,.025),"black",rotation=(0,0,dx*3))
        s.light("mask-eye-light",(76+dx,13,168.91),(1.2,.4,.025),"light-amber")
    s.part("mask-grin",(76,10.6,168.94),(6,.6,.025),"faded-red")
    s.anchor="exterior"
    cx,cy,cz=125,37,273
    for dx in (-19,19):
        s.beam("ferris-support",(cx+dx,0,cz-7),(cx,cy,cz),1.0,"steel")
        s.beam("ferris-support",(cx+dx,0,cz+7),(cx,cy,cz),1.0,"steel")
    s.group("euphoria-ferris-wheel",(cx,cy,cz),"ROTATE_Z",100)
    for segment in range(24):
        a,b=segment*math.tau/24,(segment+1)*math.tau/24
        p=(math.cos(a)*29,math.sin(a)*29,0);q=(math.cos(b)*29,math.sin(b)*29,0)
        s.beam("ferris-rim",p,q,.55,"faded-red")
        if segment%2==0:
            s.beam("ferris-spoke",(0,0,0),p,.25,"ivory")
            s.part("ferris-gondola",p,(3,3.5,3),"purple" if segment%4 else "faded-red")
        s.part("ferris-bulb",(p[0],p[1],-.7),(.55,.55,.5),"light-amber","SPHERE")
    s.end_group()
    for i,(x,z,tint) in enumerate(((-22,55,"faded-red"),(282,60,"purple"),(-23,174,"purple"),(284,182,"faded-red"))):
        s.backdrop_building("carnival-pavilion",x,z,26,24,11,tint)
        for n in range(7):
            s.part("pavilion-striped-awning",(x-11+n*3.5,10,z-12.3),(2.6,1.0,.4),"ivory" if n%2 else tint)
            s.light("pavilion-marquee-"+str(i)+"-"+str(n),(x-11+n*3.5,11.3,z-12.5),(.45,.45,.2),"light-amber",True,n*.09)
    s.save()


def necropolis():
    s=Scene("arena-ash-necropolis");s.common()
    for identity in ("mausoleum-base","bell-structure","graves-west","graves-east","graves-east-north"):
        s.facade(identity,"stone","light-amber",False)
    s.anchor="bell-structure"
    # Four pilasters, cornice and black recess define the existing solid mausoleum silhouette.
    for xx in (119.5,125.5,134.5,140.5):
        s.part("mausoleum-pilaster",(xx,12,153.97),(.65,7.8,.04),"dark-concrete")
    s.part("mausoleum-dark-door",(130,11.2,153.94),(4.8,6.2,.035),"black")
    s.light("ritual-portal",(130,14.7,153.91),(5.6,.25,.025),"light-amber")
    s.anchor="exterior"
    x,z=130,272
    for dx in (-10,10):
        s.part("bell-tower-pier",(x+dx,21,z),(4,42,5),"stone")
        s.part("bell-tower-cap",(x+dx,42,z),(5.5,1.2,6.5),"dark-concrete")
    s.beam("bell-overhead-frame",(x-12,39,z),(x+12,39,z),1.6,"black")
    s.group("necro-bell",(x,36,z),"SWAY_Z",7)
    s.cylinder("ritual-bell",(0,-4,0),4.8,6,"steel")
    s.cylinder("bell-lip",(0,-7,0),5.3,.5,"black")
    s.beam("bell-clapper",(0,-2,0),(0,-8,0),.6,"rust")
    s.end_group()
    for i,(x,z) in enumerate(((-18,58),(279,56),(-19,177),(278,173),(63,265),(201,265))):
        s.backdrop_building("tomb-hall",x,z,18,23,12+(i%3)*4,"stone","light-amber")
        s.part("tomb-obelisk",(x,27,z),(2.5,16,2.5),"dark-concrete",rotation=(0,45,0))
        s.cylinder("ritual-brazier",(x,7,z-8),1.7,1.0,"black")
        s.light("ritual-fire-"+str(i),(x,8,z-8),(1.7,.8,1.7),"light-amber",True,i*.23)
        s.steam("ritual-smoke-"+str(i),(x,8.8,z-8),i*.18)
    s.save()


def show():
    s=Scene("arena-doomsday");s.common()
    for identity,accent in (("lower-island-west","light-cyan"),("lower-island-east","light-magenta")):
        s.facade(identity,"black",accent)
    colors=("light-amber","light-cyan","light-magenta","light-white")
    # Seating is outside all four arena walls. Broad tiers and individual silhouettes read as an audience.
    for side in range(4):
        for tier in range(5):
            depth=11+tier*5;yy=3+tier*3
            if side in (0,1):
                xx=-depth if side==0 else 240+depth
                s.part("stadium-tribune",(xx,yy/2,120),(5,yy,212),"dark-concrete")
                for j in range(18):
                    s.part("audience-silhouette",(xx,yy+.65,21+j*11),(1.2,1.3,1.2),"black","SPHERE")
            else:
                zz=-depth if side==2 else 240+depth
                s.part("stadium-tribune",(120,yy/2,zz),(212,yy,5),"dark-concrete")
                for j in range(18):
                    s.part("audience-silhouette",(21+j*11,yy+.65,zz),(1.2,1.3,1.2),"black","SPHERE")
        s.light("sector-ribbon-"+str(side),((-3 if side==0 else 243),3.4,120) if side<2 else (120,3.4,-3 if side==2 else 243),
                (.3,.45,220) if side<2 else (220,.45,.3),colors[side],True,side*.22)
    # Main northern screen is the central skyline landmark; nothing hangs into launch trajectories.
    for x in (79,161):
        s.beam("screen-support",(x,0,268),(x,48,268),1.5,"steel")
    s.part("director-screen-frame",(120,34,266),(86,30,2),"steel")
    s.part("director-screen-black",(120,34,264.95),(82,26,.08),"black")
    s.group("director-screen",(120,34,264.86),"SCREEN",9)
    for side in (-1,1):
        s.part("director-eye",(side*17,3,0),(14,3,.06),"light-cyan")
    for i in range(9):
        s.part("director-waveform",(-28+i*7,-6,0),(3,2+(i%4)*1.5,.06),"light-magenta")
    s.end_group()
    for i,(x,z) in enumerate(((-14,-14),(254,-14),(-14,254),(254,254))):
        s.beam("stadium-light-tower",(x,0,z),(x,37,z),.8,"steel")
        for lamp in range(5):
            s.light("stadium-floodlight",(x-4+lamp*2,37,z),(1.4,1.6,.8),"light-white")
    s.save()


if __name__ == "__main__":
    for author in (yard, construction, neon, carnival, necropolis, show):
        author()
