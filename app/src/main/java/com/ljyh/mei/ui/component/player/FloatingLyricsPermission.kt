package com.ljyh.mei.ui.component.player

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

fun isXiaomiFloatingLyricsPermissionRequired(): Boolean = Build.MANUFACTURER.let { manufacturer ->
    manufacturer.equals("Xiaomi", ignoreCase = true) ||
        manufacturer.equals("Redmi", ignoreCase = true) ||
        manufacturer.equals("Poco", ignoreCase = true)
}

fun hasFloatingLyricsBackgroundPermission(context: Context): Boolean {
    if (floatingLyricsNeedsOverlayPermission(context)) return false
    if (!isXiaomiFloatingLyricsPermissionRequired()) return true
    return runCatching {
        // Xiaomi controls background activity launches with a vendor-specific app op.
        val checkOpNoThrow = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        checkOpNoThrow.invoke(
            context.getSystemService(AppOpsManager::class.java),
            10021,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

fun floatingLyricsNeedsOverlayPermission(context: Context): Boolean =
    !runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

fun floatingLyricsPermissionIntent(context: Context): Intent = when {
    floatingLyricsNeedsOverlayPermission(context) ->
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    isXiaomiFloatingLyricsPermissionRequired() ->
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setPackage("com.miui.securitycenter")
            .putExtra("extra_pkgname", context.packageName)
            .putExtra("extra_package_uid", Process.myUid())
    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun openFloatingLyricsPermissionSettings(context: Context): Boolean =
    runCatching { context.startActivity(floatingLyricsPermissionIntent(context)) }.isSuccess ||
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
