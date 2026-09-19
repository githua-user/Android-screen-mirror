package com.example.screenmirror;

import android.util.Log;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;

public class SocketClient {
    private static final String TAG = "SocketClient";
    private static final byte TYPE_SCREEN_INFO = 0x00;
    private static final byte TYPE_VIDEO_FRAME1 = 0x01;
    private static final byte TYPE_VIDEO_FRAME2 = 0x02;
    private static final int SEND_BUFFER_SIZE = 2 * 1024 * 1024;

    private Socket socket;
    private DataOutputStream output;
    private DataInputStream input;  // 新增输入流
    public volatile boolean isConnected = false;
    private String serverIp;
    private int serverPort;
    public boolean fanzhuan;
    private final Object sendLock = new Object();

    public SocketClient(String ip, int port) {
        this.serverIp = ip;
        this.serverPort = port;
        fanzhuan=false;
    }

    public void connect() throws IOException {
        if (socket != null) {
            try { socket.close(); } catch (Exception e) {}
            socket = null;
        }

        socket = new Socket(serverIp, serverPort);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(SEND_BUFFER_SIZE);
        socket.setReceiveBufferSize(SEND_BUFFER_SIZE);
        socket.setKeepAlive(true);
        output = new DataOutputStream(socket.getOutputStream());
        input = new DataInputStream(socket.getInputStream());  // 初始化输入流
        isConnected = true;
    }

    public void sendScreenInfo(int screenWidth,int screenHeight) throws IOException {
        if (!isConnected || output == null) return;
        synchronized (sendLock) {
            ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + 4);
            buffer.put(TYPE_SCREEN_INFO);
            buffer.putInt(screenWidth);
            buffer.putInt(screenHeight);
            output.write(buffer.array());
            output.flush();
        }
    }

    public void sendData(byte[] data) {
        if (!isConnected || output == null || data == null || data.length == 0) return;
        try {
            synchronized (sendLock) {
                ByteBuffer header = ByteBuffer.allocate(1 + 4);
                if(fanzhuan==false)
                    header.put(TYPE_VIDEO_FRAME1);
                else
                    header.put(TYPE_VIDEO_FRAME2);
                header.putInt(data.length);
                output.write(header.array());
                output.write(data);
                output.flush();
            }
        } catch (IOException e) {
            Log.e(TAG, "发送失败: " + e.getMessage());
            isConnected = false;
        }
    }

    public interface OnCommandListener {
        void onTap(float nx, float ny, int durationMs);
        void onSwipe(float nx1, float ny1, float nx2, float ny2, int durationMs);
        void onBackKey(int keyId);
    }

    private static final int CMD_TAP = 0x10;
    private static final int CMD_SWIPE = 0x11;
    private static final int CMD_BACK = 0x12;

    private OnCommandListener commandListener;

    public void setCommandListener(OnCommandListener l) { this.commandListener = l; }

    public void startReceiveLoop() {
        new Thread(() -> {
            try {
                while (isConnected) {
                    byte[] header = new byte[5];
                    input.readFully(header);
                    int type = header[0] & 0xFF;
                    int len = ByteBuffer.wrap(header, 1, 4).getInt();
                    if (len <= 0 || len > 64) throw new IOException("非法长度 " + len);
                    byte[] payload = new byte[len];
                    input.readFully(payload);
                    dispatchCommand(type, payload);
                }
            } catch (IOException e) {
                Log.e(TAG, "接收线程退出: " + e.getMessage());
            }
        }).start();
    }

    private void dispatchCommand(int type, byte[] payload) {
        OnCommandListener l = commandListener;
        if (l == null) return;
        ByteBuffer bb = ByteBuffer.wrap(payload);   // ByteBuffer 默认大端，与发送端一致
        switch (type) {
            case CMD_TAP:
                l.onTap(bb.getFloat(), bb.getFloat(), bb.getInt());
                break;
            case CMD_SWIPE:
                l.onSwipe(bb.getFloat(), bb.getFloat(), bb.getFloat(), bb.getFloat(), bb.getInt());
                break;
            case CMD_BACK:
                l.onBackKey(bb.getInt());
                break;
        }
    }

    public void disconnect() {
        isConnected = false;
        try {
            if (input != null) {
                input.close();
                input = null;
            }
            if (output != null) {
                output.close();
                output = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "断开连接失败: " + e.getMessage());
        }
    }
}