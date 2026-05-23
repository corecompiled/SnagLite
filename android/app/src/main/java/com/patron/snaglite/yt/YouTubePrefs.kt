package com.patron.snaglite.yt

import android.content.Context
import android.content.SharedPreferences

object YouTubePrefs {

    private const val PREFS = "snaglite_yt"
    private const val K_VISITOR_DATA = "visitor_data"
    private const val K_VISITOR_EXPIRY = "visitor_data_expiry"
    private const val K_SETUP_COMPLETE = "setup_complete"
    private const val K_LAST_YTDLP_UPDATE = "last_ytdlp_update"
    private const val K_SIGNED_IN = "signed_in"
    private const val K_WEB_UA = "web_ua"
    private const val K_LAST_ENGINE_CHECK = "last_engine_check"
    private const val K_ENGINE_SNOOZE_UNTIL = "engine_snooze_until"

    private const val VISITOR_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    const val UPDATE_INTERVAL_MS = 7L * 24L * 60L * 60L * 1000L
    const val ENGINE_CHECK_INTERVAL_MS = 1L * 24L * 60L * 60L * 1000L

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setVisitorData(ctx: Context, value: String?) {
        sp(ctx).edit().apply {
            if (value.isNullOrBlank()) {
                remove(K_VISITOR_DATA)
                remove(K_VISITOR_EXPIRY)
            } else {
                putString(K_VISITOR_DATA, value)
                putLong(K_VISITOR_EXPIRY, System.currentTimeMillis() + VISITOR_TTL_MS)
            }
        }.apply()
    }

    fun visitorDataIfFresh(ctx: Context): String? {
        val s = sp(ctx)
        val v = s.getString(K_VISITOR_DATA, null) ?: return null
        return if (System.currentTimeMillis() < s.getLong(K_VISITOR_EXPIRY, 0L)) v else null
    }

    fun isSetupComplete(ctx: Context): Boolean = sp(ctx).getBoolean(K_SETUP_COMPLETE, false)
    fun setSetupComplete(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_SETUP_COMPLETE, v).apply()

    fun lastYtdlpUpdate(ctx: Context): Long = sp(ctx).getLong(K_LAST_YTDLP_UPDATE, 0L)
    fun setLastYtdlpUpdate(ctx: Context, ts: Long) =
        sp(ctx).edit().putLong(K_LAST_YTDLP_UPDATE, ts).apply()

    fun isSignedIn(ctx: Context): Boolean = sp(ctx).getBoolean(K_SIGNED_IN, false)
    fun setSignedIn(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_SIGNED_IN, v).apply()

    fun webUa(ctx: Context): String? = sp(ctx).getString(K_WEB_UA, null)
    fun setWebUa(ctx: Context, ua: String?) {
        sp(ctx).edit().apply {
            if (ua.isNullOrBlank()) remove(K_WEB_UA) else putString(K_WEB_UA, ua)
        }.apply()
    }

    fun lastEngineCheck(ctx: Context): Long = sp(ctx).getLong(K_LAST_ENGINE_CHECK, 0L)
    fun setLastEngineCheck(ctx: Context, ts: Long) =
        sp(ctx).edit().putLong(K_LAST_ENGINE_CHECK, ts).apply()

    fun engineSnoozeUntil(ctx: Context): Long = sp(ctx).getLong(K_ENGINE_SNOOZE_UNTIL, 0L)
    fun setEngineSnoozeUntil(ctx: Context, ts: Long) =
        sp(ctx).edit().putLong(K_ENGINE_SNOOZE_UNTIL, ts).apply()
}
