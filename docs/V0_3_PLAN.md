# Wreck Riff 0.3 — approved implementation contract

Owner approved 2026-09-09. Supersedes conflicting 0.2 behavior; preserve existing work.
Freeze and Shield remain. Stun and Pulse are removed completely from runtime, AI,
config, state, input, presentation, audio, tests and current documentation. Original
procedural source provenance remains archived. Java21/jME3.8.1/Minie9.0.3 and one
fixed-step PhysicsSpace remain. HP, damage, weapon cadence/ammo/radii, current music
and control timings are unchanged except the explicitly added blast response below.

## Physical blasts

- Unified rocket/mine/napalm-impact processing in CombatSystem; PhysicsWorld applies
  real linear/angular impulse. No impulse from ongoing fire damage.
- Maximum horizontal/upward delta speed (m/s): Homing4/1, Power6/2, Mine4/5, Napalm2/0.5.
- Direct rockets have full force; radial force falls linearly with existing hull
  distance/radius and obeys existing LOS/surface barriers. Under-centre mine has zero
  horizontal vector, not an arbitrary fallback direction. Use actual hit point for
  direct contact and closest hull point for splash torque.
- Aggregate each target's tick impulses. Maximum added horizontal speed9, upward6,
  angular speed2rad/s. Own blasts multiply0.5; Shield multiplies0.3; spawn protection
  blocks. Freeze retains worldXZ+rotation constraint; Y/gravity remain free.
- Replace the old horizontal-only push path; WorldQuery passes linear/angular
  impulses, PhysicsWorld alone changes native bodies. Camera shake is optional feedback.

## Weapon and control effects

- Keep MG hitscan/damage/spread/cadence. Remove full-length static ray rendering.
  Every shot has a brief muzzle flash, alternating barrels; every third shot has a
  travelling tracer<=1.5m, clamped to actual endpoint. Metal sparks/static-surface dust,
  none on miss. Impact cosmetics follow visual arrival; damage remains once per shot.
- Extend GameEvent with origin/normal for trajectory/contact evidence. SHOT.position
  is endpoint, origin actual muzzle. IMPACT shares the shotID, point/normal, target ID
  or-1 for static. Non-contact events use a seven-argument convenience constructor.
  Add SHIELD_HIT and SHIELD_ENDED; remove PULSE/STUN. FREEZE only on accepted control;
  CONTROL_ENDED kind=freeze for expiry/cleanse, not teardown. SHIELD_ENDED natural
  expiry only. SHIELD_HIT maxone per12ticks/car, includes contact point/normal and
  incoming damage value (0 for blocked freeze). Dedupe by(type,eventID,subjectID).
- Freeze: compact cold projectile/trail, launch sound, accepted ice impact/sound;
  frost follows vehicle for full2s while silhouette/livery/damage remain legible;
  expiry/cleanse ice breakup particles/sound. Rejected freeze leaves no frost.
- Shield: translucent vehicle-sized shell, localized hit flare/sound maxone100ms/car,
  separate start/end sounds. Preserve2.5s duration,16s cooldown,70% damage reduction,
  Freeze cleanse and existing3s control immunity. Freeze10s cooldown, weapons remain usable.
- Status visuals follow authoritative ticks; pause stops them. Death/menu/retry clean
  resources without stale events, tails or repeated activations. Keep bounded batch/pools.

## Recorded sound

- Approved CC0 inputs: Free Firearm Sound Library (OpenGameArt the-free-firearm-sound-library)
  and rubberduck25CC0bang/firework(OpenGameArt25-cc0-bang-firework-sfx). Vendor selected
  raw inputs/licenses/source/hash/transforms; no build/runtime downloads.
- Replace synthesized foundations for MG, rocket launch, blasts, metal hits/destroyed.
  >=3 variants for major repeating shots/hits/blasts; no immediate same variant.
  Pre-bake attack/body/debris layers into one48kHz16bitmono WAV per voice. Power/mine
  heavier than Homing, napalm distinct fire. Current Metalmania remains.
- Keep AudioDirector,32-source budget, atomic pauseAll/resumeAll, clipping/headroom
  checks. Audition first examples separately/in game before scaling full asset processing.
  Replace obsolete outputs/generator routes; preserve historical recipes as provenance.

## HP appearance

- Prebuilt five states: hpFraction>.75 intact; >.5 light scratches/dents; >.25 dented
  panels/cracked glass/broken lamps; >0 strong dents/soot/dense smoke;0 charred wreck.
- Visible deformation<=.18m, collider/wheels/handling unchanged. Only switch on stage
  change; repair reverses stage. Materials isolated per car; frost overlays damage.
- Replace old smoke-at-one-third behavior. Wreck remains3s then disappears, no scaling
  to zero. Prepared stages preferred to per-frame mesh deformation/physical detached parts.

## Input/migration

- Keyboard:1Homing,2Power,3Mine,4Napalm,Q/Ecycle,FShield,ZFreeze,RholdRecover,Vrear,
  LMBMG/RMBselected. No Ctrl chords, no action on X. Fresh press only.
- Gamepad:A Shield,DpadUp Freeze,DpadLeft/Right cycle. Menu navigation unchanged.
- VehicleCommand replaces boolean special with nullable WeaponType directWeapon in
  the same constructor position. Direct selection wins over cycle, before firing;
  Shield wins over Freeze in one tick. Clear edges on pause/focus/retry/menu.
