"""Finite authored parking bays cut into the existing three-level floor meshes.

No decals, extra surfaces, bodies, or navigation are created. A painted triangle
replaces exactly the same concrete/ground triangle area at the same elevation.
"""
import collections
import math
from author_campaign_arenas import area, ccw, half, rect, subtract

PAINT = 'road-marking'
AREA_TOLERANCE = .01  # square metres, including floating-point polygon clipping
WIDTH, DEPTH, STRIPE = 12, 18, .32
# Distinct side pockets, not a grid across the through-routes. Each tuple is a
# level, row name, first centre X, centre Z, count, and its open driving end (+/-Z).
ROWS = (
    (0, 'ground-south-west', 1440, 310, 6, 1),
    (0, 'ground-south-east', 1560, 310, 6, 1),
    (0, 'ground-north-east', 1512, 390, 8, -1),
    (8, 'first-south', 1460, 300, 8, 1),
    (8, 'first-north', 1460, 390, 8, -1),
    (8, 'first-east', 1640, 360, 1, -1),
    (16, 'roof-south', 1460, 300, 8, 1),
    (16, 'roof-north-west', 1460, 390, 8, -1),
    (16, 'roof-north-east', 1640, 380, 1, -1),
)


def _intersection(subject, clip):
    result = ccw(subject)
    clip = ccw(clip)
    for a, b in zip(clip, clip[1:]+clip[:1]):
        result = half(result, a, b) if result else []
    return result


def _bounds(poly):
    return min(p[0] for p in poly), max(p[0] for p in poly), min(p[1] for p in poly), max(p[1] for p in poly)


def _overlap(a, b):
    aa, bb = _bounds(a), _bounds(b)
    if aa[1] <= bb[0] or bb[1] <= aa[0] or aa[3] <= bb[2] or bb[3] <= aa[2]:
        return 0
    return abs(area(_intersection(a, b)))


