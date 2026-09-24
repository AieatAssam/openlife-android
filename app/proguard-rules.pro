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

# P2-03 / ADR-0003: tesseract4android ships no consumer rules. Its native code
# looks up these Java classes, fields and methods by name (for example the
# native handles and the progress callback), so R8 must keep them intact or
# OCR fails only in release builds.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
