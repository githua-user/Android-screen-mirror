package com.example.screenmirror;

import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;


import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class ScreenCapture {
    private static final int CAPTURE_FPS = 30;
    private static final long FRAME_INTERVAL_MS = 1000 / CAPTURE_FPS;
    private int mWidth, mHeight;
    private int widtt, heitt;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    private ImageReader  mImageReaderPortrait;
    private ImageReader  mImageReaderLandscape;
    private OnFrameCapturedListener mListener;
    private HandlerThread mCaptureThread;
    private Handler mCaptureHandler;
    private Handler mMainHandler = new Handler(android.os.Looper.getMainLooper());
    private byte[] lastJpeg = null;
    // 帧率控制
    private long mLastFrameTime = 0;

    public interface OnFrameCapturedListener {
        void onFrameCaptured(byte[] rgbData);
    }
    public ScreenCapture(int width, int height, MediaProjection projection) {
        this.mWidth = width;
        this.mHeight = height;
        this.mMediaProjection = projection;
    }
    public void setFrameListener(OnFrameCapturedListener listener) {
        this.mListener = listener;
    }

    public void updateSize(int kk) {
        if(kk==11){
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay.resize(720,1280,240);
            mVirtualDisplay.setSurface(mImageReaderPortrait.getSurface());
            }
        else if(kk==22){
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay.resize(1280,720,240);
            mVirtualDisplay.setSurface(mImageReaderLandscape.getSurface());
            Log.e("TAG","oobbjj");}
    }

    public void startCapture() {
        widtt=1;heitt=2;
        // Android 14+ 注册回调
        registerProjectionCallback();
        // 创建捕获线程
        mCaptureThread = new HandlerThread("ScreenCaptureThread");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        try {
            mImageReaderPortrait = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
            mImageReaderPortrait.setOnImageAvailableListener(this::onImageAvailable, mCaptureHandler);
            mImageReaderLandscape = ImageReader.newInstance(mHeight, mWidth, PixelFormat.RGBA_8888, 2);
            mImageReaderLandscape.setOnImageAvailableListener(this::onImageAvailable, mCaptureHandler);
            mMainHandler.post(() -> {
                try {
                    createVirtualDisplay();
                } catch (Exception e) {
                }
            });
        } catch (Exception e) {
        }
    }
    private void createVirtualDisplay() {

        if (mMediaProjection == null) {return;}
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenCapture",
                mWidth, mHeight, 240,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReaderPortrait.getSurface(),
                null, null);
    }

    private void registerProjectionCallback() {
        if (Build.VERSION.SDK_INT < 34) return;
        CountDownLatch latch = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                mMediaProjection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                    }
                }, null);
            } catch (Exception e) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    private void onImageAvailable(ImageReader reader) {
        // 帧率控制
        long currentTime = System.currentTimeMillis();
        if (currentTime - mLastFrameTime < FRAME_INTERVAL_MS) {
            discardImage(reader);
            return;
        }
        mLastFrameTime = currentTime;
        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) return;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width != widtt || height != heitt) {
                widtt = width;
                heitt = height;
                Log.d("TAG", "图像尺寸变化oobbjj: " + width + "x" + height);
            }

            byte[] jpeg = convertToJpeg(image, 50);
            if (jpeg != null && mListener != null) {
                // 画面没变化就不发（首帧 lastJpeg==null 必须发）
                if (lastJpeg == null || !Arrays.equals(lastJpeg, jpeg)) {
                    lastJpeg = jpeg;
                    mListener.onFrameCaptured(jpeg);
                }
            }
        } catch (Exception e) {
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void discardImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();

        } finally {
            if (image != null) image.close();
        }
    }
    private byte[] convertToJpeg(Image image, int quality) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            if (pixelStride == 4 && rowStride == width * 4
                    && buffer.remaining() >= width * height * 4) {
                buffer.rewind();
                bitmap.copyPixelsFromBuffer(buffer);
            } else {
                byte[] rgba = new byte[width * height * 4];
                int idx = 0;
                for (int y = 0; y < height; y++) {
                    int rowOffset = y * rowStride;
                    for (int x = 0; x < width; x++) {
                        int off = rowOffset + x * pixelStride;
                        rgba[idx++] = buffer.get(off);
                        rgba[idx++] = buffer.get(off + 1);
                        rgba[idx++] = buffer.get(off + 2);
                        rgba[idx++] = (byte) 0xFF;
                    }
                }
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream(width * height / 8 + 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)) return null;
            return baos.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    public void stopCapture() {

        if (mCaptureHandler != null) {
            mCaptureHandler.removeCallbacksAndMessages(null);
        }
        if (mCaptureThread != null) {
            try {
                mCaptureThread.quitSafely();
                mCaptureThread.join(1000);
            } catch (Exception e) {
            }
            mCaptureThread = null;
        }
        mCaptureHandler = null;
    }

}