def _faces(mesh):
    for offset in range(0, len(mesh['indices']), 3):
        vertices = [mesh['vertices'][i] for i in mesh['indices'][offset:offset+3]]
        yield ccw([(p['x'], p['z']) for p in vertices]), vertices[0]['y'], (
            mesh['triangleMaterials'][offset//3] if mesh['triangleMaterials'] else mesh['material'])


def _targets(data):
    road_ids = {identity for road in data['roads'] for identity in road['geometryIds']}
    result = []
    for mesh in data['meshes']:
        if mesh['id'] in road_ids or not mesh['collision']:
            continue
        if mesh['id'] not in ('parking-first-floor', 'parking-roof-deck') and not mesh['id'].startswith('ground-'):
            continue
        v = mesh['vertices']
        if not v or max(p['y'] for p in v)-min(p['y'] for p in v) > .0001:
            continue
        x1, x2, z1, z2 = _bounds([(p['x'], p['z']) for p in v])
        if x1 < 1660 and x2 > 1400 and z1 < 500 and z2 > 200 and v[0]['y'] in (0, 8, 16):
            result.append(mesh)
    return result


def _corridors(scene, floor):
    for path in scene.location.paths:
        for first, last in zip(path['names'], path['names'][1:]):
            a, b = scene.location.points[first], scene.location.points[last]
            if min(a[1], b[1]) > floor+4.8 or max(a[1], b[1]) < floor-.2:
                continue
            dx, dz = b[0]-a[0], b[2]-a[2]
            length = math.hypot(dx, dz)
            if length < .001:
                continue
            ux, uz = dx/length, dz/length
            radius = path['width']/2+2  # keep whole bays off the carriageway and its entry turn
            sx, sz = uz*radius, -ux*radius
            yield ccw([(a[0]-ux*2+sx, a[2]-uz*2+sz), (b[0]+ux*2+sx, b[2]+uz*2+sz),
                       (b[0]+ux*2-sx, b[2]+uz*2-sz), (a[0]-ux*2-sx, a[2]-uz*2-sz)])


def _solids(data, floor):
    for box in data['boxes']:
        c, s = box['center'], box['size']
        if not box['collision'] or c['y']-s['y']/2 > floor+4.8 or c['y']+s['y']/2 < floor+.15:
            continue
        angle = math.radians(box['yawDegrees'])
        yield ccw([(c['x']+math.cos(angle)*x+math.sin(angle)*z,
                    c['z']-math.sin(angle)*x+math.cos(angle)*z)
                   for x, z in rect(-s['x']/2-.75, s['x']/2+.75, -s['z']/2-.75, s['z']/2+.75)])


def _bays(scene, targets):
    floor_faces = {floor: [p for mesh in targets for p, y, _ in _faces(mesh) if y == floor] for floor in (0, 8, 16)}
    barriers = {floor: list(_corridors(scene, floor))+list(_solids(scene.data, floor)) for floor in (0, 8, 16)}
    result = []
    for floor, row, first_x, z, count, entry in ROWS:
        for number in range(count):
            x = first_x+number*WIDTH
            footprint = rect(x-WIDTH/2, x+WIDTH/2, z-DEPTH/2, z+DEPTH/2)
            if any(_overlap(footprint, p) > .001 for p in barriers[floor]):
                continue
            if abs(sum(_overlap(footprint, p) for p in floor_faces[floor])-WIDTH*DEPTH) > AREA_TOLERANCE:
                continue
            # Three-sided outline, open toward the neighbouring aisle. Shared
            # row separators are partitioned once by _paint_mesh, never stacked.
            back = z-entry*DEPTH/2
            stripes = [rect(x-WIDTH/2, x-WIDTH/2+STRIPE, z-DEPTH/2, z+DEPTH/2),
                       rect(x+WIDTH/2-STRIPE, x+WIDTH/2, z-DEPTH/2, z+DEPTH/2),
                       rect(x-WIDTH/2, x+WIDTH/2, min(back, back+entry*STRIPE), max(back, back+entry*STRIPE))]
            result.append(dict(id=f'{row}-{number}', floor=floor, center=(x, z), footprint=footprint, stripes=stripes))
    return result


def _paint_mesh(mesh, stripes):
    vertices, indices, materials = [], [], []
    changed = False
    for polygon, height, material in _faces(mesh):
        pieces = [(polygon, material)]
        for stripe in stripes:
            next_pieces = []
            for poly, tag in pieces:
                if tag == PAINT or _overlap(poly, stripe) <= .001:
                    next_pieces.append((poly, tag))
                    continue
                changed = True
                next_pieces.extend((outside, tag) for outside in subtract(poly, stripe))
                painted = _intersection(poly, stripe)
                if painted:
                    next_pieces.append((painted, PAINT))
            pieces = next_pieces
        for poly, tag in pieces:
            base = len(vertices)
            vertices.extend(dict(x=x, y=height, z=z) for x, z in poly)
            for i in range(1, len(poly)-1):
                if abs(area([poly[0], poly[i], poly[i+1]])) <= .0001:
                    continue
                indices.extend((base, base+i+1, base+i))
                materials.append(tag)
    if changed:
        mesh.update(vertices=vertices, indices=indices, triangleMaterials=materials)
    return changed


def validate_partition(meshes):
    """Check every target triangle, including pairs within one mesh and across seams."""
    grid = collections.defaultdict(list)
    triangles = []
    totals = collections.defaultdict(float)
    for mesh in meshes:
        for polygon, height, _ in _faces(mesh):
            totals[mesh['id']] += abs(area(polygon))
            x1, x2, z1, z2 = _bounds(polygon)
            cells = [(height, x, z) for x in range(int(x1//24), int(x2//24)+1) for z in range(int(z1//24), int(z2//24)+1)]
            for index in {i for cell in cells for i in grid[cell]}:
                identity, other = triangles[index]
                overlap = _overlap(polygon, other)
                if overlap > AREA_TOLERANCE:
                    raise ValueError(f'Parking floor overlap: {mesh["id"]} / {identity}: {overlap} m²')
            index = len(triangles)
            triangles.append((mesh['id'], polygon))
            for cell in cells:
                grid[cell].append(index)
    return dict(totals)


def dress(scene):
    if scene.data['id'] != 'neon_zero':
        raise ValueError('Parking dressing belongs to neon_zero')
    targets = _targets(scene.data)
    before = validate_partition(targets)
    bays = _bays(scene, targets)
    for floor in (0, 8, 16):
        if sum(bay['floor'] == floor for bay in bays) < 4:
            raise ValueError(f'Parking level {floor} has fewer than four unobstructed bays')
    changed = []
    for mesh in targets:
        floor = mesh['vertices'][0]['y']
        stripes = [stripe for bay in bays if bay['floor'] == floor for stripe in bay['stripes']]
        if _paint_mesh(mesh, stripes):
            changed.append(mesh['id'])
    after = validate_partition(targets)
    for identity, total in before.items():
        if abs(total-after[identity]) > AREA_TOLERANCE:
            raise ValueError(f'Parking floor area changed: {identity}: {total} -> {after[identity]} m²')
    return dict(bays=[{k: v for k, v in bay.items() if k != 'stripes'} for bay in bays],
                changedMeshes=changed, areaBefore=before, areaAfter=after)
