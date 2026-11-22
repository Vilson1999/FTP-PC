# ProGuard / R8 aggressive shrinking rules for FTP PC & Apache MINA FtpServer
-dontwarn java.nio.file.**
-dontwarn javax.naming.**
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**
-dontwarn org.springframework.**
-dontwarn org.apache.log4j.**

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
}

# Keep Apache FtpServer command implementations & interfaces used via reflection/SPI
-keep class org.apache.ftpserver.command.impl.** { <init>(); }
-keep class org.apache.ftpserver.ftplet.** { *; }
-keep class * implements org.apache.ftpserver.ftplet.Ftplet { *; }
-keep class * implements org.apache.ftpserver.ftplet.FtpFile { *; }
-keep class * implements org.apache.ftpserver.ftplet.UserManager { *; }
-keep class * implements org.apache.ftpserver.ftplet.FileSystemFactory { *; }
-keep class * implements org.apache.ftpserver.ftplet.FileSystemView { *; }
-keep class org.apache.mina.core.** { *; }
-keep class org.apache.mina.transport.socket.nio.** { *; }
-keep class org.slf4j.impl.** { *; }
