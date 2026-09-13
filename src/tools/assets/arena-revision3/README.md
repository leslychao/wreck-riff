# Authored locations — revision 3

All dimensions are metres. These are original project designs. No external geometry
is used for the architecture. Shared surface textures have their own local provenance.

The three `*-plan.svg` files are the authored top-down plans. Gold roads are raised,
blue roads are underground, pale roads are at ground level, yellow dots are ammunition,
and blue circles are vehicle starts. The three `*-design.json` files contain functional
site passports, actual district polygons, road/surface/nav links and supply distributions.

## Offline preparation

1. `python src/tools/author_campaign_arenas.py` builds the canonical surfaces,
   collision structures, navigation, supply sockets and review routes.
2. `python src/tools/author_arena_models.py` writes the original GLB models and LODs.
3. Run Blender in background with `--python src/tools/author_arena_models.py -- --blend`
   to retain editable `.blend` files imported from those exact mesh sources.
4. `gradlew prepareArenaModels` converts local GLB geometry and named surface materials
   into `.j3o`. Use Microsoft JDK 21. The game never loads GLB or runs these generators.
5. `python src/tools/author_arena_art.py` writes model placements, their collision
   replacement metadata, architectural detail, local light fixtures and mechanisms.
   This command always recompiles fresh canonical geometry and supply from the
   authoring source. It never reads a previously dressed runtime arena. Sidewalk
   subtraction therefore cannot accumulate holes or duplicate IDs across runs.
   JSON and design outputs are published through atomic sibling-file replacement.

   The final pass includes `dress_construction.py`, `dress_neon.py`,
   `dress_parking.py` and `dress_carnival.py`. Their named foreground installations have specific public,
   production or repair functions and retain the verified road envelopes. Carnival
   shopfronts face their nearest approach; circus sectors have stepped supporting
   terraces, the ride core has a drive mechanism, and the depot has open workbenches.
   `test_construction_foreground.py` and `test_carnival_foreground.py` check solid
   footprints against every full driving corridor plus four metres, supply/spawn
   preservation and the presence of foreground near the actual review routes.
   Structural lintels may span a route only above its eight-metre vehicle/camera
   clearance. Roof columns, tent poles and machinery foundations remain real solids.

   Parking bay paint replaces exactly partitioned floor triangles, without decals
   or stacked pavement. Open tunnel ramp cuttings have continuous retaining walls.
   Public park paths use the locally vendored calm `park-paving` material; freshly
   cast frames and piers use `cast-concrete` with matching 1.8-metre UV capture scale
   in the canonical geometry and exported models. Trees retain their original
   six-metre footprint while using closed lobed crowns, branches and a simpler LOD.

The model generator owns each hero roof's vertices; the same placed high-detail mesh
is the rendered roof and its static collision shape. Named box proxies retain useful
authoring dimensions but have `collision:false`; they are omitted from the renderer.
Ground and raised road surfaces use the canonical indexed triangles independently.
The final [structural audit](STRUCTURAL_AUDIT.md) records the nine ceiling systems,
three lake bridges, parking floor shells and interchange bearings, together with
the geometry-based regression checks that cover their connections.

The published driving-camera review exposed bare production fields and a flat
industrial horizon in construction. The final scene therefore adds five specific
production areas: connected curing beds, reinforcement storage, span assembly,
bearing delivery and the aggregate screening inlet. Their source passports record
their function and service approach. Existing pit foundations show successive cage
and pouring stages beside a connected reclaim channel. New production areas leave
the whole road envelope plus four metres and eight metres around pickup sockets.
The industrial continuation beyond the unchanged physical boundary has separate
panel, power, rolling, assembly and curing buildings with loading fronts, grounded
pipe galleries, window bays and stepped roof monitors. It replaces the former
three plain wall blocks without changing roads, support surfaces or combat starts.

## Distinct interiors

- Construction: an open L-frame and courtyard; offset concrete production/discharge
  hall under a sawtooth roof; racked warehouse with a north loading exit.
- Neon: glass atrium passage; turning technical workshop connected to the tunnel;
  parking building with two covered levels and an open upper deck, internal ramps.
- Carnival: polygonal circus arena and backstage; curved ride pavilion around a
  mechanism; workshop/depot with repair bays and a gantry.

The lake has exactly three permanent vehicle bridges; their surfaces and the island
are polygon-clipped at shared junctions. Base terrain is spatially cut under authored
ground-level roads rather than rendered as a competing coplanar sheet.

## Review evidence

`../architecture/review-routes.json` contains all 16 district and all nine interior
routes, with ordered actual road-height coordinates. Native driving checks must visit
the waypoints in sequence: taking a shorter outside bypass is valid AI behaviour but
does not verify the interior. SVGs and source checks do not establish artistic or owner
acceptance; the current status remains `NEEDS_CREATIVE_REVIEW`.
