package com.alisat.vpn;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Small WARP switch shown only on the main entry screen and playlist editor. */
public final class WarpOverlayInstaller {
    private static final String TAG = "novo-warp-toggle";
    private static final int OFF_TEXT = Color.rgb(190, 165, 102);
    private static final int ON_TEXT = Color.rgb(244, 218, 151);
    private static final int BUSY_TEXT = Color.rgb(222, 190, 124);
    private static volatile boolean busy;
    private static volatile boolean awaitingConsentReturn;

    private WarpOverlayInstaller() { }

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (!isTargetScreen(activity)) return;
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (!(decor instanceof FrameLayout) || decor.findViewWithTag(TAG) != null) return;

                TextView toggle = new TextView(activity);
                toggle.setTag(TAG);
                toggle.setTextSize(10.5f);
                toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                toggle.setLetterSpacing(0.035f);
                toggle.setGravity(Gravity.CENTER);
                toggle.setMinHeight(dp(activity, 36));
                toggle.setPadding(dp(activity, 11), dp(activity, 5), dp(activity, 11), dp(activity, 5));
                toggle.setFocusable(true);
                toggle.setClickable(true);
                toggle.setAlpha(0.90f);
                toggle.setElevation(dp(activity, 5));
                update(toggle);
                toggle.setOnClickListener(v -> beginToggle(activity, toggle));

                // Compact top-right placement; limited to the login entry and playlist editor.
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 38), Gravity.TOP | Gravity.END);
                params.setMargins(dp(activity, 8), dp(activity, 12), dp(activity, 14), dp(activity, 8));
                ((FrameLayout) decor).addView(toggle, params);
            }

            @Override public void onActivityResumed(Activity activity) {
                if (!isTargetScreen(activity) || activity.getWindow() == null) return;
                if (awaitingConsentReturn) {
                    awaitingConsentReturn = false;
                    busy = false;
                }
                View view = activity.getWindow().getDecorView().findViewWithTag(TAG);
                if (view instanceof TextView) {
                    ((TextView) view).setEnabled(!busy);
                    update((TextView) view);
                }
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static boolean isTargetScreen(Activity activity) {
        String name = activity.getClass().getName();
        return "com.shadeed.ibopro.MainActivity".equals(name)
                || "com.shadeed.ibopro.activities.ChangePlaylistActivity".equals(name);
    }

    private static void beginToggle(Activity activity, TextView toggle) {
        if (busy || activity.isFinishing()) return;
        busy = true;
        toggle.setEnabled(false);
        update(toggle);

        WarpTunnelController controller = WarpTunnelController.get(activity);
        if (WarpTunnelController.isConnected()) {
            controller.disconnect((ok, message) -> complete(activity, toggle, ok, message));
            return;
        }

        // After Android's one-time VPN approval, connect directly on the worker thread.
        // Only the first-use system consent requires the transparent helper Activity.
        Intent consent = controller.prepare();
        if (consent == null) {
            controller.connect((ok, message) -> complete(activity, toggle, ok, message));
        } else {
            awaitingConsentReturn = true;
            try {
                Intent launch = new Intent(activity, WarpActivity.class);
                launch.putExtra(WarpActivity.EXTRA_TOGGLE, true);
                launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                activity.startActivity(launch);
            } catch (RuntimeException error) {
                awaitingConsentReturn = false;
                complete(activity, toggle, false, "Could not open Android VPN permission: " + error.getMessage());
            }
        }
    }

    private static void complete(Activity activity, TextView toggle, boolean ok, String message) {
        busy = false;
        if (activity.isFinishing()) return;
        toggle.setEnabled(true);
        update(toggle);
        // Stay quiet on success; only report an actionable failure.
        if (!ok) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static void update(TextView toggle) {
        boolean connected = WarpTunnelController.isConnected();
        toggle.setText(busy ? "WARP  ·  ..." : connected ? "WARP  ·  ON" : "WARP  ·  OFF");
        toggle.setTextColor(busy ? BUSY_TEXT : connected ? ON_TEXT : OFF_TEXT);
        toggle.setContentDescription(busy ? "Cloudflare WARP is changing state."
                : connected ? "Cloudflare WARP connected. Tap to disconnect."
                : "Cloudflare WARP disconnected. Tap to connect.");
        Activity activity = (Activity) toggle.getContext();
        toggle.setBackground(background(activity, connected, busy));
    }

    private static GradientDrawable background(Activity activity, boolean connected, boolean pending) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(pending ? Color.rgb(30, 26, 19)
                : connected ? Color.rgb(43, 34, 17) : Color.rgb(20, 19, 17));
        shape.setCornerRadius(dp(activity, 22));
        shape.setStroke(dp(activity, connected ? 2 : 1), connected
                ? Color.rgb(196, 157, 66) : Color.rgb(108, 88, 46));
        return shape;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

