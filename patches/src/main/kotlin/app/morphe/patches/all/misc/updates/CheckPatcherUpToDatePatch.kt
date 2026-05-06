package app.morphe.patches.all.misc.updates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch

// TODO: Delete this after Manager 1.17.0 has been released
internal val checkPatcherUpToDatePatch = bytecodePatch {
    execute {
        try {
            Fingerprint(
                filters = listOf(
                    methodCall(definingClass = "Lapp/morphe/extension/shared/Utils;")
                )
            ).instructionMatches.first().getMethodCalled()
        } catch (_: Throwable) {
            throw RuntimeException(
                "\n\n#####################################\n\n" +
                        "Your Morphe app is outdated. Please update Morphe " +
                        "by downloading from https://morphe.software\n\n" +
                        "#####################################\n\n"
            )
        }
    }
}
