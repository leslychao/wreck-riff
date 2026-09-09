# Original procedural presentation sources

These UTF-8 source snapshots preserve Wreck Riff's original Java-authored Rivet,
reversible damage generator, bounded combat effects and analytic particle shaders
before the 9 September 2026 art refresh. They are historical authoring evidence,
not compiled runtime alternatives. The canonical implementations remain under
`src/main/java/game/wreckriff/presentation` and `src/main/resources/materials`.

All geometry and analytic effects were authored for this project. No external
models, images, shaders, or network-generated assets were introduced by this refresh.
Existing licensed vehicle texture sources continue to use the project's asset manifest.

`ArenaFactory.java.txt` is the frozen pre-refresh arena/pickup authoring source.
Its old stationary pickups are historical evidence only; runtime pickups now have
one session-owned presentation using the exported models and authoritative events.

The update retains the original Rivet loft/wheel generators, adds five batched
cosmetic kits, merges the profile-aware original boss authoring work, and retains
the same bounded particle/fragment pools. Particle masks gain deterministic visual
variation and adjustable flash intensity. These changes do not change the physics
or weapon damage definitions.
