# Corresponding sources and additional native-library notices

The verified output materials are copied to the Windows package's `licenses`
directory. The `sources` archives are distribution materials, not classpath
resources: they must not be added to the application JAR. Builds verify the local
files offline and never download source, assets or license texts.

`source-index.json` records exact upstream commits, download URLs, file sizes,
SHA-256 values, retained notice hashes and their relationship to the selected
OpenAL native JAR/DLL and Microsoft JDK. In the source repository, index schema 2
also records ordered archive parts of at most 32 MiB, with a checksum for every
part and the complete archive. The verifier checks and concatenates these parts
without fetching anything. Its output `source-distribution-materials` contains
complete archives and a schema 1 index describing the actual packaged files;
repository parts are not included in the game ZIP. Complete source archives are
supplied with the package; there is no promise to supply them at a later time.

For OpenAL library rebuilding and replacement, read `openal/REPLACEMENT.md`.
For the Java runtime's source and jlink configuration, read `runtime/README.md`.
Existing game-asset and other dependency notices remain in
`THIRD_PARTY_NOTICES.md`, `assets`, and the other files in this directory.

`reports/source-distribution.json` in the game package is a technical integrity
report. `SOURCE_AND_NOTICE_MATERIALS_VERIFIED` means that the supplied files
matched their recorded source/binary identities. It is not a grant of a new
license, legal approval, artistic acceptance, or permission to publish the game.
