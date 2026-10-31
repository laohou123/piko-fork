# Keep this file narrow. Android manifest components are kept by the Android
# Gradle Plugin, and the app calls its Kotlin Serialization serializers
# directly, so package-wide keep rules would only reduce R8 optimization.

# 异常类名不混淆：混淆后日志与报错里只剩 "wb6" 这类短串，看不出到底是什么错。
# 只保留类名（keepnames），不保留成员，对体积几乎没有影响。
-keepnames class * extends java.lang.Throwable
-keepnames class * extends java.lang.Exception
-keepnames class * extends java.lang.RuntimeException
