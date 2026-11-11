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
 * - A plain relaunch reuses the existing Settings task and ignores the
 *   windowing mode: it can never split when Settings is already open, and
 *   it can exit a split that was open (device-proven 2026-11-11). The
 *   retry launches Settings into a new task adjacent to ours instead.
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
     *
     * Split order matters: the adjacent-task launch goes FIRST, while this
     * activity is still resumed. It explicitly targets a new task adjacent
     * to ours, so it can split even when Settings is already open (a plain
     * LAUNCH_ADJACENT relaunch reuses the existing fullscreen task and
     * never splits — user-reported 2026-11-11). The flag launch is the
     * fallback; a 400 ms retry is the safety net for a slow engage.
     */
    fun startPairing(activity: Activity, onStageTwo: () -> Unit) {
        onStageTwo()
        if (activity.isInMultiWindowMode) {
            // Already in split-screen (Settings is in the other pane):
            // re-launching Settings reuses its task and ignores the split
            // flags, which exits the open split (device-proven 2026-11-11).
            // Just start the episode; the pair row is already visible.
            return
        }
        if (!launchAdjacentTask(activity)) {
            val applied = launchAdjacent(
                activity,
                developmentSettingsIntent().apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                },
            )
            if (!applied) {
                launchAdjacentTaskOrPlain(activity)
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isInMultiWindowMode) {
                // The split did not engage: retry into a new task adjacent
                // to ours. A plain relaunch here only brings the existing
                // Settings task forward fullscreen (or exits an open split).
                launchAdjacentTaskOrPlain(activity)
            }
        }, PAIRING_STAGE_DELAY_MS)
    }

    /** Adjacent-task retry, falling back to a plain launch when unavailable. */
    private fun launchAdjacentTaskOrPlain(activity: Activity) {
        if (!launchAdjacentTask(activity)) {
            runCatching {
                activity.startActivity(
                    developmentSettingsIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            }
        }
    }

    /**
     * Launch Settings into a new task adjacent to ours. A plain relaunch
     * reuses the existing Settings task and ignores the windowing mode, so
     * it can never split when Settings is already open (device-proven
     * 2026-11-11). The adjacent-task id is a hidden API; absent on older
     * runtimes it fails soft and the caller falls back to a plain launch.
     */
    private fun launchAdjacentTask(activity: Activity): Boolean = runCatching {
        val options = ActivityOptions.makeBasic()
        ActivityOptions::class.java.getMethod(
            "setLaunchAdjacentTaskId", Int::class.javaPrimitiveType,
        ).invoke(options, activity.taskId)
        applySplitWindowing(options)
        activity.startActivityForResult(
            developmentSettingsIntent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            REQUEST_PAIRING_SETTINGS, options.toBundle(),
        )
        true
    }.getOrDefault(false)

    /**
     * Adjacent launch with the hidden windowing mode (reflection: the API is
     * hidden since API 28). Returns false only when the launch itself
     * throws; a missing windowing mode degrades to the LAUNCH_ADJACENT flag.
     */
    private fun launchAdjacent(activity: Activity, intent: Intent): Boolean = runCatching {
        val options = ActivityOptions.makeBasic()
        applySplitWindowing(options)
        activity.startActivityForResult(intent, REQUEST_PAIRING_SETTINGS, options.toBundle())
        true
    }.getOrDefault(false)

    /** Split windowing mode (PRIMARY) + create mode (TOP_OR_LEFT), best-effort. */
    private fun applySplitWindowing(options: ActivityOptions) {
        runCatching {
            ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode", Int::class.javaPrimitiveType,
            ).invoke(options, 3)
            // WINDOWING_MODE_SPLIT_SCREEN_PRIMARY = 3;
            // SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT = 0.
            runCatching {
                ActivityOptions::class.java.getMethod(
                    "setSplitScreenCreateMode", Int::class.javaPrimitiveType,
                ).invoke(options, 0)
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
}
