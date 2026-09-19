package com.example.screenmirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;


public class ScreenCaptureService extends Service {
    private static final String CHANNEL_ID = "screen_mirror";
    private MediaProjection mediaProjection;
    private ScreenCapture screenCapture;
    private SocketClient socketClient;
    private String serverIp;
    private int serverPort;
    private int screenWidth, screenHeight;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "屏幕镜像", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)  {
        int resultCode = intent.getIntExtra("resultCode", -1);
        if(resultCode==-1) {
            startForeground(1001, createNotification("正在初始化..."));
            serverIp = intent.getStringExtra("serverIp");
            serverPort = intent.getIntExtra("serverPort", 8888);
            Log.e("TAG","oobbjj11");
            Intent projectionData = intent.getParcelableExtra("data");
            new Thread(() -> {
                try {
                    getScreenSize();
                    MediaProjectionManager pm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    mediaProjection = pm.getMediaProjection(resultCode, projectionData);
                    socketClient = new SocketClient(serverIp, serverPort);
                    socketClient.connect();

                    createCommandListener();
                    socketClient.sendScreenInfo(screenWidth, screenHeight);
                    mainHandler.post(() -> {
                        try {
                            createScreenCapture();
                        } catch (Exception e) {
                        }
                    });
                } catch (Exception e) {
                }
            }).start();
        }
        else if(resultCode==11)
        {
            this.getScreenSize();
            screenCapture.updateSize(11);
            socketClient.fanzhuan=false;
        }
        else if(resultCode==22)
        {
            this.getScreenSize();
            screenCapture.updateSize(22);
            socketClient.fanzhuan=true;
        }
        return START_NOT_STICKY;

    }
    private void createCommandListener() {
        socketClient.setCommandListener(new SocketClient.OnCommandListener() {
            public void onTap(float nx, float ny, int durationMs) {
                TouchControlService svc = TouchControlService.getInstance();
                if (svc != null) svc.tap(nx, ny, durationMs);
            }
            public void onSwipe(float nx1, float ny1, float nx2, float ny2, int durationMs) {
                TouchControlService svc = TouchControlService.getInstance();
                if (svc != null) svc.swipe(nx1, ny1, nx2, ny2, durationMs);
            }
            public void onBackKey(int keyId) {
                TouchControlService svc = TouchControlService.getInstance();
                if (svc != null) svc.backKey(keyId);
            }
        });
        socketClient.startReceiveLoop();
    }
    private void createScreenCapture() {
        try {
            screenCapture = new ScreenCapture(screenWidth, screenHeight, mediaProjection);
            screenCapture.setFrameListener(new ScreenCapture.OnFrameCapturedListener() {
                @Override
                public void onFrameCaptured(byte[] jpgData) {
                    try {
                        if (socketClient != null && socketClient.isConnected) {
                            socketClient.sendData(jpgData);
                        }
                    } catch (Exception e) {
                    }
                }

            });
            screenCapture.startCapture();
            startForeground(1001, createNotification("投屏中 (原始图像)"));
        } catch (Exception e) {
        }
    }
    private void getScreenSize()  {
       /* DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        float scale;
        if (metrics.heightPixels > metrics.widthPixels) {
            int maxWidth = 720;
            scale = Math.min(1.0f, (float) maxWidth / metrics.widthPixels);
        } else {
            int maxHeight = 720;
            scale = Math.min(1.0f, (float) maxHeight / metrics.heightPixels);
        }
        int newWidth = (int) (metrics.widthPixels * scale);
        int newHeight = (int) (metrics.heightPixels * scale);
        screenWidth = (newWidth / 2) * 2;
        screenHeight = (newHeight / 2) * 2; */
        screenWidth=720;
        screenHeight=1280;
    }

    private Notification createNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕镜像")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenCapture != null) {
            try {
                screenCapture.stopCapture();
            } catch (Exception e) {
            }
            screenCapture = null;
        }

        if (socketClient != null) {
            socketClient.disconnect();
            socketClient = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception e) {
            }
            mediaProjection = null;
        }
        stopForeground(true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}