- Settings/gamepad schema3: preserve video/volume/custom keys/buttons, old Pulse
  key/button becomes Shield; remove modifier binding. New defaults only if free,
  otherwise disabled with actionable Controls warning. Preserve/read older schemas
  via explicit one-time migration and backup. HUD actual bindings/two cooldowns/status.

## Verification/delivery

- Unit + native direct/off-centre/under-car/LOS/ramp/air/combined/shield/freeze impulse
  checks including caps; input edge/order/migration/conflicts; HP stages/heal/isolation;
  tracer and hit geometry; audio provenance/variations/clipping/pause/source cleanup.
- Run test,physicsTest,verifyAssets. Real-window full match+20Retry and600s benchmark
  after30s warmup1920x1080,VSyncoff,audioon:p95<=16.7ms,p99<=25ms,workingSet<=1.5GiB.
- Windows0.3.0ZIP withJava/checksum/reports; real HP-stage captures and short audiovisual
  demo of hits/Freeze/Shield. Owner judges creative feel; actual gamepad remains manual
  unless hardware is available. No publish, commit, FEEL_APPROVED or MVP_ACCEPTED claim.

## Owner refinement — impact intensity and immediate firing (2026-09-09)

This refinement supplements the approved implementation and is part of its acceptance.
It changes presentation intensity and input responsiveness, not weapon balance.

### Confirmed weapon UX

- Keep the current selection-and-fire scheme: 1/2/3/4 select Homing/Power/Mine/Napalm,
  Q/E cycle, RMB fires the selected weapon, LMB fires the independent machine gun.
  Selection keys must not fire weapons. Do not implement the discussed direct-cast
  weapon hotbar or an all-weapons salvo action. Space remains handbrake; Freeze/Shield
  bindings and settings migration remain as specified above.
- MG and the selected weapon may still fire together. Switching between ready weapons
  while holding RMB uses the newly selected weapon on the next consumed simulation tick.

### Stronger contact, explosion and damage feedback

- Creative target: a brutal, physical rock-show impact, like smashing a guitar against
  the stage. Make the moment a projectile strikes the car clearly more forceful than
  the current presentation; preserve the readable industrial style and vehicle silhouette.
- At a real car contact, combine a short local white-hot/orange flash, directional metal
  sparks, paint/glass/metal fragments, and a short smoke/dust burst. Use the actual
  contact point and normal; car-hit debris must visibly originate from the struck body.
  Surface hits use the arena's dust/debris treatment; a miss never creates an impact.
- Initial tuning: local impact flash about 40–60 ms, sparks about 150–350 ms,
  cosmetic fragments about 0.4–0.9 s. Explosion fireball/expansion has a fast attack
  and gives way to a darker smoke/debris tail, rather than remaining a uniform glow.
  Power/mine impacts should be more forceful and larger than Homing; MG hits are short,
  sharp metal strikes and must not flood the view during sustained fire.
- Strengthen muzzle flashes and readable short tracers without restoring a continuous
  beam. Strengthen the immediate body-hit response alongside the persistent HP damage
  stages: fresh chips/debris on impact, then visible dents, cracked glass, broken lights,
  soot and dense critical smoke according to HP. Repair still reverses the HP stage.
- Audio emphasizes the attack, low-frequency impact and metallic breakup, using the
  approved recorded sources/variants and prebaked single-voice layers. Do not merely
  turn everything up; retain headroom, source limits and distinction between weapons.
- Existing native blast impulses remain authoritative. Camera shake may reinforce the
  contact through the user's shake setting. Do not add hit-stop, input suppression,
  stronger damage, new physical detached parts, or a second physical reaction owner.
- Implement through CombatVisuals, VehicleVisual/damage stages and AudioDirector;
  retain bounded pools, contact deduplication, pause/death/retry cleanup, tracer/contact
  synchronization, existing deformation limits and the performance acceptance limits.

### No artificial selection-to-shot delay

- Current code inspection: CombatSystem.beginTick selects the weapon before calling
  acceptWeapon in the same tick; no switch-delay timer was found. Each weapon's own
  cooldown is a legitimate readiness constraint and must not be reset or bypassed.
  Reproduce the reported delay before attributing it to a specific cause.
- A ready weapon with ammunition must fire in the same simulation tick that consumes
  its selection and fire input. Its launch flash/sound must accompany the accepted shot,
  without waiting for a selection animation, the old weapon's cooldown or target lock.
  Preserve projectile travel time and the existing cosmetic MG contact-arrival timing.
- InputSystem currently derives firing only from held button state. A press and release
  both received before consume() can therefore be lost. Retain a fresh observed fire
  press until the next simulation consumption for mouse and gamepad, alongside held
  firing; consume it once and clear it on pause/focus/menu/retry. Do not queue a rejected
  shot for later cooldown completion or replay it after a pause.
- Add regressions for selection+fire in one tick, ready-weapon selection while RMB is
  held, switching away from a cooling weapon, quick press/release between ticks, repeated
  consumption without duplicate shots, and input clearing. Check launch feedback in a
  real window; distinguish input/launch delay from the projectile's travel to the target.
- Update the recorded demo with clear close-up car impacts, sustained MG, heavy rocket/
  mine hits and damaged-body stages. The owner judges the stronger impact feel after
  seeing/hearing it; automated tests do not constitute creative approval.
