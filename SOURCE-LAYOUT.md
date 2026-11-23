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
and the complete FreeDOS collection is over 1 GiB. The 0.3.20 engine source
archive is attached to the `v0.3.20` Release. The unchanged FreeDOS collection
remains attached to `v0.3.17`. The browsable tree here contains the project
files that can be reviewed directly in GitHub.

Do not treat the APK, disk images, signing material, or installed DOS data as
source. None of those are committed to this repository.
