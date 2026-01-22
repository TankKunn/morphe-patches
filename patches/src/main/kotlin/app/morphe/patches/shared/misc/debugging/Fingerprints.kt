package app.morphe.patches.shared.misc.debugging

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object ExperimentalFeatureFlagParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    filters = listOf(
        string("Unable to parse proto typed experiment flag: ")
    ),
    custom = { method, _ ->
        // Early targets is: "L", "J", "[B"
        // Later targets is: "L", "J"
        method.parameters.let {
            (it.size == 2 || it.size == 3) && it[1].type == "J"
        }
    }
)

internal object ExperimentalBooleanFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L", "J", "Z")
)

internal object ExperimentalDoubleFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "D",
    parameters = listOf("J", "D")
)

internal object ExperimentalLongFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "J",
    parameters = listOf("J", "J")
)

internal object ExperimentalStringFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf("J", "Ljava/lang/String;")
)
