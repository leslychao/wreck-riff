# Original campaign music

Five original scores are authored by `src/tools/compose_campaign_music.py`. The script uses Python 3 and NumPy; it reads no recordings, soundfonts, MIDI files or external melodies. Finished 48 kHz, 16-bit stereo WAV files are stored in `src/tools/assets/audio/campaign`. Normal builds verify and copy those files through the existing `GenerateAudio`; they do not run the authoring script or download anything.

| Arena | Score | Tempo | Duration | Musical identity |
|---|---|---:|---:|---|
| construction_17 | Rebar Oath | 126 BPM | 91.429 s | D-based chromatic low guitar riff, metallic offbeats, bell punctuation |
| neon_zero | Last Service Lane | 138 BPM | 83.478 s | E-based syncopation, driving kick, stereo electronic lead |
| euphoria_park | Crooked Midway | 144 BPM | 80.000 s | A-based minor/tritone motif, short carnival organ, displaced accents |
| ash_necropolis | Cinders Remember | 108 BPM | 106.667 s | Low C, half-time passages, minor-second movement, bells and sustained organ |
| doomsday_arena | The Last Broadcast | 132 BPM | 87.273 s | New D-based arrangement combining motifs from the four original campaign scores |

Each score has six eight-bar sections: introduction, lead, riff variation, half-time bridge, lead variation and final refrain. Instruments are synthesized percussion, bass, two detuned/panned distorted plucked-string guitars and a thematic lead. Circular note tails and delays preserve the loop boundary. Normal and boss mixes have identical sample counts and note timing; the boss mix adds double kicks, offbeat guitar and melodic counterpoint.

To intentionally re-author all ten source WAV files, run `python src/tools/compose_campaign_music.py` with a Python environment containing NumPy. This rewrites only that script's campaign outputs. `provenance.json` records the recipe hash, NumPy version, seed, score data, per-file hashes and signal measurements; `score.txt` and `audio-metrics.csv` are packaged with the tracks. The original Metalmania track and all its licensed provenance remain unchanged.

`AudioDirector.startMatch(normalAsset, bossAsset)` opens both streams during loading. `bossMusic(true)` requests a two-second crossfade and starts the prepared boss mix at the current musical position. Repeated requests keep the same stream. Pause freezes both playback and crossfade; resumption does not reset the score. A completed transition stops the outgoing source. All music and effects share the existing 32-source limit and conservative summed-gain headroom. Leaving/retrying a match clears active sources; changing maps disposes the prior streams.

Signal checks and tests cannot establish musical appeal. These scores remain `NEEDS_CREATIVE_REVIEW` until the owner listens in the game, including weapon masking, transitions and repeated loops.
