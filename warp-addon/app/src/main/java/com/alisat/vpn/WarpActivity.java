package com.alisat.vpn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Simple second launcher entry so WARP can be opened inside the same APK. */
public final class WarpActivity extends Activity {
    private static final int VPN_CONSENT_REQUEST = 731;
    private WarpTunnelController controller;
    private TextView state;
    private Button connect;
    private Button disconnect;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = new WarpTunnelController(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(28, 28, 28, 28);
        root.setBackgroundColor(Color.rgb(16, 25, 40));

        TextView title = new TextView(this);
        title.setText("Cloudflare WARP");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80));

        state = new TextView(this);
        state.setText("Disconnected");
        state.setTextColor(Color.LTGRAY);
        state.setTextSize(17);
        state.setGravity(Gravity.CENTER);
        root.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60));

        connect = new Button(this);
        connect.setText("Connect WARP");
        root.addView(connect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64));
        disconnect = new Button(this);
        disconnect.setText("Disconnect");
        root.addView(disconnect, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 64));
        setContentView(root);

        connect.setOnClickListener(v -> requestVpnConsent());
        disconnect.setOnClickListener(v -> controller.disconnect((ok, message) -> showResult(ok, message)));
    }

    private void requestVpnConsent() {
        Intent intent = controller.prepare();
        if (intent == null) startConnection();
        else startActivityForResult(intent, VPN_CONSENT_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_CONSENT_REQUEST) {
            if (resultCode == RESULT_OK) startConnection();
            else showResult(false, "VPN permission was not granted");
        }
    }

    private void startConnection() {
        connect.setEnabled(false);
        state.setText("Connecting…");
        controller.connect((ok, message) -> {
            connect.setEnabled(true);
            showResult(ok, message);
        });
    }

    private void showResult(boolean ok, String message) {
        state.setText(ok ? "Connected" : message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }
}
