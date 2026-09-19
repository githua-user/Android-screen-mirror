package com.example.screenmirror;
import android.content.res.Configuration;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 1001;
    private EditText etIp, etPort;
    private Button btnStart, btnStop;
    private TextView tvStatus;
    private MediaProjectionManager projectionManager;
    private int resultCode = Activity.RESULT_CANCELED;
    private Intent captureData;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etIp = findViewById(R.id.et_server_ip);
        etPort = findViewById(R.id.et_server_port);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        tvStatus = findViewById(R.id.tv_status);

        etIp.setText("10.0.2.2");
        etPort.setText("8888");

        btnStart.setOnClickListener(v -> requestScreenCapture());
        btnStop.setOnClickListener(v -> stopMirroring());
        btnStop.setEnabled(false);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (!isAccessibilityOn()) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
    }

    private void requestScreenCapture() {
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            this.resultCode = resultCode;
            this.captureData = data;
            startCaptureService();
        } else {
            tvStatus.setText("状态: 权限被拒绝");
        }
    }

    private void startCaptureService() {
        String ip = etIp.getText().toString().trim();
        int port = Integer.parseInt(etPort.getText().toString().trim());

            Intent intent = new Intent(this, ScreenCaptureService.class);
            intent.putExtra("serverIp", ip);
            intent.putExtra("serverPort", port);
            intent.putExtra("resultCode", resultCode);
            intent.putExtra("data", captureData);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent);
            else
                startService(intent);
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
            tvStatus.setText( "状态: 投屏中 🎥");
    }
    private void stopMirroring() {
        stopService(new Intent(this, ScreenCaptureService.class));
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        tvStatus.setText("状态: 已停止");
    }

    // 检测本应用的无障碍服务是否已启用
    private boolean isAccessibilityOn() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null &&
                enabled.contains(getPackageName() + "/" + TouchControlService.class.getName());
    }

    protected void onDestroy() {
        super.onDestroy();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 判断横竖屏
        switch (newConfig.orientation) {
            case Configuration.ORIENTATION_LANDSCAPE:
                Intent intentHeng = new Intent(this, ScreenCaptureService.class);
                int bb=22;
                intentHeng.putExtra("resultCode", bb);
                startService(intentHeng);
                break;
            case Configuration.ORIENTATION_PORTRAIT:
                Intent intentShu = new Intent(this, ScreenCaptureService.class);
                int aa=11;
                intentShu.putExtra("resultCode", aa);
                startService(intentShu);
                break;
            case Configuration.ORIENTATION_UNDEFINED:
                break;
        }
    }
}