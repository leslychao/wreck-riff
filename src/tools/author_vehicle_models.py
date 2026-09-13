"""Original offline Wreck Riff vehicle authoring. Run with Blender 4.5.9 --background --python.

One immutable indexed topology per LOD, five full damage poses and eight sparse
regional deltas. Blender files retain editable meshes/shape keys. No network.
"""
import argparse, gzip, hashlib, json, math, sys
from pathlib import Path
import bpy
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'src/tools/assets/vehicles'
PROFILES = {
 'rivet': (4.6,2.1,1.07,'coupe'), 'grinder':(5.9,2.55,2.6,'truck'),
 'spark':(3.1,1.65,1.2,'buggy'), 'boss_foreman':(12,5,5.5,'bulldozer'),
 'boss_prefect':(9,3.3,3,'armored'), 'boss_emcee':(8,4.2,4.6,'stage'),
}
REGIONS = ('front-left','front-right','side-left','side-right','rear-left','rear-right','roof','engine')
COLORS = {'rivet':(.30,.34,.37),'grinder':(.68,.52,.19),'spark':(.55,.76,.08),
          'boss_foreman':(.84,.51,.08),'boss_prefect':(.16,.25,.35),'boss_emcee':(.49,.12,.36)}

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

class Author:
    def __init__(self, profile, lod):
        self.id=profile; self.lod=lod; self.length,self.width,self.height,self.style=PROFILES[profile]
        self.parts={}; self.current='paint'; self.sub=(7,2,1)[lod]; self.round=(24,12,6)[lod]
    def part(self,name): self.current=name
    def tri(self,a,b,c):
        out=self.parts.setdefault(self.current,[]); out.extend((a,b,c))
    def quad(self,a,b,c,d,n=1,m=1):
        a,b,c,d=map(np.array,(a,b,c,d))
        def p(u,v): return tuple((a*(1-u)+b*u)*(1-v)+(d*(1-u)+c*u)*v)
        for i in range(n):
            for j in range(m):
                p0,p1,p2,p3=p(i/n,j/m),p((i+1)/n,j/m),p((i+1)/n,(j+1)/m),p(i/n,(j+1)/m)
                self.tri(p0,p1,p2);self.tri(p0,p2,p3)
    def box(self,x,y,z,hx,hy,hz,sub=1,bevel=.025):
        # Chamfered formed metal; subdivided broad faces support smooth damage folds.
        if self.lod==2 and not self.current.startswith('panel-'):
            a=(x-hx,y-hy,z-hz);b=(x+hx,y-hy,z-hz);c=(x+hx,y+hy,z-hz);d=(x-hx,y+hy,z-hz)
            e=(x-hx,y-hy,z+hz);f=(x+hx,y-hy,z+hz);g=(x+hx,y+hy,z+hz);h=(x-hx,y+hy,z+hz)
            for face in ((a,d,c,b),(e,f,g,h),(a,e,h,d),(b,c,g,f),(d,h,g,c),(a,b,f,e)):self.quad(*face)
            return
        b=min(bevel,hx*.3,hy*.3,hz*.3)
        rings=[(-hz,hx-b,hy-b),(-hz+b,hx,hy),(hz-b,hx,hy),(hz,hx-b,hy-b)]
        prior=None
        for zz,xx,yy in rings:
            ring=[(x-xx+b,y-yy,z+zz),(x+xx-b,y-yy,z+zz),(x+xx,y-yy+b,z+zz),(x+xx,y+yy-b,z+zz),
                  (x+xx-b,y+yy,z+zz),(x-xx+b,y+yy,z+zz),(x-xx,y+yy-b,z+zz),(x-xx,y-yy+b,z+zz)]
            if prior:
                for k in range(8): self.quad(prior[k],prior[(k+1)%8],ring[(k+1)%8],ring[k],sub if k%2==0 else 1,sub)
            else:
                for k in range(1,7):self.tri(ring[0],ring[k+1],ring[k])
            prior=ring
        for k in range(1,7):self.tri(prior[0],prior[k],prior[k+1])
    def loft(self,rings,sub=None):
        sub=self.sub if sub is None else sub
        def ring(r):
            z,w,b,t=r;v=min(.08*self.width,(t-b)*.22)
            return [(-w+v,b,z),(w-v,b,z),(w,b+v,z),(w,t-v,z),(w-v,t,z),(-w+v,t,z),(-w,t-v,z),(-w,b+v,z)]
        for r,s in zip(rings,rings[1:]):
            a,b=ring(r),ring(s)
            for i in range(8):self.quad(a[i],a[(i+1)%8],b[(i+1)%8],b[i],sub if i%2==0 else 1,sub)
        for r,reverse in ((rings[0],True),(rings[-1],False)):
            a=ring(r)
            for i in range(1,7):self.tri(a[0],a[i+1] if reverse else a[i],a[i] if reverse else a[i+1])
    def cylinder(self,x,y,z,radius,length,axis='z',count=None):
        count=count or self.round
        def p(t,a,r):
            c,s=math.cos(a)*r,math.sin(a)*r
            return (x+t,y+c,z+s) if axis=='x' else (x+c,y+s,z+t)
        for i in range(count):
            a,b=i*math.tau/count,(i+1)*math.tau/count
            self.quad(p(-length/2,a,radius),p(-length/2,b,radius),p(length/2,b,radius),p(length/2,a,radius))
            self.tri(p(-length/2,0,0),p(-length/2,b,radius),p(-length/2,a,radius))
            self.tri(p(length/2,0,0),p(length/2,a,radius),p(length/2,b,radius))
    def tube(self,x,y,z,outer,inner,length,axis='z',count=None):
        count=count or self.round
        def p(t,a,r):
            c,s=math.cos(a)*r,math.sin(a)*r
            return (x+t,y+c,z+s) if axis=='x' else (x+c,y+s,z+t)
        for i in range(count):
            a,b=i*math.tau/count,(i+1)*math.tau/count
            self.quad(p(-length/2,a,outer),p(-length/2,b,outer),p(length/2,b,outer),p(length/2,a,outer))
            self.quad(p(-length/2,b,inner),p(-length/2,a,inner),p(length/2,a,inner),p(length/2,b,inner))
            self.quad(p(length/2,a,inner),p(length/2,a,outer),p(length/2,b,outer),p(length/2,b,inner))
            self.quad(p(-length/2,b,inner),p(-length/2,b,outer),p(-length/2,a,outer),p(-length/2,a,inner))
    def author(self):
        L,W,H=self.length,self.width,self.height; w=W/2;l=L/2
        sy=1 if self.id=='rivet' else H/1.07
        # Native-profile silhouettes: low coupe, exposed buggy, cab-over grinder,
        # industrial command dozer, armored limousine, theatrical amplifier truck.
        self.part('paint')
        low=.32*H if self.style in ('coupe','buggy') else .34*H
        self.loft([(-l,w*.72,-.07*H,low*.70),(-l*.78,w*.97,-.08*H,low),
                   (-l*.25,w*.95,-.08*H,low*1.1),(l*.55,w*.94,-.07*H,low),(l,w*.78,-.04*H,low*.55)])
        if self.style=='truck':cab=[(-.4,w*.78,.55,1.0),(.0,w*.82,.55,2.40),(1.40,w*.78,.55,2.40),(1.85,w*.84,.55,1.2)]
        else:
            cab=[(-l*.59,w*.72,low*.95,low*1.15),(-l*.38,w*.64,low*.95,H*.90),
                 (l*.02,w*.62,low*.95,H*.91),(l*.24,w*.73,low*.95,low*1.15)]
        self.loft(cab)
        # Independent mounted glass; recess and thick window borders come from cab envelope.
        self.part('glass');front=cab[-1];top=cab[-2]
        self.quad((-front[1]*.87,front[3]+.012,front[0]+.012),(front[1]*.87,front[3]+.012,front[0]+.012),
                  (top[1]*.88,top[3]*.96,top[0]+.02),(-top[1]*.88,top[3]*.96,top[0]+.02),self.sub*2,self.sub)
        for side in (-1,1):
            self.quad((side*cab[0][1]*1.01,low*1.2,cab[0][0]*.93),(side*cab[1][1]*1.01,cab[1][3]*.94,cab[1][0]),
                      (side*top[1]*1.01,top[3]*.94,top[0]),(side*front[1]*1.01,low*1.2,front[0]*.8),self.sub,self.sub)
        # Four breakaway parts are actual authored meshes, backed by a dark inner frame.
        self.part('panel-hood');self.box(0,low*1.03,l*.58,w*.66,.045*max(1,sy),l*.25,self.sub,.035)
        self.part('panel-trunk');self.box(0,low*1.05,-l*.79,w*.66,.045*max(1,sy),l*.14,self.sub,.035)
        for side in (-1,1):
            self.part('panel-door-'+('left' if side<0 else 'right'))
            self.box(side*w*.965,low*.60,-l*.08,.022*W,low*.37,l*.27,self.sub,.02)
        self.part('steel')
        self.box(0,low*.3,l*.99,w*.94,.065*H,.045*L,max(1,self.sub//2),.035)
        self.box(0,low*.4,-l*.99,w*.9,.065*H,.04*L,max(1,self.sub//2),.035)
        for side in (-1,1):
            self.box(side*w*.99,low*.15,-l*.08,.025*W,.045*H,l*.60,1)
            self.box(side*w*.83,low*1.25,l*.10,.09*W,.04*H,.055*L,1)
            # Wheel arch lips and suspension bars.
            for zz in (-L*1.4/4.6,L*1.4/4.6):
                for i in range((12,7,3)[self.lod]):
                    a=(i+.5)*math.pi/(12,7,3)[self.lod];rr=.43*W/2.1
                    self.box(side*w*.995,-.15*W/2.1+math.sin(a)*rr,zz+math.cos(a)*rr,.025*W,.022*H,.034*L,1,.012)
        # Intact chassis inner cavities, intake grille, ductwork and fasteners.
        self.part('rubber-trim');self.box(0,low*.38,l*1.015,w*.38,.065*H,.014*L,1,.009)
        self.box(0,-.065*H,0,w*.64,.045*H,l*.72,1)
        self.part('steel')
        for i in range((-4 if self.lod==0 else -2),(5 if self.lod==0 else 3)):
            self.box(i*w*.12,low*.39,l*1.025,.012*W,.060*H,.009*L,1,.003)
        if self.lod<2:
            for side in (-1,1):
                for z in (-.65,-.25,.25,.60):
                    self.cylinder(side*w*.74,low*1.1+.025,l*z,.020*W,.012*H,'z',6)
            self.part('rubber-trim')
            for i in range(6):self.box(0,low*1.14,l*(.30+i*.058),w*.34,.010*H,.014*L,1,.001)
        # Fixed receivers, moving bolt carriers and heat-darkened hollow barrels.
        for barrel in range(2):
            x=(-.75 if barrel==0 else .75) if self.id=='grinder' else (-.53 if barrel==0 else .53)*W/2.1
            y=2.48 if self.id=='grinder' else .47*sy; z=2.95 if self.id=='grinder' else 2.28*L/4.6
            name='mount-machine-gun-'+str(barrel)
            self.part(name);self.box(x,y-.075,z-.39,.085,.065,.16,1,.016)
            self.part(name+'-barrel');self.tube(x,y,z-.21,.055*W/2.1,.032*W/2.1,.42,'z',self.round)
            self.tube(x,y,z-.025,.075*W/2.1,.032*W/2.1,.05,'z',self.round)
            self.part(name+'-bolt');self.box(x+.073,y+.008,z-.42,.02,.027,.068,1,.006)
            if self.lod<2:
                self.part(name+'-barrel')
                for zz in range(3):self.tube(x,y,z-.17-zz*.06,.060*W/2.1,.048*W/2.1,.018,'z',12)
        y=2.58 if self.id=='grinder' else .55*sy;z=3.15 if self.id=='grinder' else 2.5*L/4.6
        self.part('mount-weapon');self.box(0,y-.075,z-.45,.13*W/2.1,.10*W/2.1,.12,1,.025)
        self.part('mount-weapon-barrel');self.tube(0,y,z-.27,.105*W/2.1,.075*W/2.1,.52,'z')
        self.tube(0,y,z-.025,.14*W/2.1,.075*W/2.1,.05,'z')
        self.part('mount-weapon-bolt');self.box(.14*W/2.1,y,z-.43,.027,.045,.11,1,.012)
        self.part('headlights')
        for side in (-1,1):self.box(side*w*.64,low*.61,l*.985,.13*W,.035*H,.025,1,.006)
        self.part('taillights')
        for side in (-1,1):self.box(side*w*.65,low*.67,-l*.97,.10*W,.027*H,.025,1,.006)
        # Model-specific mechanical identity, kept inside the registered native envelope.
        if self.style in ('coupe','stage'):
            self.part('steel');self.box(0,H*.62,-l*.69,w*.57,H*.24,l*.20,1)
            self.part('rubber-trim')
            for side in (-1,1):self.cylinder(side*w*.29,H*.62,-l*.90,w*.23,.06*L,'z')
            if self.style=='stage':
                self.part('steel')
                for side in (-1,1):
                    self.box(side*w*.8,H*.60,-l*.65,.04*W,H*.29,.03*L,1)
                    for y1 in (.45,.65,.85):self.cylinder(side*w*.78,H*y1,-l*.89,.075*W,.035*L,'z')
        if self.style in ('truck','bulldozer'):
            self.part('steel');self.box(0,H*.25,-l*.65,w*.8,H*.13,l*.26,1)
            for side in (-1,1):self.box(side*w*.84,H*.32,-l*.70,.045*W,H*.23,.22*L,1)
            if self.style=='bulldozer':self.box(0,H*.12,l*.95,w*.92,H*.10,.035*L,self.sub)
        if self.style=='armored':
            self.part('steel')
            for side in (-1,1):
                for z1 in (-.55,0,.55):self.box(side*w*.96,H*.31,z1*l,.032*W,.13*H,.10*L,1)
        if self.style=='buggy':
            self.part('steel')
            for side in (-1,1):self.box(side*w*.67,H*.66,-l*.37,.025*W,.27*H,.03*L,1)
            self.box(0,H*.91,-l*.38,w*.66,.027*H,.027*L,1)
        if self.id.startswith('boss_'):
            self.part('boss-panels');self.box(0,H*.60,-l*.47,w*.60,.15*H,.025*L,self.sub)
            self.part('service-cover');self.box(0,H*.63,-l*.51,w*.24,.14*H,.03*L,1)
            self.part('service-core');self.cylinder(0,H*.63,-l*.53,w*.19,.025*L,'z')
        if self.id=='grinder':
            for side in (-1,1):
                self.part('grinder-roller-'+('left' if side<0 else 'right'))
                self.cylinder(side*.62,.42,2.78,.31,1.15,'x',self.round)
                if self.lod<2:
                    for i in range(10 if self.lod==0 else 6):
                        a=i*math.tau/(10 if self.lod==0 else 6);self.box(side*.62,.42+math.cos(a)*.32,2.78+math.sin(a)*.32,.49,.045,.045,1,.01)
        # Rounded carcass, sidewall and recessed hub exported in wheel-local metres.
        for wheel in range(4):
            self.part('wheel-'+str(wheel)+'-tyre')
            count=(24,8,6)[self.lod]
            ring=[(-.15,.22),(-.165,.30),(-.13,.36),(-.08,.38),(.08,.38),(.13,.36),(.165,.30),(.15,.22)] if self.lod==0 else [(-.15,.24),(-.14,.38),(.14,.38),(.15,.24)]
            for (ax,ar),(bx,br) in zip(ring,ring[1:]):
                for i in range(count):
                    a=i*math.tau/count;b=(i+1)*math.tau/count
                    self.quad((ax,math.cos(a)*ar,math.sin(a)*ar),(ax,math.cos(b)*ar,math.sin(b)*ar),
                              (bx,math.cos(b)*br,math.sin(b)*br),(bx,math.cos(a)*br,math.sin(a)*br))
            if self.lod<2:
                for i in range((24,12)[self.lod]):
                    angle=(i+.5)*math.tau/(24,12)[self.lod]
                    for side in (-1,1):
                        a,b=angle-.028,angle+.028
                        self.quad((side*.11,math.cos(a)*.388,math.sin(a)*.388),(0,math.cos(a+.03)*.388,math.sin(a+.03)*.388),(0,math.cos(b+.03)*.388,math.sin(b+.03)*.388),(side*.11,math.cos(b)*.388,math.sin(b)*.388))
            self.part('wheel-'+str(wheel)+'-hub');self.tube(0,0,0,.225,.185,.30,'x',count)
            self.cylinder(0,0,0,.075,.325,'x',count)
            for side in (-1,1):
                for i in range((6,5,3)[self.lod]):
                    angle=i*math.tau/(6,5,3)[self.lod]
                    c,d=math.cos(angle),math.sin(angle);v,u=-d*.027,c*.027
                    a=(side*.152,c*.073+v,d*.073+u);b=(side*.152,c*.208+v,d*.208+u)
                    cc=(side*.152,c*.208-v,d*.208-u);dd=(side*.152,c*.073-v,d*.073-u)
                    self.quad(a,b,cc,dd) if side>0 else self.quad(dd,cc,b,a)
                    if self.lod==0:self.cylinder(side*.17,c*.10,d*.10,.019,.026,'x',6)
        return self.parts

def normals(p):
    faces=p.reshape(-1,3,3);n=np.cross(faces[:,1]-faces[:,0],faces[:,2]-faces[:,0]);n/=np.maximum(np.linalg.norm(n,axis=1,keepdims=True),1e-8)
    return np.repeat(n,3,axis=0)

def uvmap(p,n,profile):
    L,W,H,_=PROFILES[profile];axis=np.argmax(abs(n),axis=1);sign=(n[np.arange(len(n)),axis]>0).astype(int);face=axis*2+sign
    u=np.where(axis==0,p[:,2]/L+.5,p[:,0]/W+.5);v=np.where(axis==1,p[:,2]/L+.5,p[:,1]/H+.08)
    return np.column_stack(((face%3+.06+np.clip(u,0,1)*.88)/3,(face//3+.06+np.clip(v,0,1)*.88)/2))

def displacement(p,profile,region):
    L,W,H,_=PROFILES[profile];centers=[(-.8,.25,.8),(.8,.25,.8),(-1,.35,0),(1,.35,0),(-.7,.25,-.85),(.7,.25,-.85),(0,.85,-.1),(0,.28,.58)]
    q=p/np.array((W/2,H,L/2));c=np.array(centers[region]);r=np.array((.85,.70,.75))
    weight=np.maximum(0,1-np.sum(((q-c)/r)**2,axis=1))**1.3
    direction=-c;direction[1]=-.6;direction/=np.linalg.norm(direction)
    ripple=(np.sin(q[:,2]*19+q[:,0]*7)*.035)[:,None]*np.array((0,1,0))
    return (direction*.22+ripple)*weight[:,None]

def make_textures(profile,out):
    # Authored six-face paint atlas, worn coating, seam rivets and stamped industrial graphics.
    size=2048;y,x=np.mgrid[:size,:size];rng=np.random.default_rng(173+list(PROFILES).index(profile))
    grain=rng.normal(0,.015,(size,size));base=np.array(COLORS[profile]);noise=grain+np.sin(x*.043)*np.cos(y*.028)*.025
    rgb=np.clip(base[None,None,:]*(.85+noise[:,:,None]),0,1)
    seam=(np.mod(x,171)<3)|(np.mod(y,229)<3);stripe=(y%1024>900)&(y%1024<918)&((x+y)//24%2==0)
    rgb[seam]*=.42;rgb[stripe]=(.83,.67,.18)
    bolts=((x%171-13)**2+(y%229-13)**2<16);rgb[bolts]=(.40,.43,.45)
    scratches=(rng.random((size,size))>.9990)&(np.sin(x*.015+y*.07)>.3);rgb[scratches]=(.47,.46,.41)
    h=grain*.08+seam*.025+bolts*.10;dy,dx=np.gradient(h);normal=np.stack((-dx*8,-dy*8,np.ones_like(h)),axis=2);normal/=np.linalg.norm(normal,axis=2,keepdims=True)
    spec=np.clip(.37+noise*.5-seam*.22-scratches*.10,0,1)
    def save(name,pixels):
        height,width=pixels.shape[:2]
        image=bpy.data.images.new(name,width=width,height=height,alpha=True,float_buffer=False)
        if 'diffuse' not in name:image.colorspace_settings.name='Non-Color'
        rgba=np.ones((height,width,4),np.float32);rgba[:,:,:3]=pixels
        image.pixels.foreach_set(rgba.ravel());image.file_format='PNG';image.filepath_raw=str(out/name);image.save();bpy.data.images.remove(image)
    save('diffuse.png',rgb);save('normal.png',normal*.5+.5);save('specular.png',np.repeat(spec[::2,::2,None],3,axis=2))
    metal=(.19+grain*1.8+np.sin(y*1.17)*.022+np.sin(x*.032+y*.004)*.03)
    metal_rgb=np.repeat(np.clip(metal,.065,.34)[:,:,None],3,axis=2)*np.array((.88,.96,1.03))
    metal_rgb[seam]*=.7;metal_rgb[scratches]=(.39,.38,.35)
    save('metal-diffuse.png',metal_rgb[::2,::2]);save('metal-normal.png',(normal*.5+.5)[::2,::2])
    save('metal-specular.png',np.repeat(np.clip(.32+grain*2,.10,.55)[::2,::2,None],3,axis=2))

def export_glb(out):
    bpy.ops.object.select_all(action='DESELECT')
    for ob in bpy.context.scene.objects:
        if ob.type=='MESH' and ob.get('lod')==0:ob.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(out/'source.glb'),export_format='GLB',use_selection=True,export_animations=False,export_morph=True,export_materials='NONE')

def source_record(profile,out,lodcounts,mode):
    record={'id':profile,'authoringMode':mode,'generatorSha256':sha(Path(__file__)),
            'exports':{name:sha(out/name) for name in ('source.blend','source.glb','mesh.json.gz','diffuse.png','normal.png','specular.png','metal-diffuse.png','metal-normal.png','metal-specular.png','damage-ownership.png')},
            'lodTriangles':lodcounts,'sourceBlendSha256':sha(out/'source.blend'),'sourceGlbSha256':sha(out/'source.glb'),'meshSha256':sha(out/'mesh.json.gz')}
    (out/'source.json').write_bytes((json.dumps(record,indent=2)+'\n').encode('utf8'))

def scene_bundle(profile):
    bundle={'schemaVersion':1,'profile':profile,'regions':list(REGIONS),'lods':[]};counts=[]
    for lod in range(3):
        records=[];triangles=0
        for ob in sorted(bpy.context.scene.objects,key=lambda ob:ob.get('runtimePart','')):
            if ob.type!='MESH' or ob.get('lod')!=lod:continue
            mesh=ob.data;keys=mesh.shape_keys.key_blocks;mesh.calc_loop_triangles();loops=[loop for t in mesh.loop_triangles for loop in t.loops];ids=[mesh.loops[i].vertex_index for i in loops]
            def values(key):
                result=[]
                for i in ids:
                    co=ob.matrix_world @ key.data[i].co;result.append((co.x,co.z,-co.y))
                return np.asarray(result,dtype=np.float32)
            p=values(keys['Basis']);stages=[p]+[values(keys['damage-'+str(i)]) if 'damage-'+str(i) in keys else p for i in range(1,5)]
            uv=np.asarray([tuple(mesh.uv_layers['CanonicalUV'].data[i].uv) for i in loops]);regional=[]
            for i in range(8):
                delta=values(keys['region-'+str(i)])-p;indices=np.where(np.linalg.norm(delta,axis=1)>.00001)[0]
                regional.append({'indices':indices.tolist(),'delta':np.round(delta[indices],6).ravel().tolist()})
            records.append({'name':ob['runtimePart'],'islandId':int(ob.get('islandId',0)),'positions':np.round(p,6).ravel().tolist(),'normal':np.round(normals(p),6).ravel().tolist(),'uv':np.round(uv,6).ravel().tolist(),
                            'stages':[np.round(stage,6).ravel().tolist() for stage in stages],'regional':regional});triangles+=len(p)//3
        if not records:raise ValueError('Editable Blender source must retain all three authored LODs')
        bundle['lods'].append(records);counts.append(triangles)
    return bundle,counts

def pack_canonical_uv():
    from mathutils import Vector
    from mathutils.bvhtree import BVHTree
    from mathutils.geometry import barycentric_transform
    bpy.ops.object.select_all(action='DESELECT')
    high=[ob for ob in bpy.context.scene.objects if ob.type=='MESH' and ob.get('lod')==0]
    for i,ob in enumerate(sorted(high,key=lambda ob:ob['runtimePart']),1):
        ob['islandId']=i;ob.select_set(True)
    bpy.context.view_layer.objects.active=high[0]
    bpy.ops.object.mode_set(mode='EDIT');bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.mesh.remove_doubles(threshold=.000001)
    bpy.ops.uv.smart_project(angle_limit=.9,island_margin=.006,area_weight=.5)
    bpy.ops.object.mode_set(mode='OBJECT')
    for source in high:
        mesh=source.data;mesh.calc_loop_triangles();triangles=list(mesh.loop_triangles)
        tree=BVHTree.FromPolygons([v.co for v in mesh.vertices],[t.vertices for t in triangles],all_triangles=True)
        source_uv=mesh.uv_layers['CanonicalUV'].data
        for target in bpy.context.scene.objects:
            if target.type!='MESH' or target.get('lod')==0 or target.get('runtimePart')!=source['runtimePart']:continue
            target['islandId']=source['islandId'];target.data.calc_loop_triangles();layer=target.data.uv_layers['CanonicalUV'].data
            for triangle in target.data.loop_triangles:
                center=sum((target.data.vertices[i].co for i in triangle.vertices),Vector())/3
                nearest,normal,index,distance=tree.find_nearest(center)
                canonical=triangles[index];points=[mesh.vertices[i].co for i in canonical.vertices]
                uv=[Vector((*source_uv[i].uv,0)) for i in canonical.loops]
                for loop in triangle.loops:
                    point=target.data.vertices[target.data.loops[loop].vertex_index].co
                    value=barycentric_transform(point,*points,*uv)
                    layer[loop].uv=(min(1,max(0,value.x)),min(1,max(0,value.y)))

def save_rgba(path,pixels,linear=True):
    h,w=pixels.shape[:2];image=bpy.data.images.new(path.name,width=w,height=h,alpha=True,float_buffer=False)
    if linear:image.colorspace_settings.name='Non-Color'
    image.pixels.foreach_set(pixels.astype(np.float32).ravel());image.file_format='PNG';image.filepath_raw=str(path);image.save();bpy.data.images.remove(image)

def ownership(bundle,out):
    # Per-pixel island identity prevents a brush footprint from spilling onto another packed part.
    pixels=np.zeros((512,512,4),np.float32);pixels[:,:,3]=1
    for part in bundle['lods'][0]:
        uv=np.asarray(part['uv']).reshape(-1,3,2)*511
        for triangle in uv:
            lo=np.maximum(0,np.floor(triangle.min(axis=0)).astype(int));hi=np.minimum(511,np.ceil(triangle.max(axis=0)).astype(int))
            a,b,c=triangle;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den)<1e-8:continue
            for y in range(lo[1],hi[1]+1):
                for x in range(lo[0],hi[0]+1):
                    wa=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/den
                    wb=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/den
                    if min(wa,wb,1-wa-wb)>=-.015:pixels[y,x,:3]=part['islandId']/255
    save_rgba(out/'damage-ownership.png',pixels)

def make_damage_atlas():
    tile=128;y,x=np.mgrid[:tile,:tile];dx=(x-63.5)/63.5;dy=(y-63.5)/63.5;r=np.sqrt(dx*dx+dy*dy);angle=np.arctan2(dy,dx)
    atlas=np.zeros((tile*2,tile*4,4),np.float32)
    for index in range(8):
        rng=np.random.default_rng(519+index);noise=rng.random(r.shape);body=np.zeros_like(r);cavity=np.zeros_like(r)
        if index<2:
            rim=.31+np.sin(angle*9+index)*.035+np.sin(angle*17)*.018
            body=np.clip(1-abs(r-rim)/.09,0,1);cavity=np.clip((rim-r)*12,0,1)
            body+=np.clip(1-r/.8,0,1)*(abs(np.sin(angle*7+index))>.992)*.7
        elif index<4:
            edge=.43+.09*np.sin(angle*5+index)+.065*np.cos(angle*11)
            body=np.clip((edge-r)*11,0,1)*(.65+.35*noise);cavity=body*np.clip((r-edge+.09)*10,0,1)
        elif index<6:
            for line in range(7):
                slope=(line-3)*.08;offset=(line-3)*.08
                body=np.maximum(body,np.clip(1-abs(dy-dx*slope-offset)/.018,0,1)*np.clip(1-abs(dx)/(1-.08*line),0,1))
        else:
            for ray in range(11):
                direction=ray*math.tau/11+.13*math.sin(ray*5+index);a=np.arctan2(np.sin(angle-direction),np.cos(angle-direction))
                branch=np.abs(a+.055*np.sin(r*23+ray))
                body=np.maximum(body,np.clip(1-branch/(.008+.012/(r+.08)),0,1)*np.clip(1-r/(.6+.3*math.sin(ray*2)**2),0,1))
            body=np.maximum(body,np.clip(1-abs(r-.22)/.012,0,1)*.4)
        atlas[index//4*tile:(index//4+1)*tile,index%4*tile:(index%4+1)*tile,0]=np.clip(body,0,1)
        atlas[index//4*tile:(index//4+1)*tile,index%4*tile:(index%4+1)*tile,1]=np.clip(cavity,0,1)
        atlas[index//4*tile:(index//4+1)*tile,index%4*tile:(index%4+1)*tile,3]=1
    shared=OUT/'shared';shared.mkdir(parents=True,exist_ok=True);save_rgba(shared/'damage-atlas.png',atlas)

def reexport(profile):
    # Read the actual saved scene; do not regenerate or silently replace artist edits.
    out=OUT/profile;bpy.ops.wm.open_mainfile(filepath=str(out/'source.blend'))
    bundle,counts=scene_bundle(profile);ownership(bundle,out)
    with gzip.GzipFile(filename=str(out/'mesh.json.gz'),mode='wb',mtime=0) as f:f.write(json.dumps(bundle,separators=(',',':')).encode())
    export_glb(out);source_record(profile,out,counts,'saved-blender-edit');print('REEXPORTED',profile,counts,flush=True)

def export(profile):
    out=OUT/profile;out.mkdir(parents=True,exist_ok=True);bpy.context.preferences.filepaths.save_version=0;bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
    for mesh in list(bpy.data.meshes):
        if mesh.users==0:bpy.data.meshes.remove(mesh)
    bundle={'schemaVersion':1,'profile':profile,'regions':list(REGIONS),'lods':[]};lodcounts=[]
    for lod in range(3):
        parts=Author(profile,lod).author();records=[];triangles=0
        for name,values in parts.items():
            p=np.asarray(values,dtype=np.float32);n=normals(p);uv=uvmap(p,n,profile)
            deformable=name in ('paint','glass','steel','rubber-trim','boss-panels','service-cover') or name.startswith('panel-')
            regional=[displacement(p,profile,i) if deformable else np.zeros_like(p) for i in range(8)]
            total=sum(regional)/1.15
            if deformable:
                L,W,H,_=PROFILES[profile];q=p/np.array((W/2,H,L/2))
                folded=np.maximum(0,abs(q[:,2])-.48)*np.clip(q[:,1]+.1,0,1)
                total[:,1]-=folded*(.21+.075*np.sin(q[:,0]*8+q[:,2]*13))
                roof=np.maximum(0,q[:,1]-.58)*np.maximum(0,1-abs(q[:,2]+.1))
                total[:,1]-=roof*.4
                total[:,0]+=folded*np.sin(q[:,2]*21)*.11
            length=np.linalg.norm(total,axis=1);total*=np.minimum(1,.32/np.maximum(length,1e-7))[:,None]
            stages=[p+total*s for s in (0,.28,.58,.85,1)]
            sparse=[]
            for d in regional:
                ids=np.where(np.linalg.norm(d,axis=1)>.00001)[0];sparse.append({'indices':ids.tolist(),'delta':np.round(d[ids],6).ravel().tolist()})
            records.append({'name':name,'positions':np.round(p,6).ravel().tolist(),'normal':np.round(n,6).ravel().tolist(),
                            'uv':np.round(uv,6).ravel().tolist(),'stages':[np.round(s,6).ravel().tolist() for s in stages], 'regional':sparse})
            triangles+=len(p)//3
            if True:
                vertices=[(float(v[0]),float(-v[2]),float(v[1])) for v in p]
                mesh=bpy.data.meshes.new(name);mesh.from_pydata(vertices,[],[(i,i+1,i+2) for i in range(0,len(p),3)]);mesh.update()
                ob=bpy.data.objects.new(name+'.lod'+str(lod),mesh);bpy.context.collection.objects.link(ob)
                layer=mesh.uv_layers.new(name='CanonicalUV')
                for loop in mesh.loops:layer.data[loop.index].uv=uv[loop.vertex_index]
                ob.shape_key_add(name='Basis')
                if deformable:
                    for i,s in enumerate(stages[1:],1):
                        key=ob.shape_key_add(name='damage-'+str(i))
                        for v,co in zip(key.data,s):v.co=(float(co[0]),float(-co[2]),float(co[1]))
                for i,delta in enumerate(regional):
                    key=ob.shape_key_add(name='region-'+str(i))
                    for v,co in zip(key.data,p+delta):v.co=(float(co[0]),float(-co[2]),float(co[1]))
                ob['profile']=profile;ob['runtimePart']=name;ob['lod']=lod;ob.hide_render=lod>0
        bundle['lods'].append(records);lodcounts.append(triangles)
    pack_canonical_uv();bundle,lodcounts=scene_bundle(profile);ownership(bundle,out)
    with gzip.GzipFile(filename=str(out/'mesh.json.gz'),mode='wb',mtime=0) as f:f.write(json.dumps(bundle,separators=(',',':')).encode())
    # The editable source retains topology, UVs and all five damage poses.
    bpy.ops.wm.save_as_mainfile(filepath=str(out/'source.blend'),compress=True)
    export_glb(out)
    make_textures(profile,out)
    source_record(profile,out,lodcounts,'original-recipe');print('VEHICLE',profile,lodcounts,flush=True)

if __name__=='__main__':
    args=sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else []
    parser=argparse.ArgumentParser();parser.add_argument('--profile',choices=PROFILES);parser.add_argument('--reexport',choices=PROFILES);opt=parser.parse_args(args)
    make_damage_atlas()
    if opt.reexport:reexport(opt.reexport)
    else:
        for profile in ([opt.profile] if opt.profile else PROFILES):export(profile)
