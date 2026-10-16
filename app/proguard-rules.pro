# LuaJava calls back into Java through JNI and reflection.
-keep class party.iroiro.luajava.** { *; }
# quickjs-kt bridges through JNI.
-keep class com.dokar.quickjs.** { *; }
# Remote WorkManager worker is instantiated by class name in the :script process.
-keep class io.github.rscipher001.launcher.script.ScriptWorker { *; }
