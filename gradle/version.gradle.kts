val versionPattern = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")
val versionName = rootProject.file("VERSION").readText().trim()
val versionMatch = versionPattern.matchEntire(versionName)
    ?: error("VERSION must contain major.minor.patch")
val (majorText, minorText, patchText) = versionMatch.destructured
val major = majorText.toInt()
val minor = minorText.toInt()
val patch = patchText.toInt()
val versionCode = major * 10_000 + minor * 100 + patch

require(versionCode > 0) { "VERSION must derive a positive versionCode" }

rootProject.extensions.extraProperties["openLifeVersionName"] = versionName
rootProject.extensions.extraProperties["openLifeVersionCode"] = versionCode
