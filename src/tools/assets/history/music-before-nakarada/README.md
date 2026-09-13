# Preserved soundtrack before the Nakarada revision

This directory preserves actual local sources at replacement time, including uncommitted campaign work. It is outside `src/tools/assets/audio` and never copied into runtime resources.

- `campaign/`: six procedural recordings, score, metrics and exact provenance.
- `compose_campaign_music.py`: the original authoring recipe referenced by provenance.
- `GenerateAudio.java.txt`: full ancillary/import/result generator before replacement.
- `Metalmania-original.mp3`, `Metalmania-source.wav`, `source.json`: Kevin MacLeod recording under CC BY 4.0 and exact original/PCM hashes.
- `campaign-music.md`: former authoring documentation.

Older removed arenas and statue cues remain in `src/tools/assets/retired-campaign-20260913`. Historical documents retain original paths as evidence, not as a supported runtime workflow. `VerifyAssets` checks original recording/recipe hashes and rejects packaged campaign music at retired paths.

Metalmania by Kevin MacLeod (incompetech.com), ISRC USUAN1700023. Creative Commons Attribution 4.0 International: https://creativecommons.org/licenses/by/4.0/ . Source: https://incompetech.com/music/royalty-free/index.html?Search=Search&isrc=USUAN1700023 . PCM was decoded from MP3; former runtime loop/result edits are described in the archived generator. This attribution does not imply endorsement.
