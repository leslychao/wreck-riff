# Wreck Riff 0.2 — approved implementation contract

Approved by owner on 2026-09-09. This supersedes conflicting 0.1 defaults in MVP_SPEC.
Priority: actual TM4-inspired visuals with a sharp modern image, heavy guitar music,
then complete combat revision. No engine replacement, second PhysicsSpace, progression,
accounts, purchases or publishing. Original Rivet and Dead Air Yard remain our designs.
Free redistributable assets stored locally are explicitly authorized. Preserve existing work.

## Presentation and resources

- Actual framebuffer resolution, MSAA4, trilinear mipmaps, anisotropy8 clamped to GPU.
- New installs: fullscreen1920x1080 if available, otherwise nearest smaller supported mode.
  Preserve existing video settings and use the current10-second confirmation for changes.
- Keep Lighting.j3md. Add meaningful UV, tangents, diffuse/normal/specular textures.
  Use Poly Haven asphalt_02, cracked_concrete, metal_plate_02, blue_metal_plate,
  rusty_metal_03 at2K. Convert roughness into a suitable specular mask; do not bind ARM
  as a Phong specular map. Correct sRGB for color and linear normal/data textures.
- Rivet: modeled bevels, rounded arches/wheels, grille, armor, brackets, glass/rubber,
  readable authored atlas, five distinct liveries. Keep collider size and wheel positions.
- Yard: worn industrial surfaces, garage doors/panels, lattice mast, pipes, wires,
  signs and distant industrial skyline. Keep navigation and entrances clear.
- Warm low directional sun plus cool fill; real3-cascade2048 shadows. Remove old oval
  shadow implementation. No motion blur, DOF, pixelation, or excessive ambient light.
- Original procedural sources/provenance remain as required by project rules, but
  superseded runtime resources and duplicate rendering/playback paths are removed.
- Local Roboto Condensed regular/bold OFL font, high-quality atlas generated from
  bundled font files. No system font dependency. Rework HUD with legible indicators.
- Music: Kevin MacLeod Metalmania, ISRC USUAN1700023, creator CC BY4.0 source.
  Download, decode to48kHz16-bitstereo, inspect/listen, create appropriate loop.
  Retain streaming/pause/source caps. Old generated song is not packaged or selected.
  Preserve attribution/license/source/hash/transforms. Builds never download assets.

## Driving, resources and match start

- Positive VehicleCommand.steer means driver's right. Translate sign once in native
  steering adapter and use for handbrake yaw. Correct AI angle/right-vector convention.
- Authoritative max HP: player800, each bot400. Repair25% maximum (200/100). HUD,
  smoke and AI use HP fraction. Existing six-minute match limit remains.
- Completely remove overheating, cooling, fields/config/guards/events/SFX/HUD/tests.
  Machine gun retains3damage,10shots/sec, no ammunition or heat limitation.
- Delete COUNTDOWN. Settle suspension inside LOADING using360 fixed native steps
  without advancing combat/AI/hazard/resources/match ticks; reset accumulator/input
  and enter RUNNING immediately. All special/ability cooldowns start ready.

## Weapons

WeaponType = HOMING, POWER, MINE, NAPALM. Q/E cycle; RMB uses selected weapon.
Unify ammo/cooldown by WeaponType instead of parallel individual resource fields.
LMB machine gun and F Feedback Pulse remain independent of selected weapon.

- Homing: existing32direct OR24splash and existing ammo/cadence/flight.
- Power: existing50direct OR40splash and existing ammo/cadence/flight.
- Mine: supported free placement3m behind; support ray<=3m, normal.y>=0.6,
  unobstructed path to placement. Arm0.6s, enemy hull trigger distance3m with LOS,
  blast max70damage/radius6 with existing falloff and visibility rules, life25s.
  Initial3/max6ammo; cooldown1s. Owner does not trigger, but takes half splash.
  Not shootable, no chain reactions. Invalid placement/cap does not spend resources.
- Napalm: horizontal28m/s, initial vertical10m/s, gravity18, ttl3s, swept collision.
  First hit creates max20 radial damage using existing LOS/owner rules. Find static
  support<=4m below hit offset outward0.05m; normal.y>=0.6. Without support no patch;
  TTL miss disappears. Valid fired miss does not refund ammunition.
  Fire radius5m/life4s/30DPS; initial3/max6ammo; cooldown1.4s.
  Surface identity/normal and visibility clip visuals and damage to hit surface;
  no damage through walls/floors. Per-target7.5damage each30ticks (0.25s), maximum
  across overlapping zones (not additive); lowest stable zone ID owns attribution.
