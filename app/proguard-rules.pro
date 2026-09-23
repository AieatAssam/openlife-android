# Keep the line/source metadata needed to interpret mapping.txt without
# collecting user content or adding any crash-reporting service.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SQLCipher reaches the bundled native library through these Java bridge
# classes. Keep the JNI names and methods stable for the encrypted vault.
-keep class net.zetetic.database.** { *; }

# Room constructs the generated database implementation by name. The Room
# consumer rules cover most generated code; this project-owned rule retains
# the generated implementation that opens the SQLCipher-backed database.
-keep class org.openlife.vault.storage.**_Impl { *; }

# The optional coroutines debug agent is absent from release runtime, but its
# optional references can otherwise become missing-class warnings under full
# mode. No debug agent is shipped by this rule.
-dontwarn kotlinx.coroutines.debug.**

# P2-03 must extend this file with the exact JNI keep rules for the selected
# open-source OCR engine when that engine lands. C0/P0-06 does not invent
# those classes or their reflection surface early.
