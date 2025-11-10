# --- Mantener metadatos de anotaciones/reflection ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions

# --- Moshi (reflect) ---
# Mantener clases con @JsonClass(generateAdapter = true) si más adelante migras a codegen
-if @com.squareup.moshi.JsonClass class *
-keep class <1> {
    <fields>;
    <methods>;
}

# Mantener adaptadores y anotaciones Moshi
-keep class com.squareup.moshi.** { *; }
-dontwarn com.squareup.moshi.**

# --- Kotlin stdlib/reflection (evitar stripping excesivo) ---
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# --- Data classes usadas por Moshi a través de reflexión ---
# (Opcional: si ves problemas en release, descomenta una de estas líneas por paquete/modelo)
# -keep class site.weatherstation.net.model.** { *; }
