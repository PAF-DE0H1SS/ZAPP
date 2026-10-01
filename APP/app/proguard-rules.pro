# Правила R8 для release-сборки.
#
# Compose и Kotlin не требуют специальных правил, но JNI-часть позже будет
# обращаться к нативным методам по имени - имена классов и методов нужно сохранить,
# иначе R8 переименует их вместе с native-библиотекой.

# Нативные методы вызываются из Kotlin по имени, а не через рефлексию.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Модели из kotlinx.serialization: имена полей сериализатора генерирует рефлексией.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}