# sshj / BouncyCastle 反射使用，keep 关键类
-keep class com.hierynomus.sshj.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.i2p.crypto.eddsa.**
# Android 无 GSS-API/Kerberos，sshj 的 GSS 认证不会走到
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.serverprobe.manager.** { *** Companion; }
-keepclasseswithmembers class com.serverprobe.manager.** { kotlinx.serialization.KSerializer serializer(...); }
