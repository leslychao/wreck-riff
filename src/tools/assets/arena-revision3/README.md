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

The model generator owns each hero roof's vertices; the same placed high-detail mesh
is the rendered roof and its static collision shape. Named box proxies retain useful
authoring dimensions but have `collision:false`; they are omitted from the renderer.
Ground and raised road surfaces use the canonical indexed triangles independently.

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
