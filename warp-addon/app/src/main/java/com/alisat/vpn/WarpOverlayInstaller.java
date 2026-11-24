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

/** Adds a compact, accessible WARP toggle to existing app screens. */
public final class WarpOverlayInstaller {
    private static final String TAG = "novo-warp-toggle";
    private static final int COLOR_OFF_TEXT = Color.rgb(190, 165, 102);
    private static final int COLOR_ON_TEXT = Color.rgb(244, 218, 151);

    private WarpOverlayInstaller() { }

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (activity instanceof WarpActivity) return;
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (!(decor instanceof FrameLayout) || decor.findViewWithTag(TAG) != null) return;

                TextView toggle = new TextView(activity);
                toggle.setTag(TAG);
                toggle.setTextSize(12f);
                toggle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                toggle.setLetterSpacing(0.045f);
                toggle.setGravity(Gravity.CENTER);
                toggle.setMinHeight(dp(activity, 44));
                toggle.setPadding(dp(activity, 15), dp(activity, 8), dp(activity, 15), dp(activity, 8));
                toggle.setFocusable(true);
                toggle.setClickable(true);
                toggle.setElevation(dp(activity, 8));
                update(toggle);
                toggle.setOnClickListener(v -> {
                    toggle.setEnabled(false);
                    Intent intent = new Intent(activity, WarpActivity.class);
                    intent.putExtra(WarpActivity.EXTRA_TOGGLE, true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(intent);
                });

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48), Gravity.BOTTOM | Gravity.END);
                params.setMargins(dp(activity, 12), dp(activity, 12), dp(activity, 14), dp(activity, 18));
                ((FrameLayout) decor).addView(toggle, params);
            }

            @Override public void onActivityResumed(Activity activity) {
                if (activity.getWindow() == null || activity instanceof WarpActivity) return;
                View toggle = activity.getWindow().getDecorView().findViewWithTag(TAG);
                if (toggle instanceof TextView) {
                    toggle.setEnabled(true);
                    update((TextView) toggle);
                }
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void update(TextView toggle) {
        boolean connected = WarpTunnelController.isConnected();
        toggle.setText(connected ? "WARP  ·  ON" : "WARP  ·  OFF");
        toggle.setTextColor(connected ? COLOR_ON_TEXT : COLOR_OFF_TEXT);
        toggle.setContentDescription(connected
                ? "Cloudflare WARP connected. Tap to disconnect."
                : "Cloudflare WARP disconnected. Tap to connect.");
        Activity activity = (Activity) toggle.getContext();
        toggle.setBackground(background(activity, connected));
    }

    private static GradientDrawable background(Activity activity, boolean connected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(connected ? Color.rgb(43, 34, 17) : Color.rgb(20, 19, 17));
        shape.setCornerRadius(dp(activity, 28));
        shape.setStroke(dp(activity, connected ? 2 : 1), connected
                ? Color.rgb(196, 157, 66) : Color.rgb(108, 88, 46));
        return shape;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

}
