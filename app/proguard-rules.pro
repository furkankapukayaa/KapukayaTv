# Kapukaya TV - R8 / ProGuard kuralları
# Media3, Room, OkHttp, Coil ve ZXing kendi tüketici kurallarını getirir; aşağıdakiler ek güvenlik içindir.

# Hata raporlarında satır numaraları okunabilsin
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Intent ile Serializable olarak taşınan model ve Room varlıkları
-keep class com.example.data.model.** { *; }
-keep class com.example.data.db.entity.** { *; }
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Media3 TrackSelectionDialogBuilder, AppCompat AlertDialog'u yansıma (reflection) ile arar
-keep class androidx.appcompat.app.AlertDialog$Builder { *; }

# OkHttp isteğe bağlı platform sınıfları
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
