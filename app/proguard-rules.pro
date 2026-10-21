# Les bibliothèques (Firebase, Room, WorkManager, Glance, kotlinx.serialization, OkHttp)
# embarquent leurs propres règles R8. Restent quelques avertissements de Tink
# (dépendance de security-crypto) sur des annotations absentes à l'exécution.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**

# Glance instancie les ActionCallback par réflexion (getDeclaredConstructor().newInstance()) ;
# sa règle ne garde que la classe : en mode R8 complet, le constructeur serait supprimé
# et le tap sur le widget n'aurait plus aucun effet.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
