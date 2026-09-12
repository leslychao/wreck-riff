# OpenAL Soft source and library replacement

Wreck Riff uses OpenAL Soft 1.24.1 as a separate dynamically loaded Windows x64
library, through the unmodified LWJGL 3.3.6 bindings. OpenAL Soft is distributed
under the GNU Library General Public License version 2 or later; see COPYING.
The BSD-3Clause, LICENSE-pffft and fmt-LICENSE notices cover included components.

The complete source tree is supplied in
`../sources/openal-soft-1.24.1-90191edd.tar.gz`, at commit
`90191edd20bb877c5cbddfdac7ec0fe49ad93727` of
https://github.com/LWJGL-CI/openal-soft . The source includes its CMake files,
headers, embedded dependencies, platform build instructions and CI recipes.
`../source-index.json` binds this archive to the original LWJGL JAR and native
DLL by SHA-256 and to the DLL's embedded `.git` provenance entry. Wreck Riff has
not modified these source or binary files. Compiler output need not be byte
identical when rebuilding with a different supported toolchain.

You may replace this library with a compatible modified version and debug that
modification for your own use. This package does not restrict those LGPL rights.
The Java application and bindings remain available as ordinary, unmodified JAR
files; replacing OpenAL does not require recompiling the game or editing a JAR.
The separate project and third-party components retain their respective terms.

## Build a compatible library

Extract the source archive. Follow its README.md and appveyor.yml, using CMake
and a Windows x64 C++ toolchain. The retained upstream Windows recipe uses
Visual Studio 2022 and enables WASAPI, DirectSound and WinMM. Build the shared
implementation (`soft_oal.dll`), not the optional OpenAL routing DLL. The
result must provide the OpenAL/ALC API and extensions used by LWJGL 3.3.6.
The tools needed to compile a modified library are not needed to play the game.

## Select a replacement in the packaged EXE

1. Exit Wreck Riff. Work in your own writable copy of the fully extracted game.
2. Keep a backup of `app/WreckRiff.cfg`. Put your compatible x64 DLL in a folder
   you control, for example `C:\Games\WreckRiff\openal-override\OpenAL.dll`.
3. Add one line under `[JavaOptions]` in `app/WreckRiff.cfg`:

   ```text
   java-options=-Dorg.lwjgl.openal.libname=C:\Games\WreckRiff\openal-override\OpenAL.dll
   ```

   Use the actual absolute path. Keep the setting on one line. LWJGL's property
   selects only OpenAL; do not change the general native-library search path.
4. Start `WreckRiff.exe` normally. Confirm the selected DLL path in the process's
   loaded modules, and check that the sound devices and game audio work. A failed
   or incompatible override is an error, not evidence that the original DLL was
   successfully replaced.
5. To restore the original library, exit the game and restore the original config
   or remove the added line. The original native JAR remains intact.

The relevant upstream implementation is Configuration.OPENAL_LIBRARY_NAME:
https://github.com/LWJGL/lwjgl3/blob/3.3.6/modules/lwjgl/core/src/main/java/org/lwjgl/system/Configuration.java

This document explains the supported replacement mechanism. The material
verification report checks source and notice integrity; it does not claim that
every user modification works or that a human release review has occurred.
