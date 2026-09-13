# Heavy soundtrack and menu audio

The menu, four arenas and three campaign bosses use eight independent recordings by **Alexander Nakarada**, licensed by the author under **Creative Commons Attribution 4.0 International (CC BY 4.0)**. Sources were acquired from CreatorChords on 2026-09-13. The grant and attribution text are retained from each creator page; this credit does not imply endorsement.

| Use | Recording / creator page | Runtime path |
|---|---|---|
| Menu and garage | [Riffs](https://creatorchords.com/music/riffs/) | `audio/music/menu.wav` |
| Dead Air Yard | [Metal Interlude](https://creatorchords.com/music/metal-interlude/) | `audio/music/dead-air-yard.wav` |
| Construction 17 | [Construction](https://creatorchords.com/music/construction/) | `audio/music/construction_17-normal.wav` |
| Construction boss | [The Collapse](https://creatorchords.com/music/the-collapse/) | `audio/music/construction_17-boss.wav` |
| Neon Zero | [Circuits](https://creatorchords.com/music/circuits/) | `audio/music/neon_zero-normal.wav` |
| Neon boss | [Chaotic](https://creatorchords.com/music/chaotic/) | `audio/music/neon_zero-boss.wav` |
| Euphoria Park | [Hall of the Metal King](https://creatorchords.com/music/hall-of-the-metal-king/) | `audio/music/euphoria_park-normal.wav` |
| Euphoria boss | [Bloodmoon Ritual](https://creatorchords.com/music/bloodmoon-ritual/) | `audio/music/euphoria_park-boss.wav` |

## Offline preparation and distribution

`src/tools/assets/audio/music/originals` preserves the original MP3 bytes. `evidence` preserves each creator page with its free CC BY 4.0 grant. `sources.json` records the exact MP3 URLs, acquisition date and source/page hashes. `provenance.json` binds every output to that source, license evidence and the importer SHA-256. The runtime contains the eight prepared WAV files and small manifests; original MP3s and HTML pages remain outside the runtime.

`src/tools/import_menu_music.py` is an explicit authoring operation with Python/NumPy and FFmpeg; `--ffmpeg` identifies the executable. It has no download code. Preparation uses FFmpeg `loudnorm` with target -16 LUFS, -1.5 dBTP and LRA 11, 50 ms windows to remove inactive entrance/release, a cyclic 125 ms crossfade, DC removal, a 3 ms de-click and a 0.84 peak ceiling. Stereo PCM is rounded to signed little-endian 48 kHz / 16-bit WAV. The manifest records exact trim times, frames, decoder/NumPy versions, measured loudness and safety gain. Complete active musical sections are retained: durations are about 149-345 seconds.

Normal Gradle builds use `GenerateAudio` to validate and copy prepared WAVs. They neither invoke FFmpeg nor download assets. Arena metadata owns battle music paths; `AudioConfig` owns menu music and the workshop bed. Each recording begins at zero when selected; unrelated normal/boss compositions have independent lengths. Menu music stays alive between front-end pages. Runtime mixing and pause behavior are documented in [AUDIO_DESIGN](AUDIO_DESIGN.md).

The five UI cues (`ui-nav`, `ui-change`, `ui-confirm`, `ui-back`, `ui-vehicle`) and 16-second mono `menu-ambience` are original deterministic mechanical synthesis in `GenerateAudio.java`: inharmonic metal modes, latch noise, transformer/fan hum and filtered air. They contain no external samples. `audio/menu-provenance.json` records source/output hashes. The ambience is its own SFX-controlled looping bed, outside the one-shot effects list.

Victory, defeat and draw use short **Riffs** guitar/drum excerpts, mono fold, filtering and release envelopes, plus the existing CC0 metal impact. Defeat adds continuous tape slowdown. Durations remain 3.6, 2.6 and 2.0 seconds. `audio/result-provenance.json` records source/output hashes and modifications.

## Preserved history and checks

The replaced Metalmania originals, six procedural campaign WAVs, score/provenance and generators are preserved in `src/tools/assets/history/music-before-nakarada`, outside runtime. Earlier retired arenas remain in `src/tools/assets/retired-campaign-20260913`. `GenerateAudio` and incremental resource processing remove old runtime paths; there is no fallback or same-length/seek contract.

`AudioAssetsTest` checks eight unique recordings, independent lengths, stereo energy, PCM format, entrance/end energy and seam continuity. `VerifyAssets` binds packaged PCM to sources, recipe and license evidence; it verifies preserved procedural history and original menu cues. These are technical checks. Track selection and the final game mix remain **NEEDS_CREATIVE_REVIEW** until the owner listens.

Retain the complete track list, creator links, modification notice and [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) when redistributing the music or game. See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).