- Pulse: radius12m,90damage, max4m/s outward impulse,12s cooldown, ready at start.
- Caps:64 airborne projectiles INCLUDING freeze+napalm;10mines and2per owner;
  active fire zones + slots reserved by airborne napalm <=6. Reserve at launch,
  release on miss/expiry/cleanup; cap denial never spends ammunition/cooldown.
- New mine pickups at existing nodes14(-70,0,0) and9(30,0,55); napalm at37(36,6,0)
  and1(-30,0,-48). Each adds2 ammo, respawns20s. Existing other pickups remain.

## Control abilities

AbilityId = NONE, FREEZE, STUN, SHIELD. Available immediately to player and bots.
No skill tree, loadout selection or extra energy resource.

- Ctrl+W / R3+DpadUp: FREEZE straight swept bolt50m/s, ttl1.2s/range60m,
  radius0.3m; no damage,2s immobilization,10s cooldown. Weapons remain usable.
  Drive/turbo/handbrake/manual recovery disabled. Gravity continues in air.
- Ctrl+A / R3+DpadLeft: STUN cone16m, halfangle35deg, LOS to target hull;
  no damage,1s disable driving and attacks,12s cooldown. Inertia/gravity remain.
- Ctrl+D / R3+DpadRight: SHIELD2.5s,16s cooldown, cleanse current CC, block newCC,
  reduce weapons/fire/ram/hazard damage70%. Does not reduce recovery/OOB penalties.
  Shield is allowed while controlled and processed before same-tick hits.
- CC does not refresh/stack while active.3s shared immunity after expiry or cleanse.
  Simultaneous CC resolves stably: STUN before FREEZE, then stable event ID.
  New CC resolves after physics along with hit outcomes; only survivors get effects.
- New direction press while modifier already held -> one intent. WASD movement
  remains live; modified Dpad suppresses weapon cycling. Pause/focus/menu/retry clear
  edges. Same-tick priority SHIELD > STUN > FREEZE. Do not repeat held input.
- VehicleCommand adds final AbilityId ability; special and weaponDelta remain edges.
  withoutEdges clears ability. withoutAttacks preserves SHIELD only.
- VehicleState owns durations; CombatSystem owns acceptance/resources/hit resolution;
  MatchRuntime masks commands. Defensive intents must bypass attack suppression.
- PhysicsWorld owns temporary single-ended New6Dof: lock worldXZ + rotation at
  current pose, free worldY. At entry zero XZ/angular velocities; never restore old
  velocity. No per-frame teleports or second space. Remove on expiry/cleanse/death/
  recovery/cleanup. Native tests must prove ramp/air/impact/release behavior.
- Extend support query with stable static surface ID, point and normal.
- AI uses visible targets for freeze, close cone for stun, observed incoming threat
  for shield, pursuer for mines, lead on ground target for napalm. No new hidden
  omniscience. Do not advance stuck timer during forced CC.

## Integration, migration and verification

- Migrate settings and gamepad profile to version2 preserving old video/volume and
  bindings. Conflicting newly added bindings stay disabled with actionable Controls
  notice; never silently replace user keys. Default R3 modifier uses GLFW button10.
- New HUD: max/currentHP, turbo,4ammo slots, Pulse,3abilities/status durations,
  radar/time/rivals. No heat/countdown UI. Distinct weapon/control audiovisual cues.
- Update spec/decisions/register/acceptance and conflict in project asset rule.
- Tests: driver-relative directions/headings/reverse/handbrake/AI; HP/repair/fractions;
  continuousMG beyondoldlimit; tick0settling; allweaponresource/cap/LOS/height cases;
  overlappingfire; same-tickshield/CC/death; inputedge/hold/reset/profilemigration;
  root gravity/ram/blast/ramp/release, no native leaks on retries.
- Run test, physicsTest, verifyAssets; packaged real-window match +20restarts;
  negative-config launch. Capture720p/1080p actual arena/car/garage/ramp/HUD.
- New-build10min benchmark after30swarmup,1080pVSyncoff+audio; p95<=16.7ms,
  p99<=25ms, working set<=1.5GiB. Preserve raw errors and stage boundaries.
- Package Windows0.2.0ZIP with Java/checksum, reports and notices. No publication.
- Owner alone approves feel/music/visuals. Functional tests do not grant
  FEEL_APPROVED or MVP_ACCEPTED. Show actual visual scene early before scaling polish.
