package com.alisat.vpn;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Installs a small in-app shortcut to the WARP connect/disconnect screen. */
public final class WarpOverlayInstaller {
    private static final String TAG = "novo-warp-control";
    private WarpOverlayInstaller() { }

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                if (activity.getClass().getName().equals(WarpActivity.class.getName())) return;
                View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (!(decor instanceof FrameLayout) || decor.findViewWithTag(TAG) != null) return;

                TextView control = new TextView(activity);
                control.setTag(TAG);
                control.setTextColor(Color.WHITE);
                control.setTextSize(13f);
                control.setGravity(Gravity.CENTER);
                control.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
                control.setFocusable(true);
                control.setClickable(true);
                control.setElevation(dp(activity, 12));
                update(control);
                control.setBackground(background(WarpTunnelController.isConnected()));
                control.setOnClickListener(v -> {
                    Intent intent = new Intent(activity, WarpActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(intent);
                });

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 46), Gravity.BOTTOM | Gravity.END);
                params.setMargins(dp(activity, 14), dp(activity, 14), dp(activity, 14), dp(activity, 18));
                ((FrameLayout) decor).addView(control, params);
            }

            @Override public void onActivityResumed(Activity activity) {
                if (activity.getWindow() == null) return;
                View control = activity.getWindow().getDecorView().findViewWithTag(TAG);
                if (control instanceof TextView) {
                    update((TextView) control);
                    control.setBackground(background(WarpTunnelController.isConnected()));
                }
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static void update(TextView view) {
        view.setText(WarpTunnelController.isConnected() ? "WARP ON · CONTROL" : "WARP OFF · CONTROL");
    }

    private static GradientDrawable background(boolean connected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(connected ? Color.rgb(28, 135, 84) : Color.rgb(35, 45, 58));
        drawable.setCornerRadius(1000f);
        drawable.setStroke(1, connected ? Color.rgb(108, 230, 158) : Color.rgb(170, 190, 210));
        return drawable;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
