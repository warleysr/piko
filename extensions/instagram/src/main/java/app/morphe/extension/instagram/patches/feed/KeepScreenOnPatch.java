/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.feed;

import app.morphe.extension.instagram.utils.Pref;

@SuppressWarnings("unused")
public class KeepScreenOnPatch {

    // Called when the video player starts playing, with the value of the server side
    // setting that decides whether the video view keeps the screen on while playing.
    // That setting is off for most users, so the screen only stays on for a short
    // wake lock timeout (video duration + 10s) and turns off while a video loops.
    public static boolean keepScreenOnWhilePlaying(boolean original) {
        return original || Pref.keepScreenOnWhilePlaying();
    }

    // Called when the video completes, after the player turned keep screen on off.
    // Feed videos loop without reporting that playback started again, so keep it on.
    public static boolean keepScreenOnWhileLooping(boolean isLooping) {
        return isLooping && Pref.keepScreenOnWhilePlaying();
    }
}
