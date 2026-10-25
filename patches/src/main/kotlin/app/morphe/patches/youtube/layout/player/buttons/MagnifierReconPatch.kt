/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.buttons

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
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
    }
}
