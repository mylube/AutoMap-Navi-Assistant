package com.shadow.automap;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class FloatingNaviService extends Service {

    private static final String TAG = "FloatingNaviService";
    public static FloatingNaviService instance;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;
    private AmapNaviReceiver amapNaviReceiver;
    private SharedPreferences prefs;

    // 布局模式常量
    private static final int LAYOUT_NAVI_STANDARD = 0;
    private static final int LAYOUT_NAVI_MINIMAL = 1;
    private static final int LAYOUT_CRUISE = 2;
    private int currentLayoutType = -1;

    // 前台服务通知
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "navi_foreground";

    // 红绿灯方向常量
    private static final int DIR_LEFT = 1;
    private static final int DIR_RIGHT = 2;
    private static final int DIR_U_TURN = 3;
    private static final int DIR_STRAIGHT = 4;
    private static final int DIR_NONE = 0;

    // 导航数据缓存（解决布局切换时的数据丢失）
    private NaviDataCache lastNaviData;
    private LightDataCache lastLightData;

    private static class NaviDataCache {
        String disNum, disUnit, actionStr, roadName, summaryStr, etaStr, speed;
        int progressPercentage, turnIconId, cameraDist;
    }

    private static class LightDataCache {
        int status, seconds, dir;
    }

    // UI 组件引用
    private TextView tvDistanceNum, tvDistanceUnit, tvAction, tvRoadName, tvCruiseSpeed, tvSummary, tvEta, tvLightTime,tvCameraWarning,tvCameraDesc;
    private ImageView ivTurnIcon, ivLightIcon, ivLightArrow;
    private ProgressBar pbRoute;
    private View llTrafficLightGroup;
    private ObjectAnimator blinkAnimator;

    // 交互与锁定变量
    private boolean isLocked = false;
    private boolean isLongPressTriggered = false;
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // 🔥 重新加回：红绿灯专属 Handler
    private Handler lightWatchdogHandler = new Handler(Looper.getMainLooper());

    // 看门狗机制
    private static final long MAIN_TIMEOUT_MS = 2000; // 2秒无数据隐藏
    private static final long LIGHT_TIMEOUT_MS = 3000; // 红绿灯3秒无数据隐藏

    private Runnable hideMainRunnable = () -> {
        if (floatingView != null && floatingView.getVisibility() == View.VISIBLE) {
            floatingView.setVisibility(View.GONE);
        }
    };

    private Runnable hideLightRunnable = () -> {
        if (llTrafficLightGroup != null && llTrafficLightGroup.getVisibility() == View.VISIBLE) {
            llTrafficLightGroup.setVisibility(View.GONE);
        }
    };

    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.shadow.automap.UPDATE_SETTINGS".equals(intent.getAction())) {
                applySettings();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("NaviSettings", MODE_PRIVATE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        initLayoutParams();
        switchLayoutIfNeeded(0);

        registerNaviReceiver();

        IntentFilter filter = new IntentFilter("com.shadow.automap.UPDATE_SETTINGS");
        registerReceiver(settingsReceiver, filter);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initLayoutParams() {
        layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        layoutParams.format = PixelFormat.RGBA_8888;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        layoutParams.gravity = Gravity.TOP | Gravity.START;

        layoutParams.x = prefs.getInt("window_x", 50);
        layoutParams.y = prefs.getInt("window_y", 150);
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
    }

    private void switchLayoutIfNeeded(int icon) {
        int targetType;
        if (icon == 0) {
            targetType = LAYOUT_CRUISE;
        } else {
            int savedStyleId = prefs.getInt("navi_style_id", R.id.rb_style_standard);
            targetType = (savedStyleId == R.id.rb_style_minimal) ? LAYOUT_NAVI_MINIMAL : LAYOUT_NAVI_STANDARD;
        }

        if (currentLayoutType == targetType && floatingView != null) return;
        currentLayoutType = targetType;

        // 清理红绿灯看门狗和闪烁动画，防止旧回调影响新布局
        lightWatchdogHandler.removeCallbacksAndMessages(null);
        handleBlinkAnimation(false);

        if (floatingView != null) windowManager.removeView(floatingView);

        int layoutRes = (targetType == LAYOUT_CRUISE) ? R.layout.layout_floating_cruise :
                (targetType == LAYOUT_NAVI_MINIMAL ? R.layout.layout_floating_navi_minimal : R.layout.layout_floating_navi);

        floatingView = LayoutInflater.from(this).inflate(layoutRes, null);
        rebindViewReferences();
        setupInteractions();
        applySettings();
        windowManager.addView(floatingView, layoutParams);
        floatingView.setVisibility(View.GONE);

        // 布局切换后从缓存恢复数据
        if (lastNaviData != null) applyNaviData(lastNaviData);
        if (lastLightData != null) applyLightData(lastLightData);
    }

    private void applySettings() {
        if (floatingView == null) return;

        int windowProgress = prefs.getInt("window_size", 35);
        float scale = 0.5f + (windowProgress / 100.0f) * 0.5f;
        floatingView.setScaleX(scale);
        floatingView.setScaleY(scale);
        floatingView.setPivotX(0);
        floatingView.setPivotY(0);

        View rootLayout = floatingView.findViewById(R.id.root_layout);
        if (rootLayout != null) {
            int themeId = prefs.getInt("theme_id", R.id.rb_theme_dark);
            if (themeId == R.id.rb_theme_dark) rootLayout.setBackgroundResource(R.drawable.bg_floating_dark);
            else if (themeId == R.id.rb_theme_blue) rootLayout.setBackgroundResource(R.drawable.bg_theme_blue);
            else if (themeId == R.id.rb_theme_orange) rootLayout.setBackgroundResource(R.drawable.bg_theme_orange);
            else if (themeId == R.id.rb_theme_red) rootLayout.setBackgroundResource(R.drawable.bg_theme_red);
            else if (themeId == R.id.rb_theme_purple) rootLayout.setBackgroundResource(R.drawable.bg_theme_purple);
            else if (themeId == R.id.rb_theme_green) rootLayout.setBackgroundResource(R.drawable.bg_theme_green);
        }
    }

    private void rebindViewReferences() {
        tvDistanceNum = floatingView.findViewById(R.id.tv_distance_num);
        if (tvDistanceNum == null) tvDistanceNum = floatingView.findViewById(R.id.tv_distance_num_min);
        tvDistanceUnit = floatingView.findViewById(R.id.tv_distance_unit);
        if (tvDistanceUnit == null) tvDistanceUnit = floatingView.findViewById(R.id.tv_distance_unit_min);
        tvAction = floatingView.findViewById(R.id.tv_action);
        tvRoadName = floatingView.findViewById(R.id.tv_road_name);
        if (tvRoadName == null) tvRoadName = floatingView.findViewById(R.id.tv_road_name_min);
        if (tvRoadName == null) tvRoadName = floatingView.findViewById(R.id.tv_cruise_road_name);
        ivTurnIcon = floatingView.findViewById(R.id.iv_turn_icon);
        if (ivTurnIcon == null) ivTurnIcon = floatingView.findViewById(R.id.iv_action_icon_min);
        tvCruiseSpeed = floatingView.findViewById(R.id.tv_cruise_speed);
        tvCameraWarning = floatingView.findViewById(R.id.tv_camera_warning);
        tvCameraDesc = floatingView.findViewById(R.id.tv_camera_desc);
        llTrafficLightGroup = floatingView.findViewById(R.id.ll_traffic_light_group);
        ivLightIcon = floatingView.findViewById(R.id.iv_light_icon);
        ivLightArrow = floatingView.findViewById(R.id.iv_light_arrow);
        tvLightTime = floatingView.findViewById(R.id.tv_light_time);
        tvSummary = floatingView.findViewById(R.id.tv_summary);
        tvEta = floatingView.findViewById(R.id.tv_eta);
        pbRoute = floatingView.findViewById(R.id.pb_route);
    }

    private void setupInteractions() {
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isLongPressTriggered = false;
                        longPressHandler.postDelayed(longPressRunnable, 800);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(event.getRawX() - initialTouchX);
                        float dy = Math.abs(event.getRawY() - initialTouchY);
                        if (dx > 10 || dy > 10) {
                            longPressHandler.removeCallbacks(longPressRunnable);
                            if (isLocked || isLongPressTriggered) return true;
                            layoutParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                            layoutParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(floatingView, layoutParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        longPressHandler.removeCallbacks(longPressRunnable);
                        prefs.edit().putInt("window_x", layoutParams.x).putInt("window_y", layoutParams.y).apply();
                        return true;
                }
                return false;
            }
        });
    }

    private Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            isLongPressTriggered = true;
            isLocked = !isLocked;
            Toast.makeText(FloatingNaviService.this, isLocked ? "悬浮窗已锁定 🔒" : "悬浮窗已解锁 🔓", Toast.LENGTH_SHORT).show();
        }
    };

    public void updateNaviInfo(String disNum, String disUnit, String actionStr, String roadName, String summaryStr, String etaStr, int progressPercentage, int turnIconId, String speed, int cameraDist) {
        if (floatingView == null) {
            Toast.makeText(this, "布局未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 缓存数据（布局切换时自动从缓存恢复）
        lastNaviData = new NaviDataCache();
        lastNaviData.disNum = disNum;
        lastNaviData.disUnit = disUnit;
        lastNaviData.actionStr = actionStr;
        lastNaviData.roadName = roadName;
        lastNaviData.summaryStr = summaryStr;
        lastNaviData.etaStr = etaStr;
        lastNaviData.progressPercentage = progressPercentage;
        lastNaviData.turnIconId = turnIconId;
        lastNaviData.speed = speed;
        lastNaviData.cameraDist = cameraDist;

        // 2. 确保布局正确（同步切换，不再 post 异步）
        switchLayoutIfNeeded(turnIconId == 0 ? 0 : 1);

        // 3. 将数据应用到当前视图
        applyNaviData(lastNaviData);

        // 4. 重启看门狗
        mainHandler.removeCallbacks(hideMainRunnable);
        mainHandler.postDelayed(hideMainRunnable, MAIN_TIMEOUT_MS);
    }

    private void applyNaviData(NaviDataCache data) {
        if (floatingView == null) return;

        // 先处理可见性
        if (floatingView.getVisibility() != View.VISIBLE) {
            if (data.turnIconId != 0) {
                // 导航态：有剩余距离就显示
                if (!"0".equals(data.disNum)) floatingView.setVisibility(View.VISIBLE);
            } else {
                // 巡航态：只要收到数据就显示
                floatingView.setVisibility(View.VISIBLE);
            }
        }

        if (currentLayoutType == LAYOUT_CRUISE) {
            if (tvCruiseSpeed != null) tvCruiseSpeed.setText(data.speed);
            if (tvRoadName != null) tvRoadName.setText(data.roadName);
            if (tvCameraWarning != null && tvCameraDesc != null) {
                if (data.cameraDist > 0) {
                    tvCameraWarning.setText(String.valueOf(data.cameraDist));
                    tvCameraWarning.setVisibility(View.VISIBLE);
                    tvCameraDesc.setVisibility(View.VISIBLE);
                } else {
                    tvCameraWarning.setVisibility(View.GONE);
                    tvCameraDesc.setVisibility(View.GONE);
                }
            }
        } else {
            if (tvDistanceNum != null) tvDistanceNum.setText(data.disNum);
            if (tvDistanceUnit != null) tvDistanceUnit.setText(data.disUnit);
            if (tvAction != null) tvAction.setText(data.actionStr);
            if (tvRoadName != null) tvRoadName.setText(data.roadName);
            if (tvSummary != null) tvSummary.setText(data.summaryStr);
            if (tvEta != null) tvEta.setText(data.etaStr);
            if (pbRoute != null) pbRoute.setProgress(data.progressPercentage);
            updateTurnIcon(data.turnIconId);
        }
    }

    private void updateTurnIcon(int iconId) {
        if (ivTurnIcon == null) return;
        int resId;
        switch (iconId) {
            case 2: resId = R.mipmap.ic_navi_left; break;
            case 3: resId = R.mipmap.ic_navi_right; break;
            case 4: resId = R.mipmap.ic_navi_left_d; break;
            case 5: resId = R.mipmap.ic_navi_right_d; break;
            case 8: resId = R.mipmap.ic_navi_u_turn; break;
            case 9: resId = R.mipmap.ic_navi_straight; break;
            case 10: resId = R.mipmap.ic_navi_mid; break;
            case 11: resId = R.mipmap.ic_navi_in_dao; break;
            case 12: resId = R.mipmap.ic_navi_en_dao; break;
            case 15: resId = R.mipmap.ic_navi_end; break;
            default: resId = R.mipmap.ic_navi_straight; break;
        }
        ivTurnIcon.setImageResource(resId);
    }

    public void updateTrafficLight(int status, int seconds, int dir) {
        if (floatingView == null || llTrafficLightGroup == null) return;

        // 缓存数据
        lastLightData = new LightDataCache();
        lastLightData.status = status;
        lastLightData.seconds = seconds;
        lastLightData.dir = dir;

        applyLightData(lastLightData);
    }

    private void applyLightData(LightDataCache data) {
        if (floatingView == null || llTrafficLightGroup == null) return;

        if (data.seconds <= 0 && data.status != 1 && data.status != 4) {
            llTrafficLightGroup.setVisibility(View.GONE);
            handleBlinkAnimation(false);
            return;
        }

        llTrafficLightGroup.setVisibility(View.VISIBLE);
        if (tvLightTime != null) tvLightTime.setText(data.seconds > 0 ? String.valueOf(data.seconds) : "");

        if (ivLightArrow != null) {
            if (data.dir == DIR_STRAIGHT) ivLightArrow.setImageResource(R.mipmap.ic_navi_straight);
            else if (data.dir == DIR_LEFT) ivLightArrow.setImageResource(R.mipmap.ic_navi_left);
            else if (data.dir == DIR_U_TURN) ivLightArrow.setImageResource(R.mipmap.ic_navi_u_turn);
            ivLightArrow.setVisibility(data.dir == DIR_NONE ? View.GONE : View.VISIBLE);
        }

        switch (data.status) {
            case 1:
                ivLightIcon.setImageResource(R.mipmap.icon_red);
                if (tvLightTime != null) tvLightTime.setTextColor(Color.parseColor("#FF4D4D"));
                handleBlinkAnimation(false);
                break;
            case 4:
                ivLightIcon.setImageResource(R.mipmap.icon_green);
                if (tvLightTime != null) tvLightTime.setTextColor(Color.parseColor("#00FA9A"));
                handleBlinkAnimation(data.seconds > 0 && data.seconds <= 3);
                break;
            default:
                ivLightIcon.setImageResource(R.mipmap.icon_yellow);
                if (tvLightTime != null) {
                    tvLightTime.setTextColor(Color.parseColor("#ebb537"));
                    tvLightTime.setText("注意");
                }
                handleBlinkAnimation(false);
                break;
        }

        lightWatchdogHandler.removeCallbacks(hideLightRunnable);
        lightWatchdogHandler.postDelayed(hideLightRunnable, LIGHT_TIMEOUT_MS);
    }

    private void handleBlinkAnimation(boolean shouldBlink) {
        if (shouldBlink) {
            if (blinkAnimator == null || !blinkAnimator.isRunning()) {
                blinkAnimator = ObjectAnimator.ofFloat(llTrafficLightGroup, "alpha", 1f, 0.2f, 1f);
                blinkAnimator.setDuration(1000);
                blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
                blinkAnimator.start();
            }
        } else {
            if (blinkAnimator != null) {
                blinkAnimator.cancel();
                blinkAnimator = null;
            }
            if (llTrafficLightGroup != null) llTrafficLightGroup.setAlpha(1f);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "导航悬浮窗",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于保持导航悬浮窗前台服务运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("AutoMap 导航辅助")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void registerNaviReceiver() {
        amapNaviReceiver = new AmapNaviReceiver();
        IntentFilter filter = new IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(amapNaviReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(amapNaviReceiver, filter);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (floatingView != null) windowManager.removeView(floatingView);
        unregisterReceiver(amapNaviReceiver);
        unregisterReceiver(settingsReceiver);
        mainHandler.removeCallbacksAndMessages(null);
        longPressHandler.removeCallbacksAndMessages(null);
        // 🔥 修复：销毁时清理专属看门狗
        lightWatchdogHandler.removeCallbacksAndMessages(null);
    }
}