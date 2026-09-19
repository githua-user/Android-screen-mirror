package com.example.screenmirror;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class TouchControlService extends AccessibilityService {
    private static volatile TouchControlService instance;
    public static TouchControlService getInstance() { return instance; }
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i("11", "无障碍服务已连接");
    }
    public void onAccessibilityEvent(AccessibilityEvent event) { }  // 不需要事件，留空
    public void onInterrupt() { }
    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // 归一化坐标 -> 真实屏幕像素坐标
    private int[] toPixels(float nx, float ny) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        int px = (int) (nx * dm.widthPixels);
        int py = (int) (ny * dm.heightPixels);
        return new int[]{
                Math.min(Math.max(px, 0), dm.widthPixels - 1),
                Math.min(Math.max(py, 0), dm.heightPixels - 1)
        };
    }

    public void tap(float nx, float ny, long durationMs) {
        int[] p = toPixels(nx, ny);
        Path path = new Path();
        path.moveTo(p[0], p[1]);
        boolean ok =dispatchGesture(buildGesture(path, durationMs), null, null);
        if (!ok) Log.w("11", "dispatchGesture 失败（上一次手势可能未完成）");
    }
    public void swipe(float nx1, float ny1, float nx2, float ny2, long durationMs) {
        int[] a = toPixels(nx1, ny1);
        int[] b = toPixels(nx2, ny2);
        Path path = new Path();
        path.moveTo(a[0], a[1]);
        path.lineTo(b[0], b[1]);
        dispatchGesture(buildGesture(path, durationMs), null, null);
    }

    public void backKey(int keyId) {
        switch (keyId) {
            case 1: performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }
    private GestureDescription buildGesture(Path path, long durationMs) {
        long d = Math.max(durationMs, 1);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, d);
        return new GestureDescription.Builder().addStroke(stroke).build();
    }
}