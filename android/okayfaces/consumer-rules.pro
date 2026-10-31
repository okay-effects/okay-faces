# okay-faces on Android, for R8 in the app that takes it (shipped in the AAR):
# an app's MobileApp is opened by its class name — kept, with the constructor the shell calls
-keep class * implements okay.faces.mobile.MobileApp { <init>(); }
# what Scala's library and okay-simple name but Android has not (the JVM's management, the desktop): never reached
-dontwarn scala.**
-dontwarn okay.**
-dontwarn java.lang.management.**
-dontwarn sun.misc.**
