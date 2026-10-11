# R8 rules for the Wear OS release build (#78).
#
# Nothing app-specific is needed so far: services, receivers and activities are kept through the
# manifest (Data Layer listener, tile and complication services), WorkManager and the Wear
# libraries ship their own consumer rules, and kotlinx.serialization ships rules that keep the
# generated serializers of @Serializable classes (ChunkManifest, Outbox and journal entries).
# Add rules here only for code that R8 cannot see (reflection, JNI, names in resources).
