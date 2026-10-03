/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.feed;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.Adapter;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

import com.instagram.common.ui.widget.reboundviewpager.ReboundViewPager;

@SuppressWarnings("unused")
public class CarouselAutoScrollPatch {
    private static final long IMAGE_PAGE_DURATION_MS = 5000;

    // A carousel must be at least this visible to scroll it, so posts leaving the screen do not move.
    private static final float MIN_VISIBLE_FRACTION = 0.5f;

    private static int carouselPagerId;
    private static int carouselVideoPageId;

    // Carousel scrolled by the last auto scroll. It keeps scrolling past image pages
    // as long as it stays on that page, and stops once the user swipes or leaves it.
    private static WeakReference<ReboundViewPager> chainPager = new WeakReference<>(null);
    private static int chainPage = -1;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable imagePageTimeout = CarouselAutoScrollPatch::onImagePageTimeout;

    public static boolean isEnabled() {
        return Pref.carouselAutoScroll();
    }

    public static void toggle() {
        boolean enabled = !isEnabled();
        Pref.setCarouselAutoScroll(enabled);
        if (!enabled) cancelChain();
        Utils.showToastShort(str(enabled ? "piko_carousel_auto_scroll_enabled" : "piko_carousel_auto_scroll_disabled"));
    }

    public static String menuTitle() {
        return str(isEnabled() ? "piko_carousel_auto_scroll_on" : "piko_carousel_auto_scroll_off");
    }

    /**
     * Injection point. Called on every completion of a video, including each loop of a looping video.
     *
     * @param videoView View the video is rendered on, or null if the player has none.
     */
    public static void onVideoCompleted(View videoView) {
        if (videoView == null || !isEnabled()) return;

        // Player callbacks are not on the main thread.
        handler.post(() -> {
            try {
                ReboundViewPager pager = findCarouselPager(videoView);
                if (pager == null || !isMostlyVisible(pager)) return;

                // Neighbour pages may have their own player, only follow the page on screen.
                View activePage = pager.getCurrentActiveView();
                if (activePage != null && !isDescendant(videoView, activePage)) return;

                scrollToNextPage(pager);
            } catch (Exception ex) {
                Logger.printException(() -> "onVideoCompleted failure", ex);
            }
        });
    }

    /**
     * Replaced by the patch with a call to the obfuscated method that animates the pager to a page.
     */
    private static void smoothScrollToPage(ReboundViewPager pager, int page) {
        Logger.printDebug(() -> "smoothScrollToPage was not patched");
    }

    private static void scrollToNextPage(ReboundViewPager pager) {
        Adapter adapter = pager.getAdapter();
        int page = pager.getCurrentDataIndex();
        if (adapter == null || page < 0 || page >= adapter.getCount() - 1) {
            cancelChain();
            return;
        }

        int nextPage = page + 1;
        smoothScrollToPage(pager, nextPage);

        chainPager = new WeakReference<>(pager);
        chainPage = nextPage;
        handler.removeCallbacks(imagePageTimeout);
        handler.postDelayed(imagePageTimeout, IMAGE_PAGE_DURATION_MS);
    }

    private static void onImagePageTimeout() {
        try {
            ReboundViewPager pager = chainPager.get();
            if (pager == null || !isEnabled() || !isMostlyVisible(pager)
                    || pager.getCurrentDataIndex() != chainPage) {
                cancelChain();
                return;
            }

            // A video page scrolls on its own once the video completes.
            if (isVideoPage(pager.getCurrentActiveView())) return;

            scrollToNextPage(pager);
        } catch (Exception ex) {
            Logger.printException(() -> "onImagePageTimeout failure", ex);
        }
    }

    private static void cancelChain() {
        handler.removeCallbacks(imagePageTimeout);
        chainPager = new WeakReference<>(null);
        chainPage = -1;
    }

    private static ReboundViewPager findCarouselPager(View view) {
        if (carouselPagerId == 0) {
            carouselPagerId = ResourceUtils.getIdentifier(ResourceType.ID, "carousel_viewpager");
        }

        // Stories and other screens also use this pager, so match the feed carousel id.
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent instanceof ReboundViewPager && ((View) parent).getId() == carouselPagerId) {
                return (ReboundViewPager) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static boolean isVideoPage(View page) {
        if (page == null) return false;
        if (carouselVideoPageId == 0) {
            carouselVideoPageId = ResourceUtils.getIdentifier(ResourceType.ID, "carousel_video_media_group");
        }

        View videoGroup = page.findViewById(carouselVideoPageId);
        return videoGroup != null && videoGroup.getVisibility() == View.VISIBLE;
    }

    private static boolean isDescendant(View view, View ancestor) {
        ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isMostlyVisible(View view) {
        if (!view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return false;

        Rect visible = new Rect();
        if (!view.getGlobalVisibleRect(visible)) return false;

        float visibleArea = (float) visible.width() * visible.height();
        return visibleArea / ((float) view.getWidth() * view.getHeight()) >= MIN_VISIBLE_FRACTION;
    }
}
