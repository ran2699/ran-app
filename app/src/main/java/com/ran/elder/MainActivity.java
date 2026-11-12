package com.ran.elder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView a11yState;
    private TextView overlayState;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(30), dp(24), dp(40));
        root.setBackgroundColor(0xFF121212);

        root.addView(text("老人抖音 测试版 v0.1", 28, 0xFFFFFFFF));
        root.addView(text("做完下面三步，再点最下面的按钮进入抖音。", 18, 0xFFAAAAAA), top(10));

        root.addView(text("第一步：开启无障碍服务", 21, 0xFFFFFFFF), top(26));
        a11yState = text("", 20, 0xFFFF5555);
        root.addView(a11yState, top(6));
        Button b1 = bigButton("去开启");
        b1.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(b1, top(10));

        root.addView(text("第二步：允许显示在其他应用上层", 21, 0xFFFFFFFF), top(26));
        overlayState = text("", 20, 0xFFFF5555);
        root.addView(overlayState, top(6));
        Button b2 = bigButton("去允许");
        b2.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        root.addView(b2, top(10));

        root.addView(text("第三步：打开抖音", 21, 0xFFFFFFFF), top(26));
        Button b3 = bigButton("打开抖音");
        b3.setOnClickListener(v -> launchDouyin());
        root.addView(b3, top(10));

        root.addView(text("小米手机还要做两件事：\n"
                + "1. 设置 → 应用设置 → 应用管理 → 老人抖音 → 省电策略 → 无限制\n"
                + "2. 同一页 → 允许自启动", 16, 0xFF888888), top(30));

        root.addView(text("说明：进入抖音后应当出现三个大按钮，其余区域全黑只留视频。", 16, 0xFF888888), top(16));

        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = isA11yOn();
        a11yState.setText(on ? "已开启" : "未开启，点下面去开");
        a11yState.setTextColor(on ? 0xFF33DD66 : 0xFFFF5555);

        boolean ov = Settings.canDrawOverlays(this);
        overlayState.setText(ov ? "已允许" : "未允许，点下面去允许");
        overlayState.setTextColor(ov ? 0xFF33DD66 : 0xFFFF5555);
    }

    private boolean isA11yOn() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        return flat.contains(getPackageName() + "/" + DouyinA11yService.class.getName());
    }

    private void launchDouyin() {
        Intent i = getPackageManager().getLaunchIntentForPackage(DouyinA11yService.PKG_DOUYIN);
        if (i == null) {
            i = getPackageManager().getLaunchIntentForPackage(DouyinA11yService.PKG_DOUYIN_LITE);
        }
        if (i == null) {
            Toast.makeText(this, "没找到抖音，请先装一个", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(i);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private Button bigButton(String s) {
        Button bt = new Button(this);
        bt.setText(s);
        bt.setTextSize(22);
        return bt;
    }

    private LinearLayout.LayoutParams top(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = px;
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
