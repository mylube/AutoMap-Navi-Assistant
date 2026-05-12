package com.shadow.automap;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    // 定义一个请求码，用于区分权限请求返回的结果
    private static final int REQUEST_CODE_DRAW_OVERLAY = 1001;
    private static final String TAG = "MainActivity";
    private SharedPreferences prefs;
    public static final String ACTION_UPDATE_SETTINGS = "com.shadow.automap.UPDATE_SETTINGS";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查并申请悬浮窗权限
        checkAndStartFloatingWindow();

        initSettingView();
    }

    private void initSettingView(){
        prefs = getSharedPreferences("NaviSettings", MODE_PRIVATE);

        RadioGroup rgTheme = findViewById(R.id.rg_theme);
        SeekBar sbWindowSize = findViewById(R.id.sb_window_size);
        Button btnToggle = findViewById(R.id.btn_toggle_service);


        // --- 🔥 新增：导航布局样式选择控件 ---
        RadioGroup rgNaviStyle = findViewById(R.id.rg_navi_style);

        // 1. 初始化回显：从 SharedPreferences 加载上次保存的选择
        // 默认选择常规模式 (R.id.rb_style_standard)
        int savedStyleId = prefs.getInt("navi_style_id", R.id.rb_style_standard);
        rgNaviStyle.check(savedStyleId);

        // 2. 监听样式切换
        rgNaviStyle.setOnCheckedChangeListener((group, checkedId) -> {
            // 保存选中的 RadioButton ID 到本地
            prefs.edit().putInt("navi_style_id", checkedId).apply();

            // 发送广播通知 Service 布局可能发生了改变，需要根据新样式刷新
            sendBroadcast(new Intent(ACTION_UPDATE_SETTINGS));

            Log.d(TAG, "导航样式已保存，选中 ID: " + checkedId);
        });



        rgTheme.check(prefs.getInt("theme_id", R.id.rb_theme_dark));
        sbWindowSize.setProgress(prefs.getInt("window_size", 35));
        Log.d(TAG, "initSettingView: prefs"+sbWindowSize.getProgress());
        rgTheme.setOnCheckedChangeListener((group, checkedId) -> {
            prefs.edit().putInt("theme_id", checkedId).apply();
            sendBroadcast(new Intent(ACTION_UPDATE_SETTINGS));
        });

        sbWindowSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("window_size", progress).apply();
                    sendBroadcast(new Intent(ACTION_UPDATE_SETTINGS));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnToggle.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingNaviService.class));
            startService(new Intent(this, FloatingNaviService.class));
        });
    }

    private void checkAndStartFloatingWindow() {
        // 判断系统版本是否在 Android 6.0 (M) 及以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 判断是否已经拥有悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请开启悬浮窗权限", Toast.LENGTH_LONG).show();
                // 如果没有权限，跳转到系统设置页面引导用户开启
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_CODE_DRAW_OVERLAY);
            } else {
                // 如果已经有权限，直接启动服务
                startFloatingService();
            }
        } else {
            // Android 6.0 以下不需要动态申请，直接启动
            startFloatingService();
        }
    }

    // 处理用户从设置页面返回后的结果
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_DRAW_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "权限获取成功", Toast.LENGTH_SHORT).show();
                    startFloatingService();
                } else {
                    Toast.makeText(this, "悬浮窗权限被拒绝，无法显示导航", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // 启动我们写好的 Service
    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingNaviService.class);
        startService(intent);

        // 可选：启动悬浮窗后，把主界面退到后台，让悬浮窗直接显示在桌面上
        // moveTaskToBack(true);
    }
}
