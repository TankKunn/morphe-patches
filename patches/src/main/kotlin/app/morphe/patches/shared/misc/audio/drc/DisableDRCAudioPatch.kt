/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.audio.drc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playservice.is_21_17_or_greater
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/DisableDRCAudioPatch;"

@Suppress("unused")
internal fun disableDRCAudioPatch(
    block: BytecodePatchBuilder.() -> Unit,
    preferenceScreen: BasePreferenceScreen.Screen,
    useLegacyNormalizationFlag: BytecodePatchBuilder.() -> Boolean,
    useNormalizationFlag: BytecodePatchBuilder.() -> Boolean
) = bytecodePatch(
    name = "Disable DRC audio",
    description = "Adds an option to disable DRC (Dynamic Range Compression) audio."
) {

    block()

    execute {
        preferenceScreen.addPreferences(
            SwitchPreference("morphe_disable_drc_audio")
        )

        // Nullifying the first parameter/check will disable the normalization.
        fun patchLogic(freeRegister: String, instructionRegister: String) =
            """
                invoke-static { }, $EXTENSION_CLASS->disableDrcAudio()Z
                move-result $freeRegister
                if-eqz $freeRegister, :disable_drc_audio
                const/16 $instructionRegister, 0x0
                :disable_drc_audio
                nop
            """

        if (!is_21_17_or_greater) {
            VolumeNormalizationConfigLegacyFingerprint.apply {
                method.apply {
                    val instructionIndex = instructionMatches[3].index
                    val instructionRegister = getInstruction<OneRegisterInstruction>(
                        instructionIndex
                    ).registerA
                    val freeRegister = getInstruction<TwoRegisterInstruction>(
                        instructionMatches[4].index
                    ).registerA

                    addInstructionsWithLabels(
                        instructionIndex,
                        patchLogic(
                            "v$freeRegister",
                            "v$instructionRegister"
                        )
                    )
                }
            }
        } else {
            VolumeNormalizationConfigFingerprint.method.addInstructionsWithLabels(
                0,
                patchLogic(
                    "v0",
                    "p1"
                )
            )
        }
    }
}
