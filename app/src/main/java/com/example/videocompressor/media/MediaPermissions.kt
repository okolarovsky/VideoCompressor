package com.example.videocompressor.media

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

enum class AccessLevel { FULL, LIMITED, NONE }

/**
 * Permissions per API level:
 * - API 34+: READ_MEDIA_IMAGES + READ_MEDIA_VIDEO (full), READ_MEDIA_VISUAL_USER_SELECTED alone means limited.
 * - API 33:  READ_MEDIA_IMAGES + READ_MEDIA_VIDEO.
 * - API 32 and lower: READ_EXTERNAL_STORAGE.
 */
object MediaPermissions {

    private const val PREFS = "media_permissions"
    private const val KEY_REQUESTED = "requested_once"

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun accessLevel(context: Context): AccessLevel {
        if (Build.VERSION.SDK_INT < 33) {
            return if (isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)) AccessLevel.FULL else AccessLevel.NONE
        }
        val fullGranted = isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) &&
            isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
        return when {
            fullGranted -> AccessLevel.FULL
            Build.VERSION.SDK_INT >= 34 &&
                isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessLevel.LIMITED
            else -> AccessLevel.NONE
        }
    }

    /** Stores that the permission dialog was shown at least once; needed to tell "never asked" from "don't ask again". */
    fun markRequested(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    /**
     * True when the system will no longer show the dialog: it was requested before, nothing is granted,
     * and shouldShowRequestPermissionRationale is false (the user picked "Don't ask again").
     */
    fun isPermanentlyDenied(activity: Activity): Boolean {
        val requested = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_REQUESTED, false)
        if (!requested) return false
        if (accessLevel(activity) != AccessLevel.NONE) return false
        return requiredPermissions().none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
}
