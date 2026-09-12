# Included Microsoft OpenJDK runtime

The Windows game includes a reduced runtime from Microsoft Build of OpenJDK
21.0.11+10-LTS, Windows x64, implementor version Microsoft-13877171. The original
JDK `release` file is preserved verbatim here as `microsoft-jdk-release.txt`.
The generated runtime's separate `runtime/release` file records its actual
module closure; it is not a substitute for the original build metadata.

The full corresponding source tree is supplied in
`../sources/microsoft-openjdk-21.0.11+10-87e312d6.tar.gz`:
https://github.com/microsoft/openjdk-jdk21u/commit/87e312d6724906796bbeb1ce86b7598183e1fab9

This commit expands the original JDK's `SOURCE=".:git:87e312d67249"` identifier.
The archive includes the JVM's native sources, Java sources, configure/make
scripts, build documentation and notices. Its checksum and the build metadata
are recorded in `../source-index.json`. This is the complete repository archive,
not the Java-only `lib/src.zip` included in a developer JDK. Source is delivered
with the binary package; this document is not a future written source offer.

The Microsoft runtime is GPLv2 with the applicable Classpath Exception. Preserve
LICENSE, ADDITIONAL_LICENSE_INFO, ASSEMBLY_EXCEPTION and the entire packaged
`runtime/legal` directory, including component-specific notices. The exception
does not assign the game's own code to the GPL. See the retained notices and:
https://learn.microsoft.com/en-us/java/openjdk/faq

## How this reduced runtime is made

Wreck Riff does not patch or recompile OpenJDK. Its Windows package script invokes
the selected Microsoft JDK's jpackage, which invokes jlink with these root modules:

```text
java.base,java.desktop,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.jfr
```

The exact equivalent jlink runtime-selection command is:

```text
jlink --module-path <Microsoft-JDK-21.0.11>/jmods --add-modules java.base,java.desktop,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.jfr --strip-debug --no-man-pages --no-header-files --output <new-runtime-directory>
```

`tools/package-windows.ps1` supplies the same `--add-modules` value to jpackage
and `--jlink-options "--strip-debug --no-man-pages --no-header-files"`. The actual
runtime includes transitive modules selected by jlink. To rebuild OpenJDK itself,
follow `doc/building.md` and `configure` in the complete source archive. Matching
the selected Microsoft build requires its documented platform toolchain; a
different supported toolchain is not claimed to produce byte-identical binaries.

The package verifier compares the selected JDK's version, implementor and source
metadata with the vendored record. A JDK change requires refreshing and verifying
the corresponding source materials before another release package is produced.
