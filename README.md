# Instrumentalizer (Android, offline)
1. Open this folder in Android Studio (Koala+), let Gradle sync, Run.
2. Add your model as app/src/main/assets/model.onnx.
   Expected: input [1,2,441000] float32 stereo @44.1 kHz -> output [1,4,2,441000]
   with stems ordered drums, bass, other, vocals (Demucs-style). Instrumental = drums+bass+other.
   If your model's stem order or I/O differs, edit VOCAL / SEG in Separator.kt.
3. No permissions, accounts, or network are used.
