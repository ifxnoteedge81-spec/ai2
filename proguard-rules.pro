# Rules for when R8 is enabled (isMinifyEnabled = true). Not active in v1.
-keep class com.personal.aicomputer.accessibility.HandsService { *; }
-keep class com.personal.aicomputer.agent.AgentService { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
