/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.carouselAutoScroll

import app.crimera.patches.instagram.misc.keepScreenOn.VideoCompletionFingerprint
import app.crimera.patches.instagram.misc.keepScreenOn.playerSetKeepScreenOnMethod
import app.crimera.patches.instagram.misc.keepScreenOn.videoCompletionStopCall
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.addOverflowMenuButtonAttributes
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.debugOverflowButton.debugOverflowMenuButtonPatch
import app.crimera.patches.instagram.misc.overflowMenuButton.posts.hookOverflowMenuButton
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val REBOUND_VIEW_PAGER_CLASS = "Lcom/instagram/common/ui/widget/reboundviewpager/ReboundViewPager;"
private const val EXTENSION_CLASS = "$PATCHES_DESCRIPTOR/feed/CarouselAutoScrollPatch;"

// Animates the pager to a page. The method next to it passes false instead of true and jumps without animation.
//
//   int-to-float v3, p1
//   const-wide/16 v1, 0x0
//   const/4 v0, 0x1
//   invoke-static {p0, v1, v2, v3, v0}, ReboundViewPager;->springToPage(ReboundViewPager;DFZ)V
internal object ReboundViewPagerSmoothScrollFingerprint : Fingerprint(
    definingClass = REBOUND_VIEW_PAGER_CLASS,
    returnType = "V",
    parameters = listOf("I"),
    filters =
        listOf(
            opcode(Opcode.INT_TO_FLOAT),
            literal(1, location = InstructionLocation.MatchAfterWithin(2)),
            methodCall(
                definingClass = REBOUND_VIEW_PAGER_CLASS,
                parameters = listOf(REBOUND_VIEW_PAGER_CLASS, "D", "F", "Z"),
                returnType = "V",
                location = InstructionLocation.MatchAfterImmediately(),
            ),
        ),
)

internal object SmoothScrollToPageExtensionFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "smoothScrollToPage",
)

@Suppress("unused")
val carouselAutoScrollPatch =
    bytecodePatch(
        name = "Carousel auto scroll",
        description =
            "Adds a post menu button to turn on scrolling carousels to the next item when a video ends. " +
                "Images in between are shown for 5 seconds.",
    ) {
        dependsOn(settingsPatch, hookOverflowMenuButton, debugOverflowMenuButtonPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            SmoothScrollToPageExtensionFingerprint.method.addInstructions(
                0,
                """
                invoke-virtual {p0, p1}, ${ReboundViewPagerSmoothScrollFingerprint.method}
                return-void
                """.trimIndent(),
            )

            val playerClass = playerSetKeepScreenOnMethod().parameterTypes[0].toString()

            // The player turns keep screen on on and off on the view the video is rendered on:
            //
            //   iget-object v1, v0, Player;->videoView:Landroid/view/View;
            //   if-eqz v1, :cond_0
            //   iget-boolean v0, p0, Runnable;->keepScreenOn:Z
            //   invoke-virtual {v1, v0}, Landroid/view/View;->setKeepScreenOn(Z)V
            val videoViewField =
                Fingerprint(
                    returnType = "V",
                    filters =
                        listOf(
                            fieldAccess(
                                definingClass = playerClass,
                                type = "Landroid/view/View;",
                                opcode = Opcode.IGET_OBJECT,
                            ),
                            methodCall(
                                definingClass = "Landroid/view/View;",
                                name = "setKeepScreenOn",
                                location = InstructionLocation.MatchAfterWithin(3),
                            ),
                        ),
                ).instructionMatches
                    .first()
                    .instruction
                    .getReference<FieldReference>()
                    ?: throw PatchException("Could not find the player video view field")

            VideoCompletionFingerprint.method.apply {
                val (stopIndex, stopCall) = videoCompletionStopCall(playerClass)
                val freeRegister = stopCall.registerD

                addInstructions(
                    stopIndex + 1,
                    """
                    iget-object v$freeRegister, v${stopCall.registerC}, $videoViewField
                    invoke-static {v$freeRegister}, $EXTENSION_CLASS->onVideoCompleted(Landroid/view/View;)V
                    """.trimIndent(),
                )
            }

            addOverflowMenuButtonAttributes("PIKO_CAROUSEL_AUTO_SCROLL", "carouselAutoScrollOverflowButton")

            enableSettings("carouselAutoScroll")
        }
    }
