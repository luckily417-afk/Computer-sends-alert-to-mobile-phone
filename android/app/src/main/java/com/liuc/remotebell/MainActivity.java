package com.liuc.remotebell;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText serverInput;
    private EditText topicInput;
    private TextView statusView;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        requestNotificationPermission();
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(247, 248, 250));
        setContentView(root);

        TextView title = label("Remote Bell", 24, true);
        root.addView(title, fullWidth(-2));

        TextView serverLabel = label("中转服务器", 14, false);
        serverLabel.setPadding(0, dp(20), 0, dp(6));
        root.addView(serverLabel, fullWidth(-2));

        serverInput = input(prefs.getString(Constants.PREF_SERVER, Constants.DEFAULT_SERVER));
        root.addView(serverInput, fullWidth(dp(48)));

        TextView topicLabel = label("频道", 14, false);
        topicLabel.setPadding(0, dp(16), 0, dp(6));
        root.addView(topicLabel, fullWidth(-2));

        topicInput = input(prefs.getString(Constants.PREF_TOPIC, Constants.DEFAULT_TOPIC));
        root.addView(topicInput, fullWidth(dp(48)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, 0);
        root.addView(row, fullWidth(-2));

        Button start = new Button(this);
        start.setText("开始监听");
        row.addView(start, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button stop = new Button(this);
        stop.setText("停止监听");
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        stopParams.setMargins(dp(12), 0, 0, 0);
        row.addView(stop, stopParams);

        Button test = new Button(this);
        test.setText("本机测试响铃");
        LinearLayout.LayoutParams testParams = fullWidth(dp(48));
        testParams.setMargins(0, dp(14), 0, 0);
        root.addView(test, testParams);

        statusView = label("未监听", 15, false);
        statusView.setPadding(0, dp(18), 0, 0);
        root.addView(statusView, fullWidth(-2));

        start.setOnClickListener(v -> startListening());
        stop.setOnClickListener(v -> stopListening());
        test.setOnClickListener(v -> localAlarm());
    }

    private void startListening() {
        String server = cleanServer();
        String topic = cleanTopic();
        if (server.isEmpty() || topic.isEmpty()) {
            statusView.setText("服务器和频道不能为空");
            return;
        }

        prefs.edit()
            .putString(Constants.PREF_SERVER, server)
            .putString(Constants.PREF_TOPIC, topic)
            .apply();

        Intent intent = new Intent(this, ListenerService.class);
        intent.setAction(Constants.ACTION_START);
        intent.putExtra(Constants.EXTRA_SERVER, server);
        intent.putExtra(Constants.EXTRA_TOPIC, topic);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusView.setText("正在监听频道：" + topic);
    }

    private void stopListening() {
        Intent intent = new Intent(this, ListenerService.class);
        intent.setAction(Constants.ACTION_STOP_LISTENER);
        startService(intent);
        statusView.setText("已停止监听");
    }

    private void localAlarm() {
        Intent intent = new Intent(this, ListenerService.class);
        intent.setAction(Constants.ACTION_LOCAL_ALARM);
        intent.putExtra(Constants.EXTRA_MESSAGE, "这是一条本机测试提醒");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private String cleanServer() {
        return serverInput.getText().toString().trim().replaceAll("/+$", "");
    }

    private String cleanTopic() {
        return topicInput.getText().toString().trim();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private TextView label(String text, int sizeSp, boolean strong) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.rgb(24, 27, 31));
        if (strong) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private EditText input(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextSize(15);
        input.setPadding(dp(12), 0, dp(12), 0);
        return input;
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
