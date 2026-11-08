/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.buttons

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.miniplayer.NextGenWatchLayoutOnInterceptTouchEventFingerprint
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.playerControlsOverlayVisibilityPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val MAGNIFIER_RECON_CLASS =
    "Lapp/morphe/extension/youtube/videoplayer/MagnifierRecon;"

/**
 * PLAN A second hook point, in the same stock class. Once a layout intercepts a gesture the
 * framework stops calling onInterceptTouchEvent for it and delivers the remainder of the gesture to
 * the layout's own onTouchEvent. Verified present in the target APK: NextGenWatchLayout declares
 * onTouchEvent(Landroid/view/MotionEvent;)Z (probe of the stock 21.30.216 classes.dex).
 */
private object NextGenWatchLayoutOnTouchEventFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/watch/nextgenwatch/ui/NextGenWatchLayout;",
    name = "onTouchEvent",
    parameters = listOf("Landroid/view/MotionEvent;"),
)

@Suppress("unused")
val magnifierReconPatch = bytecodePatch(
    name = "Magnifier surface recon",
    description = "Adds an editable YouTube video magnifier. Uses the supported YouTube version set.",
) {
    dependsOn(
        sharedExtensionPatch,
        playerOverlayButtonsHookPatch,
        playerTypeHookPatch,
        videoIdPatch,
        playerControlsOverlayVisibilityPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_magnifier_enabled", summary = true)
        )
        addPlayerBottomButton(
            MAGNIFIER_RECON_CLASS,
            "onPlayerControlsInitialized",
        )
        hookVideoId("$MAGNIFIER_RECON_CLASS->onVideoIdChanged(Ljava/lang/String;)V")

        // PLAN A touch arbitration. In normal mode the zoom window is parented below the stock
        // controls, so a lower child never sees ACTION_DOWN. NextGenWatchLayout.onInterceptTouchEvent
        // is the stock method that already sits above every player child (the same one the miniplayer
        // patch injects into), so observing it needs no new override and no new class member.
        // The extension decides; returning true here makes the stock method return true, which
        // cancels the stock handlers for a claimed drag and swallows it through ACTION_UP.
        NextGenWatchLayoutOnInterceptTouchEventFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $MAGNIFIER_RECON_CLASS->onPlayerTouchIntercept(Landroid/view/MotionEvent;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :morphe_magnifier_no_claim
                    const/4 v$freeRegister, 0x1
                    return v$freeRegister
                    :morphe_magnifier_no_claim
                    nop
                """
            )
        }

        // PLAN A continuation of the same claimed gesture: after the interception above, the rest of
        // the gesture (MOVEs, UP) reaches this layout's own onTouchEvent and nowhere else. Returning
        // true there keeps the drag following the finger and swallows the gesture through ACTION_UP;
        // when nothing is claimed the extension returns false and the stock body runs untouched.
        NextGenWatchLayoutOnTouchEventFingerprint.method.apply {
            val freeRegister = findFreeRegister(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $MAGNIFIER_RECON_CLASS->onPlayerTouchEvent(Landroid/view/MotionEvent;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :morphe_magnifier_no_touch_claim
                    const/4 v$freeRegister, 0x1
                    return v$freeRegister
                    :morphe_magnifier_no_touch_claim
                    nop
                """
            )
        }
    }
}
