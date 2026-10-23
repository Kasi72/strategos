-keepattributes Signature,RuntimeVisibleAnnotations
-keep class com.drkkr.strategos.domain.model.** { *; }
-keep class com.drkkr.strategos.domain.framework.** { *; }
-keep class kotlinx.serialization.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.concurrent.**

# Strategos 2.0
-keep class com.drkkr.strategos.domain.** { *; }
-keep class com.drkkr.strategos.llm.** { *; }
-keep class net.zetetic.** { *; }
