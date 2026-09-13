# Historical campaign source, layout revision 1

Captured on 2026-09-13 before replacing the five small campaign arenas with three
large maps. JSON files were copied from `src/main/resources/config/`; Python sources
were copied from `src/tools/`. They retain the original coordinate data, procedural
construction rules, local material references and scene provenance.

These files are source history only. They are not loaded by the game, copied into
the runtime asset catalogue, or executed by Gradle. Current authoring lives at
`src/tools/author_campaign_arenas.py` and `src/tools/author_arena_art.py`.

Dead Air Yard keeps its original presentation resources and original procedural
details in `ArenaFactory`. Its current schema revision adds explicit metadata only.
