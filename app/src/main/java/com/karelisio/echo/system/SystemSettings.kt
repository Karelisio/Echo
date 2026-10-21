package com.karelisio.echo.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.karelisio.echo.notification.EchoNotifications

/** État des autorisations système utiles à la réception, et raccourcis vers les bons écrans. */
data class SystemStatus(
    val notificationsAllowed: Boolean,
    val dndAccess: Boolean,
    val channelBypassesDnd: Boolean,
    val ignoringBatteryOptimizations: Boolean,
)

object SystemSettings {

    /** Honor (MagicOS) et Huawei (EMUI) gèrent l'arrière-plan et Ne pas déranger de façon plus stricte. */
    val isHonorOrHuawei: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("honor", "huawei") ||
            Build.BRAND.lowercase() in setOf("honor", "huawei")

    val needsNotificationPermission: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun status(context: Context, notifications: EchoNotifications) = SystemStatus(
        notificationsAllowed = notificationsAllowed(context),
        dndAccess = hasDndAccess(context),
        channelBypassesDnd = notifications.echoChannelBypassesDnd(),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
    )

    fun notificationsAllowed(context: Context): Boolean {
        val permission = !needsNotificationPermission ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun hasDndAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun openDndAccess(context: Context) = context.startFirst(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
        appDetails(context),
    )

    /** Demande directe d'exclusion de l'optimisation de batterie (appli installée hors Play Store). */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) = context.startFirst(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(context),
    )

    fun openNotificationSettings(context: Context) = context.startFirst(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails(context),
    )

    /** Réglages du canal « Échos reçus » : l'utilisateur peut y activer « Ignorer Ne pas déranger ». */
    fun openEchoChannelSettings(context: Context) = context.startFirst(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, EchoNotifications.CHANNEL_ECHOES),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    /** Écran Ne pas déranger du système (exceptions / applications autorisées). */
    fun openDndSettings(context: Context) = context.startFirst(
        Intent(ACTION_ZEN_MODE_SETTINGS),
        Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        Intent(Settings.ACTION_SOUND_SETTINGS),
    )

    /** Gestionnaire de lancement d'applications d'Honor / Huawei (« Gérer manuellement »). */
    fun openLaunchManager(context: Context) = context.startFirst(
        *LAUNCH_MANAGERS.map { Intent().setComponent(it) }.toTypedArray(),
        appDetails(context),
    )

    fun openAppDetails(context: Context) = context.startFirst(appDetails(context))

    private fun appDetails(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    private fun Context.startFirst(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    // Constante publique côté système mais masquée du SDK.
    private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"

    private val LAUNCH_MANAGERS = listOf(
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
    )
}
