# sherpa-onnx's JNI code reads config fields and constructs result objects by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# JNA (semantic search, #24): its native dispatch library looks up these classes, fields and methods by name.
-keep class com.sun.jna.** { *; }
-dontwarn java.awt.**
