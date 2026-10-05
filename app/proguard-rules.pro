# Reglas ProGuard del modulo app.
# La build de release no usa minify por defecto; si lo activas,
# conserva los nombres necesarios para el engine y las vistas.

-keepattributes SourceFile,LineNumberTable
-keep class com.lumiyaviewer.lumiya.slproto.messages.** { *; }
-keep class com.lumiyaviewer.lumiya.slproto.llsd.** { *; }
