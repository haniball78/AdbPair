package com.adbpair.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Settings navigation for pairing.
 *
 * Hard facts (AOSP-verified, Samsung-proven in DroidDeck):
 * - No direct deep link to the wireless-debugging sub-page exists for a
 *   third-party app. The highlight key only scrolls/highlights the row.
 *   One manual tap on the row is unavoidable the first time — that is the
 *   design, not a bug.
 * - Split-screen order and ratio cannot be forced; Samsung ignores the
 *   PRIMARY windowing mode. The divider-swap hint is the only remedy.
 * - SettingsActivity has no onNewIntent: re-sending the intent while
 *   Settings is open does nothing. Bring-forward uses NEW_TASK | CLEAR_TOP.
 * - CLEAR_TASK / MULTIPLE_TASK landing flags are withdrawn (device-proven
 *   ineffective, and they destroy task restoration). They are not used here.
 */
object SettingsNavigator {

    const val REQUEST_PAIRING_SETTINGS = 1001
    const val PAIRING_STAGE_DELAY_MS = 400L
    const val CLOSE_SETTINGS_DELAY_MS = 4_000L

    private const val FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
    private const val TOGGLE_ADB_WIRELESS = "toggle_adb_wireless"

    /** The highlight key, in the AOSP-correct bundle form. */
    private fun developmentSettingsIntent(): Intent {
        val args = Bundle().apply { putString(FRAGMENT_ARGS_KEY, TOGGLE_ADB_WIRELESS) }
        return Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            // Both forms: AOSP reads the bundle; Samsung's Settings reads the
            // top-level extra on fresh launches (proven in DroidDeck video).
            // An extra the system ignores costs nothing.
            putExtra(SHOW_FRAGMENT_ARGS, args)
            putExtra(FRAGMENT_ARGS_KEY, TOGGLE_ADB_WIRELESS)
        }
    }

    /**
     * Staged launch: pairing work first, Settings second. The episode must
     * start while this app is still foreground: opening Settings can
     * dismiss an already-open pairing dialog, and a backgrounded activity
     * may not be able to start the service (device-proven 2026-11-11 —
     * every submit failed because the dialog died after the switch).
     */
    fun startPairing(activity: Activity, onStageTwo: () -> Unit) {
        onStageTwo()
        val intent = developmentSettingsIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        val applied = launchAdjacent(activity, intent)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isInMultiWindowMode) {
                // Split did not engage: fall back to a plain launch.
                runCatching {
                    activity.startActivity(
                        developmentSettingsIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                    )
                }
            }
        }, PAIRING_STAGE_DELAY_MS)
        if (!applied) {
            runCatching {
                activity.startActivity(
                    developmentSettingsIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            }
        }
    }

    /** Bring already-open Settings forward. NEW_TASK | CLEAR_TOP is the reliable route. */
    fun bringForward(activity: Activity) {
        runCatching {
            activity.startActivity(
                developmentSettingsIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    /** Close Settings ~4 s after a successful pair, only while still split. */
    fun closeSettingsAfterPair(activity: Activity) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (activity.isInMultiWindowMode) {
                runCatching { activity.finishActivity(REQUEST_PAIRING_SETTINGS) }
            }
        }, CLOSE_SETTINGS_DELAY_MS)
    }

    /**
     * Adjacent launch with the hidden windowing mode (reflection: the API is
     * hidden since API 28). Returns false when reflection is unavailable —
     * the caller falls back to a plain launch.
     */
    private fun launchAdjacent(activity: Activity, intent: Intent): Boolean = runCatching {
        val options = ActivityOptions.makeBasic()
        val method = ActivityOptions::class.java.getMethod(
            "setLaunchWindowingMode", Int::class.javaPrimitiveType,
        )
        // WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3.
        method.invoke(options, 3)
        activity.startActivityForResult(intent, REQUEST_PAIRING_SETTINGS, options.toBundle())
        true
    }.getOrDefault(false)
}
