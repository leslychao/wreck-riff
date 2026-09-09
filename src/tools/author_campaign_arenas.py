"""Original arena authoring source. Run explicitly; never invoked by the build/runtime.

Source contract: docs/ARENAS_BOSSES_IMPLEMENTATION_SPEC_RU.md, sections 4-17.
Coordinates in the authoring tables are (X, Z, Y), as in that document.
The output is deterministic local JSON and contains no downloaded resources.
"""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src" / "main" / "resources" / "config"


def vec(p):
    x, z, *height = p
    return dict(x=x, y=height[0] if height else 0, z=z)


class Arena:
    def __init__(self, identity, resource, title, theme, width, depth, spawns, pickups, boss):
        self.resource = resource
        self.data = dict(schemaVersion=2, id=identity,
                         metadata=dict(title=title, theme=theme, normalEnemies=len(spawns)-1,
                                       durationSeconds=0, recoveryCost=0,
                                       introduction="Два этажа. Два подброса. Одна победа.",
                                       music=f"audio/campaign/{identity}-normal.ogg",
                                       bossMusic=f"audio/campaign/{identity}-boss.ogg"),
                         bounds=dict(minX=0, maxX=width, minZ=0, maxZ=depth, recoveryY=-12),
                         boxes=[], ramps=[], spawns=[], pickups=[], hazards=[], nodes=[], edges=[],
                         surfaces=[], launchPads=[], drops=[], destructibles=[], secrets=[], bosses=[], barriers=[])
        self.width, self.depth = width, depth
        self.node_ids = {}
        self.edge_pairs = set()
        for i, p in enumerate(spawns):
            # All starts face an open road toward the interior, not a perimeter wall.
            yaw = math.degrees(math.atan2(width/2-p[0], depth/2-p[1]))
            self.data["spawns"].append(dict(id=i, position=vec(p), yawDegrees=round(yaw, 3)))
        self.data["spawns"][0]["yawDegrees"] = 45
        types = ["HOMING_AMMO", "POWER_AMMO", "MINE_AMMO", "NAPALM_AMMO", "BALLISTIC_AMMO", "CANNON_AMMO", "HOMING_AMMO", "BALLISTIC_AMMO", "REPAIR", "REPAIR", "TURBO_CELL", "POWER_AMMO"]
        names = [f"W{i}" for i in range(1, 9)] + ["R1", "R2", "M1", "S1"]
        seconds = [25]*7+[45, 40, 40, 30, 60]
        for name, kind, p, respawn in zip(names, types, pickups, seconds):
            self.data["pickups"].append(dict(id=name, type=kind, position=vec(p), respawnTicks=respawn*120))
        boss_id, name, hp, weapons, entrances, quotes = boss
        self.data["bosses"].append(dict(id=boss_id, name=name, profileId=boss_id, maximumHp=hp,
                                        primary=weapons[0], secondary=weapons[1],
                                        entrances=[dict(id=100+i, position=vec(p), yawDegrees=-90 if p[0]>width/2 else 90) for i, p in enumerate(entrances)],
                                        launchPadIds=["launch-a", "launch-b"] if boss_id=="boss_emcee" else [], quotes=quotes))
        # Perimeter colliders are outside the stated useful area.
        self.box("boundary-west", (-.4, depth/2, 1.5), (.8, depth, 3), "rust")
        self.box("boundary-east", (width+.4, depth/2, 1.5), (.8, depth, 3), "rust")
        self.box("boundary-south", (width/2, -.4, 1.5), (width, .8, 3), "rust")
        self.box("boundary-north", (width/2, depth+.4, 1.5), (width, .8, 3), "rust")

    def box(self, name, center, size, material="concrete", collision=True):
        self.data["boxes"].append(dict(id=name, center=vec(center), size=vec(size), material=material, collision=collision))

    def slab(self, name, rect, y=0, material="asphalt", level=0, grip=1):
        x0, x1, z0, z1 = rect
        self.box(name, ((x0+x1)/2, (z0+z1)/2, y-.3), (x1-x0, z1-z0, .6), material)
        self.data["surfaces"].append(dict(id=name, geometryId=name, level=level, grip=grip))

    def cover(self, name, rect, y=0, height=4, material="concrete"):
        x0, x1, z0, z1 = rect
        self.box(name, ((x0+x1)/2, (z0+z1)/2, y+height/2), (x1-x0, z1-z0, height), material)

    def ramp(self, name, rect, start, end, axis="Z", bottom=-.6, level=0):
        x0, x1, z0, z1 = rect
        self.data["ramps"].append(dict(id=name, minX=x0, maxX=x1, minZ=z0, maxZ=z1,
                                       startY=start, endY=end, bottomY=bottom, axis=axis, material="concrete"))
        self.data["surfaces"].append(dict(id=name, geometryId=name, level=level, grip=1))

    def rail(self, name, a, b, y=8):
        self.box(name, ((a[0]+b[0])/2, (a[1]+b[1])/2, y+.6),
                 (max(.6, abs(a[0]-b[0])), max(.6, abs(a[1]-b[1])), 1.2), "steel")

    def rectangle_rails(self, name, rect, gaps=None):
        x0, x1, z0, z1 = rect
        gaps = gaps or {}
        for side, lo, hi, constant in [("west", z0, z1, x0-.3), ("east", z0, z1, x1+.3),
                                        ("south", x0, x1, z0-.3), ("north", x0, x1, z1+.3)]:
            cursor = lo
            for i, (start, end) in enumerate(sorted(gaps.get(side, []))+[(hi, hi)]):
                if start > cursor:
                    a, b = ((constant, cursor), (constant, start)) if side in ("west", "east") else ((cursor, constant), (start, constant))
                    self.rail(f"{name}-{side}-{i}", a, b)
                cursor = max(cursor, end)

    def hazard(self, name, kind, center, size, sector, warning=1.5, active=2.5, interval=1, damage=32, rest=14):
        p, s = vec(center), vec(size)
        self.data["hazards"].append(dict(id=name, type=kind, sector=sector,
                                         minX=p["x"]-s["x"]/2, maxX=p["x"]+s["x"]/2,
                                         minZ=p["z"]-s["z"]/2, maxZ=p["z"]+s["z"]/2,
                                         minY=p["y"]-.1, maxY=p["y"]+s["y"],
                                         offTicks=int(rest*120), warningTicks=int(warning*120),
                                         activeTicks=int(active*120), damageIntervalTicks=int(interval*120), damage=damage))

    def gate(self, name, p, axis="X", width=16, effect="OPEN_ROUTE", delay=0, block=0):
        size = (1, width, 5) if axis=="X" else (width, 1, 5)
        self.box(name+"-panel", (*p[:2], 2.5), size, "yellow")
        self.data["destructibles"].append(dict(id=name, geometryId=name+"-panel", maximumHp=25,
                                              effect=effect, delayTicks=delay, blockTicks=block))

    def secret(self, entry, exit):
        # Two actual 12m vehicle doors on different roads; the entrance is gated.
        self.gate("secret-gate", entry, "Z", width=12)
        x0,x1=min(entry[0],exit[0])-8,max(entry[0],exit[0])+8
        z0,z1=entry[1],exit[1]
        for side,z,opening in [("front",z0,entry[0]),("back",z1,exit[0])]:
            for suffix,left,right in [("left",x0,opening-6),("right",opening+6,x1)]:
                if right>left:self.cover(f"secret-{side}-{suffix}",(left,right,z-.4,z+.4),height=6,material="rust")
        self.cover("secret-west-wall", (x0-.4, x0+.4, z0, z1), height=6, material="rust")
        self.cover("secret-east-wall", (x1-.4, x1+.4, z0, z1), height=6, material="rust")
        self.box("secret-canopy", ((x0+x1)/2, (z0+z1)/2, 7.1), (x1-x0, z1-z0, .6), "rust")
        self.data["secrets"].append(dict(id="secret", gateId="secret-gate", entry=vec(entry), exit=vec(exit), pickupId="S1"))

    def pads(self, a, b, drop, ramp):
        for name, (source, target) in zip(("launch-a", "launch-b"), (a, b)):
            self.data["launchPads"].append(dict(id=name, source=vec(source), target=vec(target),
                                               sourceSurfaceId=self.surface(vec(source)), landingSurfaceId=self.surface(vec(target)),
                                               length=12, width=10, maximumEntryAngle=55, minimumSpeed=2,
                                               compressionTicks=12, flightSeconds=2.1, rearmTicks=120))
        self.data["drops"].append(dict(id="drop", start=vec(drop[0]), end=vec(drop[1]), width=24))
        x, z, y = ramp[0]
        _, endz, endy = ramp[1]
        self.ramp("upper-ramp", (x-12, x+12, z, endz), y, endy)

    def surface(self, p):
        for surface in reversed(self.data["surfaces"]):
            for ramp in self.data["ramps"]:
                if ramp["id"]!=surface["geometryId"]: continue
                if ramp["minX"]-.01<=p["x"]<=ramp["maxX"]+.01 and ramp["minZ"]-.01<=p["z"]<=ramp["maxZ"]+.01:
                    key="x" if ramp["axis"]=="X" else "z"
                    low=ramp["minX"] if key=="x" else ramp["minZ"]
                    high=ramp["maxX"] if key=="x" else ramp["maxZ"]
                    height=ramp["startY"]+(ramp["endY"]-ramp["startY"])*(p[key]-low)/(high-low)
                    if abs(p["y"]-height)<.05: return surface["id"]
            for box in self.data["boxes"]:
                if box["id"]!=surface["geometryId"]: continue
                c, s = box["center"], box["size"]
                if abs(p["y"]-c["y"]-s["y"]/2)<.05 and abs(p["x"]-c["x"])<=s["x"]/2+.01 and abs(p["z"]-c["z"])<=s["z"]/2+.01:
                    return surface["id"]
        raise ValueError(f'{self.data["id"]}: no road under {p}')

    def node(self, name, p):
        if name in self.node_ids: return self.node_ids[name]
        identity=len(self.node_ids)
        self.node_ids[name]=identity
        self.data["nodes"].append(dict(id=identity, position=vec(p), surfaceId=self.surface(vec(p))))
        return identity

    def edge(self, a, b, width=24, kind="ROAD", obj="", bidirectional=True, clearance=7.35):
        i, j=self.node_ids[a], self.node_ids[b]
        key=(min(i,j), max(i,j), kind)
        if key in self.edge_pairs: return
        self.edge_pairs.add(key)
        self.data["edges"].append(dict(id=f"{kind.lower()}-{a}-{b}", from_=i, to=j, width=width,
                                      clearance=clearance, type=kind, objectId=obj, bidirectional=bidirectional))
        self.data["edges"][-1]["from"]=self.data["edges"][-1].pop("from_")

    def chain(self, names, close=False, width=24):
        for a,b in zip(names, names[1:]+(names[:1] if close else [])): self.edge(a,b,width)

    def road_clear(self, a, b, width=3, ignore=()):
        distance=math.hypot(b["x"]-a["x"], b["z"]-a["z"])
        for i in range(max(2,int(distance))+1):
            t=i/max(2,int(distance))
            p={key:a[key]+(b[key]-a[key])*t for key in ("x","y","z")}
            try: self.surface(p)
            except ValueError: return False
            for box in self.data["boxes"]:
                if not box["collision"] or box["id"] in ignore: continue
                c,s=box["center"],box["size"]
                if c["y"]+s["y"]/2<=p["y"]+.1 or c["y"]-s["y"]/2>=p["y"]+6.7: continue
                if abs(p["x"]-c["x"])<s["x"]/2+width/2-1e-6 and abs(p["z"]-c["z"])<s["z"]/2+width/2-1e-6: return False
            for ramp in self.data["ramps"]:
                if ramp["id"] in ignore: continue
                if ramp["minX"]-width/2<p["x"]<ramp["maxX"]+width/2 and ramp["minZ"]-width/2<p["z"]<ramp["maxZ"]+width/2:
                    key="x" if ramp["axis"]=="X" else "z"
                    low,high=(ramp["minX"],ramp["maxX"]) if key=="x" else (ramp["minZ"],ramp["maxZ"])
                    t=max(0,min(1,(p[key]-low)/(high-low)))
                    height=ramp["startY"]+(ramp["endY"]-ramp["startY"])*t
                    if height>p["y"]+.05: return False
        return True

    def attach(self, name, p, allowed=None, width=16, ignore=()):
        self.node(name,p)
        own=self.data["nodes"][self.node_ids[name]]
        options=[]
        for target in self.data["nodes"]:
            if target["id"]==own["id"] or abs(target["position"]["y"]-own["position"]["y"])>.1: continue
            label=next(key for key,value in self.node_ids.items() if value==target["id"])
            if allowed is not None and label not in allowed: continue
            if self.road_clear(own["position"],target["position"],width,ignore):
                options.append((math.dist(list(own["position"].values()),list(target["position"].values())),label))
        if not options: raise ValueError(f'{self.data["id"]}: no clear authored connector for {name} {p}')
        for _,label in sorted(options)[:2]: self.edge(name,label,width)

    def network(self, ground, upper, upper_links):
        # Only these hand-authored route chains create through roads. Pickup/spawn
        # leaves attach to a checked nearby road; no all-pairs visibility graph.
        for name,p in ground: self.node(name,p)
        self.chain([name for name,_ in ground],close=True)
        for name,p in upper: self.node(name,p)
        for route in upper_links: self.chain(route)
        for pad in self.data["launchPads"]:
            s,t=pad["source"],pad["target"]
            sign=1 if t["x"]>s["x"] else -1
            approach=(min(self.width-5,max(5,s["x"]-sign*12)),s["z"],s["y"])
            self.attach(pad["id"]+"-entry",approach,width=8)
            self.node(pad["id"]+"-source",(s["x"],s["z"],s["y"]))
            self.edge(pad["id"]+"-entry",pad["id"]+"-source",8)
            self.attach(pad["id"]+"-landing",(t["x"],t["z"],t["y"]),width=16)
            self.edge(pad["id"]+"-source",pad["id"]+"-landing",10,"LAUNCH",pad["id"],False,30)
        ramp=next(r for r in self.data["ramps"] if r["id"]=="upper-ramp")
        x=(ramp["minX"]+ramp["maxX"])/2
        self.attach("ramp-approach",(x,ramp["minZ"]-10,0),width=20)
        names=[]
        for i in range(5):
            name=f"ramp-{i}";names.append(name)
            self.node(name,(x,ramp["minZ"]+(ramp["maxZ"]-ramp["minZ"])*i/4,2*i))
        self.edge("ramp-approach",names[0],24,"RAMP","upper-ramp")
        for a,b in zip(names,names[1:]):self.edge(a,b,24,"RAMP","upper-ramp")
        self.attach("ramp-exit",(x,ramp["maxZ"]+12,8),width=20)
        self.edge(names[-1],"ramp-exit",24,"RAMP","upper-ramp")
        drop=self.data["drops"][0];s,t=drop["start"],drop["end"]
        self.attach("drop-upper",(s["x"],s["z"],s["y"]),width=16)
        self.attach("drop-lower",(t["x"],t["z"],t["y"]),width=16)
        self.edge("drop-upper","drop-lower",drop["width"],"DROP","drop",False,30)
        secret=self.data["secrets"][0];entry,exit=secret["entry"],secret["exit"]
        self.attach("secret-entry-road",(entry["x"],entry["z"]-8,0),width=6)
        self.attach("secret-exit-road",(exit["x"],exit["z"]+8,0),width=6)
        self.node("secret-entry-inside",(entry["x"],entry["z"]+8,0))
        self.node("secret-exit-inside",(exit["x"],exit["z"]-8,0))
        self.edge("secret-entry-road","secret-entry-inside",12,"OPENABLE","secret-gate",clearance=6.7)
        self.edge("secret-entry-inside","secret-exit-inside",12,clearance=6.7)
        self.edge("secret-exit-inside","secret-exit-road",12,clearance=6.7)
        for pickup in self.data["pickups"]:
            p=pickup["position"]
            if p["y"]<0: continue
            # Exact sockets near boundaries intentionally have a smaller local approach
            # than the main 24m roads, still wider than every ordinary car.
            self.attach("pickup-"+pickup["id"],(p["x"],p["z"],p["y"]),width=6)
        for spawn in self.data["spawns"]:
            p=spawn["position"];self.attach("spawn-"+str(spawn["id"]),(p["x"],p["z"],p["y"]),width=6)

    def opening(self, name, p, axis="X", width=16):
        x,z=p
        before=(x-14,z,0) if axis=="X" else (x,z-14,0)
        after=(x+14,z,0) if axis=="X" else (x,z+14,0)
        self.attach(name+"-before",before,width=8)
        self.attach(name+"-after",after,width=8)
        self.edge(name+"-before",name+"-after",width,"OPENABLE",name)

    def finish(self):
        # Exact coordinates must resolve to genuine surfaces, including the pit.
        for item in self.data["spawns"]+self.data["pickups"]:
            self.surface(item["position"])
        for edge in self.data["edges"]:
            if edge["type"]=="ROAD" and not self.road_clear(self.data["nodes"][edge["from"]]["position"],
                                                            self.data["nodes"][edge["to"]]["position"],edge["width"]):
                raise ValueError(f'{self.data["id"]}: blocked authored road {edge["id"]}')
        ids={n["id"] for n in self.data["nodes"]}
        reached={0}
        while True:
            previous=len(reached)
            for e in self.data["edges"]:
                if e["from"] in reached: reached.add(e["to"])
                if e["bidirectional"] and e["to"] in reached: reached.add(e["from"])
            if len(reached)==previous:break
        if reached!=ids:raise ValueError(f'{self.data["id"]}: disconnected nodes {ids-reached}')
        # Air landings reserve 24m across, 20m along, then 30m rollout.
        for pad in self.data["launchPads"]:
            s,t=pad["source"],pad["target"];direction=1 if t["x"]>s["x"] else -1
            for along in range(-10,31,2):
                for side in range(-12,13,2):
                    point=dict(x=t["x"]+direction*along,y=8,z=t["z"]+side)
                    self.surface(point)
                    for box in self.data["boxes"]:
                        if not box["collision"]:continue
                        c,b=box["center"],box["size"]
                        if c["y"]+b["y"]/2<=8.1 or c["y"]-b["y"]/2>=15:continue
                        if abs(point["x"]-c["x"])<b["x"]/2-1e-5 and abs(point["z"]-c["z"])<b["z"]/2-1e-5:
                            raise ValueError(f'{self.data["id"]}: landing obstruction {pad["id"]}/{box["id"]}')
        OUT.mkdir(parents=True,exist_ok=True)
        (OUT/(self.resource+".json")).write_text(json.dumps(self.data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(f'{self.data["id"]}: {len(self.data["boxes"])} boxes, {len(ids)} nodes, {len(self.data["edges"])} edges')


def ring(width,depth,rows,west=24,east=None):
    east=width-24 if east is None else east
    result=[("south-west",(west,24,0)),("south-mid",(width/2,24,0)),("south-east",(east,24,0))]
    result += [(f"east-{i}",(east,z,0)) for i,z in enumerate(rows)]
    result += [("north-east",(east,depth-16,0)),("north-mid",(width/2,depth-16,0)),("north-west",(west,depth-16,0))]
    result += [(f"west-{i}",(west,z,0)) for i,z in reversed(list(enumerate(rows)))]
    return result


def construction():
    a=Arena("construction_17","arena-construction-17","МЕГАСТРОЙ-17","CONSTRUCTION",240,220,
            [(24,24),(216,24),(220,84),(200,204),(120,204),(24,198),(24,88)],
            [(72,66,-3),(122,24),(204,50),(210,98),(144,204),(26,136),(90,180,8),(132,150,8),(28,206),(212,24),(164,24),(50,170)],
            ("boss_foreman","Бригадир",4000,("POWER","CANNON"),[(224,72),(216,204)],
             ["Объект закрыт. Посторонних — под отвал","Сейчас выровняем!"]))
    for name,rect in [("floor-west",(0,40,0,220)),("floor-east",(104,240,0,220)),("floor-south",(40,104,0,44)),("floor-north",(40,104,86,220))]:a.slab(name,rect)
    a.slab("pit-floor",(40,104,44,86),-3)
    a.ramp("pit-west-ramp",(40,58,44,86),0,-3,"X",-3.6)
    a.ramp("pit-east-ramp",(86,104,44,86),-3,0,"X",-3.6)
    a.slab("parking-deck",(64,184,104,192),8,"concrete",1)
    a.cover("upper-island-west",(103,117,142,160),8,3)
    a.cover("upper-island-east",(145,159,142,160),8,3)
    a.cover("parking-pier-west",(105,111,146,156),0,7.4)
    a.cover("parking-pier-east",(149,155,146,156),0,7.4)
    a.cover("container-island-south",(174,190,40,54),0,5,"blue")
    a.cover("container-island-north",(174,190,78,94),0,5,"rust")
    a.slab("gravel",(194,206,72,90),.025,"concrete",0,.8)
    a.rectangle_rails("parking",(64,184,104,192),dict(west=[(108,132)],east=[(134,158),(162,186)],south=[(120,144)]))
    a.gate("short-cut",(164,68))
    a.secret((34,162),(70,178))
    for i,p in enumerate([(180,62),(210,120),(106,88)]):a.hazard(f"crane-{i+1}","CRANE",p,(14,14,6),f"crane-sector-{i+1}",2,3,3,120)
    a.pads(((30,120,0),(90,120,8)),((218,174,0),(158,174,8)),((184,146,8),(218,146,0)),((132,38,0),(132,104,8)))
    upper=[("u-west-south",(84,120,8)),("u-mid-south",(132,120,8)),("u-east-south",(171,120,8)),("u-east-mid",(171,146,8)),("u-east-north",(171,174,8)),("u-mid-north",(132,174,8)),("u-west-north",(84,174,8))]
    a.network(ring(240,220,[65,110,146,174],west=13),upper,[[n for n,_ in upper]+[upper[0][0]],["u-mid-south","u-mid-north"]])
    for name,p in [("pit-west-top",(40,66,0)),("pit-west-bottom",(58,66,-3)),("pickup-W1",(72,66,-3)),("pit-east-bottom",(86,66,-3)),("pit-east-top",(104,66,0))]:a.node(name,p)
    a.edge("west-0","pit-west-top",24)
    a.edge("pit-west-top","pit-west-bottom",24,"RAMP","pit-west-ramp")
    a.chain(["pit-west-bottom","pickup-W1","pit-east-bottom"],width=24)
    a.edge("pit-east-bottom","pit-east-top",24,"RAMP","pit-east-ramp")
    a.attach("pit-east-road",(108,66,0),width=6)
    a.edge("pit-east-top","pit-east-road",24)
    a.opening("short-cut",(164,68))
    return a


def neon():
    a=Arena("neon_zero","arena-neon-zero","НЕОН-РАЙОН ZERO","NEON",280,240,
            [(24,24),(252,24),(250,94),(250,220),(180,224),(92,224),(28,208),(28,82)],
            [(44,48),(120,28),(228,48),(248,142),(174,232),(34,188),(82,192,8),(140,202,8),(28,224),(252,28),(192,96),(232,118)],
            ("boss_prefect","Префект",3400,("HOMING","MINE"),[(252,44),(28,216)],
             ["В этом районе даже аварии происходят по разрешению","Ваш маршрут отменён"]))
    a.slab("floor",(0,280,0,240))
    a.slab("transport-plaza",(64,216,108,224),8,"asphalt",1)
    a.cover("upper-service-block",(124,152,148,180),8,4,"blue")
    a.cover("service-base",(126,150,148,180),0,7.4,"concrete")
    a.cover("shop-west",(58,96,42,60),0,6,"blue")
    a.cover("shop-east",(184,214,38,56),0,6,"black")
    a.cover("lower-west-island",(66,90,152,170),0,5,"blue")
    a.rectangle_rails("plaza",(64,216,108,224),dict(west=[(116,140)],east=[(152,176),(188,212)],south=[(128,152)]))
    a.gate("short-cut",(78,90))
    a.secret((226,104),(244,132))
    # Separate lanes physically terminate before the uninterrupted ramp corridor.
    a.hazard("traffic-west","TRAFFIC",(66,71),(104,18,4),"traffic",1.5,4,1.5,120)
    a.hazard("traffic-east","TRAFFIC",(218,71),(104,18,4),"traffic",1.5,4,1.5,120)
    a.cover("traffic-west-stop",(116,118,62,80),0,1.2,"steel")
    a.cover("traffic-east-stop",(162,164,62,80),0,1.2,"steel")
    a.hazard("electric-strip","ELECTRIC",(226,92),(10,22,6),"east-electric")
    for name,p in [("city-barrier-west",(104,130)),("city-barrier-east",(176,130))]:
        a.box(name+"-panel",(*p,1),(1,16,2),"yellow",False)
        a.data["barriers"].append(dict(id=name,geometryId=name+"-panel",sector=name,offTicks=1680,warningTicks=180,activeTicks=480))
    a.pads(((28,128,0),(88,128,8)),((252,200,0),(192,200,8)),((216,164,8),(250,164,0)),((140,42,0),(140,108,8)))
    upper=[("u-sw",(88,128,8)),("u-sm",(140,128,8)),("u-se",(192,128,8)),("u-east-mid",(192,164,8)),("u-ne",(192,200,8)),("u-nm",(140,200,8)),("u-nw",(88,200,8))]
    a.network(ring(280,240,[90,128,164,200],east=266),upper,[[n for n,_ in upper]+[upper[0][0]]])
    a.opening("short-cut",(78,90))
    a.opening("city-barrier-west",(104,130))
    a.opening("city-barrier-east",(176,130))
    return a


def carnival():
    a=Arena("euphoria_park","arena-euphoria-park","ЛУНАПАРК ЭЙФОРИЯ","CARNIVAL",260,240,
            [(24,24),(232,24),(236,92),(238,220),(168,226),(84,226),(22,206),(24,62)],
            [(64,34),(148,28),(228,76),(234,146),(130,222),(24,172),(70,198,8),(184,162,8),(26,222),(232,28),(112,108),(38,100)],
            ("boss_emcee","Конферансье",4000,("NAPALM","POWER"),[(232,40),(130,224)],
             ["Билеты невозвратные. Как и вы!","А теперь — верхний ярус!"]))
    a.slab("floor",(0,260,0,240))
    a.slab("west-roof",(40,112,124,212),8,"concrete",1)
    a.slab("east-roof",(148,220,124,212),8,"concrete",1)
    # The specified gallery spans X100..160; its end portions already belong to
    # the roofs. Emit only the unsupported middle to avoid coplanar double floors.
    a.slab("south-gallery",(112,148,128,156),8,"steel",1)
    a.slab("north-gallery",(112,148,180,208),8,"steel",1)
    a.cover("stage-figure",(67,85,169,187),8,8,"yellow")
    a.cover("west-pavilion-core",(68,84,164,184),0,7.4,"red")
    a.cover("east-pavilion-core",(178,198,164,180),0,7.4,"blue")
    a.cover("southern-stage",(42,78,44,58),0,4,"red")
    a.rectangle_rails("west-roof",(40,112,124,212),dict(west=[(132,156)],east=[(128,156),(180,208)],south=[(58,82)]))
    a.rectangle_rails("east-roof",(148,220,124,212),dict(east=[(180,204)],west=[(128,156),(180,208)],south=[(172,196)]))
    for z in [127.7,156.3,179.7,208.3]:a.rail(f"gallery-rail-{z}",(112,z),(148,z))
    a.gate("short-cut",(128,116),"Z")
    a.secret((26,88),(50,112))
    a.hazard("carousel","CAROUSEL",(130,78),(36,36,6),"central-carousel",1.5,4,1.5,96)
    a.hazard("stage-fire","FIRE",(88,52),(20,6,6),"south-stage",1.5,2)
    a.pads(((10,144,0),(70,144,8)),((250,192,0),(190,192,8)),((70,124,8),(70,88,0)),((184,58,0),(184,124,8)))
    upper=[("u-west-south",(70,144,8)),("u-south-bridge",(130,144,8)),("u-east-south",(190,144,8)),("u-east-north",(190,192,8)),("u-north-bridge",(130,192,8)),("u-west-inner",(100,192,8)),("u-west-ne",(100,200,8)),("u-west-nw",(53,200,8)),("u-west-sw",(53,144,8))]
    ground=ring(260,240,[72,126,172,202]);at=next(i for i,v in enumerate(ground) if v[0]=="west-0")
    ground[at:at]=[("west-secret-north",(72,126,0)),("west-secret-south",(72,72,0))]
    a.network(ground,upper,[[n for n,_ in upper]+[upper[0][0]]])
    a.opening("short-cut",(128,116),"Z")
    return a


def necropolis():
    a=Arena("ash_necropolis","arena-ash-necropolis","НЕКРОПОЛЬ ПЕПЛА","NECROPOLIS",260,240,
            [(26,24),(230,24),(234,84),(234,150),(224,222),(158,228),(84,228),(26,210),(24,70)],
            [(74,30),(162,30),(226,112),(224,210),(130,232),(30,146),(88,202,8),(130,200,8),(28,224),(230,28),(174,76),(48,104)],
            ("boss_ash_shepherd","Пастырь Пепла",4600,("NAPALM","CANNON"),[(232,40),(226,224)],
             ["Для каждого здесь приготовлено место","Круг должен замкнуться"]))
    a.slab("floor",(0,260,0,240))
    a.slab("mausoleum-court",(64,196,112,220),8,"concrete",1)
    a.cover("bell-structure",(118,142,154,178),8,8,"ivory")
    a.cover("mausoleum-base",(118,142,154,178),0,7.4,"concrete")
    a.cover("graves-west",(58,94,44,54),0,4,"ivory")
    a.cover("graves-east",(166,190,40,54),0,4,"ivory")
    a.cover("graves-east-north",(200,214,158,178),0,4,"ivory")
    a.rectangle_rails("court",(64,196,112,220),dict(west=[(122,146),(166,190)],east=[(182,206)],south=[(120,144)]))
    a.gate("short-cut",(198,96),effect="STATUE",delay=240)
    a.secret((34,92),(62,112))
    a.hazard("ritual-west","FIRE",(84,76),(20,6,6),"lower-west")
    a.hazard("ritual-east","FIRE",(184,144),(20,6,6),"lower-east")
    a.hazard("ritual-upper","FIRE",(162,164,8),(14,6,6),"upper-east")
    a.pads(((28,134,0),(88,134,8)),((232,194,0),(172,194,8)),((64,178,8),(30,178,0)),((132,46,0),(132,112,8)))
    upper=[("u-sw",(88,134,8)),("u-sm",(132,134,8)),("u-se",(172,134,8)),("u-ne",(172,194,8)),("u-nm",(130,194,8)),("u-nw",(88,194,8))]
    ground=ring(260,240,[70,134,178,208]);at=next(i for i,v in enumerate(ground) if v[0]=="west-0")
    ground[at:at]=[("west-secret-north",(86,134,0)),("west-secret-south",(86,70,0))]
    a.network(ground,upper,[[n for n,_ in upper]+[upper[0][0]]])
    a.opening("short-cut",(198,96))
    return a


def doomsday():
    a=Arena("doomsday_arena","arena-doomsday","АРЕНА СУДНОГО ДНЯ","SHOW",240,240,
            [(24,24),(214,24),(214,216),(26,214),(120,224)],
            [(66,28),(162,28),(212,100),(212,186),(120,232),(26,108),(170,138,8),(120,207,8),(28,226),(216,26),(138,92),(204,104)],
            ("boss_director","Директор",5600,("BALLISTIC","CANNON"),[(210,38),(214,218)],
             ["Рейтинг падает. Придётся выйти самому","Никаких титров. Продолжаем!"]))
    a.slab("floor",(0,240,0,240))
    for name,rect in [("ring-west",(50,92,116,224)),("ring-east",(148,190,116,224)),("ring-south",(92,148,116,150)),("ring-north",(92,148,190,224))]:a.slab(name,rect,8,"steel",1)
    a.cover("lower-island-west",(64,88,142,166),0,5,"blue")
    a.cover("lower-island-east",(152,176,166,190),0,5,"rust")
    a.rectangle_rails("ring-outer",(50,190,116,224),dict(west=[(124,148),(166,190)],east=[(192,216)],south=[(108,132)]))
    # Inner rails occupy the hole, preserving every metre of useful ring width.
    a.rail("hole-west",(92.3,150),(92.3,190))
    a.rail("hole-east",(147.7,150),(147.7,190))
    a.rail("hole-south",(92,150.3),(148,150.3))
    a.rail("hole-north",(92,189.7),(148,189.7))
    a.gate("short-cut",(78,94))
    a.secret((192,92),(214,116))
    a.hazard("show-crane","CRANE",(62,62),(14,14,6),"construction",2,3,3,120,16)
    a.hazard("show-electric","ELECTRIC",(180,70),(22,10,6),"city",rest=16)
    a.hazard("show-fire","FIRE",(30,194),(20,6,6),"theatre",active=2,rest=16)
    a.gate("control-shield-south",(86,98),width=2,effect="SHIELD",block=1440)
    a.gate("control-shield-north",(180,222),width=2,effect="SHIELD",block=1440)
    a.pads(((14,136,0),(74,136,8)),((226,204,0),(166,204,8)),((50,178,8),(16,178,0)),((120,50,0),(120,116,8)))
    # M1 moved 6m east from the specified (132,92): that original point lies on
    # the 5.09m-high ramp side wall and blocks the required pickup visibility ray.
    upper=[("u-sw",(74,136,8)),("u-sm",(120,136,8)),("u-se",(166,136,8)),("u-ne",(166,204,8)),("u-nm",(120,204,8)),("u-nw",(74,204,8)),("u-west-mid",(74,178,8))]
    ground=ring(240,240,[94,136,178,204]);at=next(i for i,v in enumerate(ground) if v[0]=="east-0")
    ground[at:at+1]=[("east-secret-south",(216,64,0)),("east-inner-south",(168,64,0)),("east-inner-north",(168,136,0))]
    at=next(i for i,v in enumerate(ground) if v[0]=="north-mid")
    ground[at:at]=[("north-shield-east",(204,208,0)),("north-shield-west",(156,208,0))]
    a.network(ground,upper,[[n for n,_ in upper]+[upper[0][0]]])
    a.opening("short-cut",(78,94))
    return a


if __name__ == "__main__":
    arenas=[construction(),neon(),carnival(),necropolis(),doomsday()]
    for arena in arenas:arena.finish()
    entries=[dict(id="dead-air-yard",resourceKey="arena",campaign=False)]+[
        dict(id=a.data["id"],resourceKey=a.resource,campaign=True) for a in arenas]
    (OUT/"arenas.json").write_text(json.dumps(dict(schemaVersion=1,entries=entries),ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
