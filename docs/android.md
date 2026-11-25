# Android

OpenGP builds Mario Kart Arcade GP 2 for Android arm64 handhelds and phones (Vulkan, Android 10+).
It was written for a Retroid Pocket 6 (Snapdragon 8 Gen 2, Adreno 740).

The APK holds code recompiled from **your own** `main.dol`: build it for your own device and never
publish it. Your disc image stays on the device; nothing from the game is committed.

## Building

On Linux, with the Android SDK and an NDK (r29+), CMake, Ninja, a JDK 17+ and git:

```sh
python3 tools/setup.py --no-dtk          # orig/GGPE02/sys/main.dol from your image in ISO/
ANDROID_HOME=/path/to/sdk OPENGP_ANDROID_VERSION=1 scripts/build_android.sh
# -> out/opengp-android-v1.apk
```

The script builds `gekkorecomp` and `glslang` for the build machine (the cross build runs them),
then the game as `libmain.so` with SDL3 as `libSDL3.so`, then the APK with Gradle (downloaded into
`build/`). It needs `dl.google.com` and `maven.google.com` for Gradle's Android plugin. Raise
`OPENGP_ANDROID_VERSION` for every APK you install: the journal prints it.

The unstripped `libmain.so` of each version is kept in `build/android-symbols/` for reading crash
backtraces (`llvm-symbolizer -e libmain-vN.so 0xOFFSET`).

## Playing

1. Install the APK (allow installs from your file manager).
2. Open OpenGP, choose **Importer l'image du jeu** and pick your `GGPE02` image (`.iso`, `.gcm`, or a
   `.zip` holding one). It is copied into the app's folder
   (`Android/data/org.opengp.android/files/ISO/GGPE02.iso`).
3. **Jouer**. In game, Select + Start opens OpenGP's menu.

Default controls: left stick or D-pad steers, R2 accelerates, L2 brakes, A/R1 item, B/L1 cancel,
Start start, Select coin. They are in `files/user/controls.toml` (see [playing.md](playing.md)).

## Journal

After each session (and after a crash, with a message), the launcher saves
`Download/opengp-journal-<date>.txt`: the version, the device, how the game process ended, the end of
the game's output (`last_run.log`), the controller lines (`[input diag]`), OpenGP's crash and hang
reports, and logcat. **Enregistrer un journal** saves one at any time.

## How the port works

| Part | Where |
|------|-------|
| Cross build: imported host `gekkorecomp` (`OPENGP_GEKKORECOMP`), host `glslang` (`OPENGP_GLSLANG`), the game as `libmain.so`, SDL3 shared | `CMakeLists.txt`, `games/CMakeLists.txt`, `cmake/sdl3.cmake`, `tools/gekkorecomp/CMakeLists.txt` |
| Start-up: `OPENGP_HOME` = the app's external files folder, stdout/stderr to logcat and `last_run.log` | `runtime/app/android.cpp`, `games/common/main.cpp` |
| Reboots (test menu): the arguments go to `restart-args.txt`, the launcher's process starts a new game process | `runtime/os/reboot.cpp`, `runtime/app/android.cpp`, `GameActivity`, `RestartActivity` |
| Bionic: `memfd_create` before API 30, backtraces with the unwinder | `runtime/ppc/memory.cpp`, `runtime/os/crash.cpp` |
| GPU device with only the features the renderer uses; GPU and driver logged | `runtime/video/renderer.cpp` |
| Controller diagnostics, L2/R2 sent as buttons 15/16 used as triggers | `runtime/input/host.cpp` |
| App: launcher (import, journal), game activity (`:game` process) | `android/` |
