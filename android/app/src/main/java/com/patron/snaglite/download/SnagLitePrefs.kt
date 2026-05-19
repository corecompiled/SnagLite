package com.patron.snaglite.download

import android.content.Context
import android.content.SharedPreferences

object SnagLitePrefs {

    private const val PREFS = "snaglite_app"
    private const val K_DELETE_FILE_ON_REMOVE = "delete_file_on_remove"
    private const val K_BATT_OPT_DONT_ASK = "batt_opt_dont_ask"
    private const val K_MEDIA_MIGRATED = "media_migrated_v1"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deleteFileOnRemove(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_DELETE_FILE_ON_REMOVE, false)

    fun setDeleteFileOnRemove(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_DELETE_FILE_ON_REMOVE, v).apply()
    }

    fun battOptDontAsk(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_BATT_OPT_DONT_ASK, false)

    fun setBattOptDontAsk(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_BATT_OPT_DONT_ASK, v).apply()
    }

    fun mediaMigrated(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_MEDIA_MIGRATED, false)

    fun setMediaMigrated(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(K_MEDIA_MIGRATED, v).apply()
    }
}
