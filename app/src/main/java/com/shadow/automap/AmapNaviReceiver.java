package com.shadow.automap; // 确保这是你的包名

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class AmapNaviReceiver extends BroadcastReceiver {

    private static final String TAG = "AmapNavi";


    @Override
    public void onReceive(Context context, Intent intent) {
        // 过滤高德标准发送 Action
        if ("AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction())) {

            int keyType = intent.getIntExtra("KEY_TYPE", 0);
            Log.d(TAG, "========== 🚥所有原始数据包==========");
            android.os.Bundle bundle = intent.getExtras();
            if (bundle != null) {
                // 遍历打印这个包裹里的所有字段名、值和数据类型
                for (String key : bundle.keySet()) {
                    Object value = bundle.get(key);
                    Log.d(TAG, "Key: " + key + " | Value: " + value + " | Type: " + (value != null ? value.getClass().getSimpleName() : "null"));
                }
            }
            Log.d(TAG, "==========================================================");
            if (keyType == 60073) {//红绿灯数据包
//                Log.d(TAG, "========== 🚥 捕获到红绿灯原始数据包 (60073) ==========");
//                android.os.Bundle bundle = intent.getExtras();
//                if (bundle != null) {
//                    // 遍历打印这个包裹里的所有字段名、值和数据类型
//                    for (String key : bundle.keySet()) {
//                        Object value = bundle.get(key);
//                        Log.d(TAG, "Key: " + key + " | Value: " + value + " | Type: " + (value != null ? value.getClass().getSimpleName() : "null"));
//                    }
//                }
//                Log.d(TAG, "==========================================================");

                int status = intent.getIntExtra("trafficLightStatus", 0);

                // 🔥 铁证如山：不管什么灯，当前倒计时永远在这个字段里，直接用它！
                int displaySeconds = intent.getIntExtra("redLightCountDownSeconds", 0);

                int dir = intent.getIntExtra("dir",4);
                if (FloatingNaviService.instance != null) {
                    FloatingNaviService.instance.updateTrafficLight(status, displaySeconds, dir);
                }
            }

            // ================== 2. 抓取常规导航数据 (距离、时间、路名等) ==================
            // 只要 intent 里携带了距离字段，就认定这是导航核心数据包
//            if (intent.hasExtra("SEG_REMAIN_DIS") || intent.hasExtra("ROUTE_REMAIN_DIS")) {
            if (keyType == 10001) {
                // 🔥 抓包逻辑：只在第一次遇到主包时，把它里面的所有字段全打印出来
//                    Log.d(TAG, "========== 🧭 捕获到主导航数据包 (全量字段) ==========");
//                    android.os.Bundle bundle = intent.getExtras();
//                    if (bundle != null) {
//                        for (String key : bundle.keySet()) {
//                            Object value = bundle.get(key);
//                            Log.d(TAG, "Main-Key: " + key + " | Value: " + value + " | Type: " + (value != null ? value.getClass().getSimpleName() : "null"));
//                        }
//                    }
//                    Log.d(TAG, "==========================================================");
                // 1. 🔥 直接拿高德的原生格式化字符串，不自己算！
                String segRemainDisAuto = intent.getStringExtra("SEG_REMAIN_DIS_AUTO");     // "847米" 或 "1.1公里"
                String routeRemainDisAuto = intent.getStringExtra("ROUTE_REMAIN_DIS_AUTO"); // "1.1公里"
                String routeRemainTimeAuto = intent.getStringExtra("ROUTE_REMAIN_TIME_AUTO"); // "6分钟"
                String etaText = intent.getStringExtra("ETA_TEXT");                         // "预计16:36到达"
                String nextRoadName = intent.getStringExtra("NEXT_ROAD_NAME");              // "坂雪岗大道"
                String curRoadName = intent.getStringExtra("CUR_ROAD_NAME");              // 当前道路
                int cameraDist = intent.getIntExtra("CAMERA_DIST",0);              // 当前道路
                int curSpeed = intent.getIntExtra("CUR_SPEED",0);              // 当前速度
                // 🔥 核心修正：优先拿 NEW_ICON
                int icon = intent.getIntExtra("NEW_ICON", 0);
                if (icon == 0) {
                    icon = intent.getIntExtra("ICON", 0);
                }
                // --- 安全兜底防空指针 ---
                if (segRemainDisAuto == null) segRemainDisAuto = "0米";
                if (routeRemainDisAuto == null) routeRemainDisAuto = "0公里";
                if (routeRemainTimeAuto == null) routeRemainTimeAuto = "0分钟";
                if (nextRoadName == null) nextRoadName = curRoadName;
                if (nextRoadName==null) nextRoadName = "未知道路";

                // 2. 🔥 智能拆分距离与单位 (因为我们的 UI 是数字大、单位小，必须拆开)
                String disNum = "0";
                String disUnit = "米";
                if (segRemainDisAuto.endsWith("公里")) {
                    disNum = segRemainDisAuto.replace("公里", ""); // 抽出数字 "1.1"
                    disUnit = "公里";
                } else if (segRemainDisAuto.endsWith("米")) {
                    disNum = segRemainDisAuto.replace("米", "");   // 抽出数字 "847"
                    disUnit = "米";
                } else {
                    disNum = segRemainDisAuto; // 兜底
                }

                // 3. 🔥 直接拼装底部 Summary 文本，原汁原味
                String summaryStr = routeRemainDisAuto + " · " + routeRemainTimeAuto;

                // 4. 定义动作文字
                String actionStr = "进入";

                // 5. 进度条计算
                int routeRemainDis = intent.getIntExtra("ROUTE_REMAIN_DIS", 0);
                int routeAllDis = intent.getIntExtra("ROUTE_ALL_DIS", 1);
                int progressPercentage = 0;
                if (routeAllDis > 0) {
                    progressPercentage = (int) ((1.0f - (float) routeRemainDis / routeAllDis) * 100);
                }

                // 6. 发送给 Service 更新 UI
                if (FloatingNaviService.instance != null) {
                    FloatingNaviService.instance.updateNaviInfo(
                            disNum, disUnit, actionStr, nextRoadName, summaryStr, etaText, progressPercentage, icon,String.valueOf(curSpeed),cameraDist
                    );
                }



            }
        }
    }

    /**
     * 实用工具：把米转换成带单位的易读格式 (用于底部总里程)
     */
    private String formatDistance(int meters) {
        if (meters >= 1000) {
            return String.format(Locale.getDefault(), "%.1f公里", meters / 1000.0f);
        } else {
            return meters + "米";
        }
    }
}