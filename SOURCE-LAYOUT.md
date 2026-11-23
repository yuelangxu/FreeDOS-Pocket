# Source layout

The `source/` tree is the browsable part of the corresponding source:

- `source/engine/engine/project/` contains the Android application integration,
  Java source, JNI integration, build recipes, and public packaging files.
- `source/engine/engine/patches/` contains the maintained QEMU, SDL2, GLib,
  Pixman, and libffi patches.
- `source/freedos/freedos/README.txt` and `manifest.json` describe the exact
  unchanged FreeDOS guest-source collection.

The original upstream source archives are deliberately not committed to Git:
some individual archives exceed GitHub's ordinary 100 MiB Git-object limit,
and the complete FreeDOS collection is over 1 GiB. The full, byte-preserved
archives are attached to the `v0.3.17` Release alongside the APK. They are the
corresponding source materials; the browsable tree here contains the project
files that can be reviewed directly in GitHub.

Do not treat the APK, disk images, private signing material, or personal game
files as source. None of those are committed to this repository.
