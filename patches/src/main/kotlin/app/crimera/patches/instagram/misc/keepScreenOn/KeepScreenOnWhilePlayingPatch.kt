/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.keepScreenOn

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val HERO_PLAYER_SETTING_CLASS = "Lcom/facebook/video/heroplayer/setting/HeroPlayerSetting;"
private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/feed/KeepScreenOnPatch;"

// Hero player listener callback for when playback starts. It only keeps the video view's
// screen on if a server side boolean of HeroPlayerSetting is enabled:
//
//   const-string v0, "start playing"
//   ...
//   iget-boolean v0, v0, HeroPlayerSetting;->A3H:Z
//   if-eqz v0, :skip
//   ...
//   invoke-static {player, true}, setKeepScreenOn(Player;Z)V
internal object VideoStartPlayingFingerprint : Fingerprint(
    returnType = "V",
    filters =
        listOf(
            string("start playing"),
            fieldAccess(
                definingClass = HERO_PLAYER_SETTING_CLASS,
                type = "Z",
                opcode = Opcode.IGET_BOOLEAN,
                location = InstructionLocation.MatchAfterWithin(4),
            ),
        ),
)

// Hero player listener callback for when playback completes. It always turns keep screen on
// off, including when the video loops, and a looping video does not report "start playing" again.
internal object VideoCompletionFingerprint : Fingerprint(
    classFingerprint = VideoStartPlayingFingerprint,
    returnType = "V",
    filters = listOf(string("onCompletion")),
)

// Index of the first static call shaped like setKeepScreenOn(Player;Z)V or stop(Player;Z)V.
private fun Method.indexOfPlayerBooleanCall(startIndex: Int, playerClass: String? = null) =
    indexOfFirstInstruction(startIndex) {
        if (opcode != Opcode.INVOKE_STATIC) return@indexOfFirstInstruction false
        val reference = getReference<MethodReference>() ?: return@indexOfFirstInstruction false
        reference.returnType == "V" &&
            reference.parameterTypes.size == 2 &&
            reference.parameterTypes[1] == "Z" &&
            (playerClass == null || reference.parameterTypes[0] == playerClass)
    }

@Suppress("unused")
val keepScreenOnWhilePlayingPatch =
    bytecodePatch(
        name = "Keep screen on while playing video",
        description = "Keeps the screen on while a video is playing in the feed, stories and other places, like in Reels.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val setKeepScreenOnMethod: MethodReference

            VideoStartPlayingFingerprint.method.apply {
                val settingIndex = VideoStartPlayingFingerprint.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(settingIndex).registerA

                val setKeepScreenOnIndex = indexOfPlayerBooleanCall(settingIndex)
                if (setKeepScreenOnIndex < 0) throw PatchException("Could not find setKeepScreenOn call")
                setKeepScreenOnMethod = getInstruction(setKeepScreenOnIndex).getReference<MethodReference>()
                    ?: throw PatchException("Could not find setKeepScreenOn call")

                addInstructions(
                    settingIndex + 1,
                    """
                    invoke-static {v$register}, $EXTENSION_CLASS->keepScreenOnWhilePlaying(Z)Z
                    move-result v$register
                    """.trimIndent(),
                )
            }

            val playerClass = setKeepScreenOnMethod.parameterTypes[0].toString()

            // Completion callback of the player controller, which reads the looping flag of the player:
            //
            //   iget-object v0, v2, Controller;->player:Player;
            //   iget-object v0, v0, Player;->config:Config;
            //   iget-boolean v0, v0, Config;->isLooping:Z
            //   const/16 v1, 0x3a
            val loopingFingerprint =
                Fingerprint(
                    returnType = "V",
                    parameters = listOf("J"),
                    filters =
                        listOf(
                            fieldAccess(type = playerClass, opcode = Opcode.IGET_OBJECT),
                            fieldAccess(
                                definingClass = playerClass,
                                opcode = Opcode.IGET_OBJECT,
                                location = InstructionLocation.MatchAfterImmediately(),
                            ),
                            fieldAccess(
                                type = "Z",
                                opcode = Opcode.IGET_BOOLEAN,
                                location = InstructionLocation.MatchAfterImmediately(),
                            ),
                            literal(0x3a, location = InstructionLocation.MatchAfterImmediately()),
                        ),
                )
            val (configField, loopingField) =
                loopingFingerprint.instructionMatches.let { matches ->
                    matches[1].instruction.getReference<FieldReference>()!! to
                        matches[2].instruction.getReference<FieldReference>()!!
                }

            VideoCompletionFingerprint.method.apply {
                val stringIndex = VideoCompletionFingerprint.instructionMatches.first().index
                val stopIndex = indexOfPlayerBooleanCall(stringIndex, playerClass)
                if (stopIndex < 0) throw PatchException("Could not find player stop call")

                // The boolean argument is not used after the call, so it is free to reuse.
                val stopCall = getInstruction<FiveRegisterInstruction>(stopIndex)
                val playerRegister = stopCall.registerC
                val freeRegister = stopCall.registerD

                addInstructions(
                    stopIndex + 1,
                    """
                    iget-object v$freeRegister, v$playerRegister, $configField
                    iget-boolean v$freeRegister, v$freeRegister, $loopingField
                    invoke-static {v$freeRegister}, $EXTENSION_CLASS->keepScreenOnWhileLooping(Z)Z
                    move-result v$freeRegister
                    invoke-static {v$playerRegister, v$freeRegister}, $setKeepScreenOnMethod
                    """.trimIndent(),
                )
            }

            enableSettings("keepScreenOnWhilePlaying")
        }
    }
