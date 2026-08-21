# R8 keeps for the release build. Almost nothing is needed here: the app uses no
# reflection, no serialisation and no addJavascriptInterface, and the libraries that
# do reflect (androidx.webkit onto Chromium's boundary interfaces, Media3, and
# subsampling-scale-image-view) each ship their own consumer rules inside the AAR,
# which R8 merges in automatically.

# A crash people report by hand is the only crash Gander ever sees: there is no
# analytics and no reporting SDK, so a stack trace arrives pasted into a GitHub
# issue by whoever hit it. Obfuscated line-free frames would make those reports
# useless. Keeping these two attributes costs a few kB and leaves every frame
# resolvable through the mapping file for that build.
#
# The AAB carries its own mapping, so Play deobfuscates Android vitals with no
# further work. The APK on GitHub does not, which is why the mapping.txt beside
# it in build/outputs/mapping/release/ has to be kept per release: without the
# file for that exact build, a trace from that build cannot be read.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
