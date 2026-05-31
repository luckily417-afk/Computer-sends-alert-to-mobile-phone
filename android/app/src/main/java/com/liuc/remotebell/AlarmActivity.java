package com.liuc.remotebell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class AlarmActivity extends Activity {
    private TextView messageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepareWindow();
        buildUi();
        showMessage(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showMessage(intent);
    }

    private void prepareWindow() {
        Window window = getWindow();
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(252, 248, 238));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("远程提醒");
        title.setTextSize(28);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(24, 27, 31));
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth(-2));

        messageView = new TextView(this);
        messageView.setTextSize(22);
        messageView.setTextColor(Color.rgb(24, 27, 31));
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(0, dp(28), 0, dp(28));
        root.addView(messageView, fullWidth(-2));

        Button stop = new Button(this);
        stop.setText("停止响铃");
        stop.setTextSize(18);
        root.addView(stop, fullWidth(dp(56)));
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, ListenerService.class);
            intent.setAction(Constants.ACTION_STOP_ALARM);
            startService(intent);
            finish();
        });
    }

    private void showMessage(Intent intent) {
        String message = intent.getStringExtra(Constants.EXTRA_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            message = "收到远程提醒";
        }
        messageView.setText(message);
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
