package com.google.ai.sample.screenshot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

import androidx.core.util.Pair;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String RESULT_CODE = "RESULT_CODE";
    private static final String DATA = "DATA";
    private static final String ACTION = "ACTION";
    private static final String START = "START";
    private static final String STOP = "STOP";
    private static final String SCREENCAP_NAME = "screencap";

    private static int IMAGES_PRODUCED;

    private MediaProjection mMediaProjection;
    private String mStoreDir;
    private ImageReader mImageReader;
    private Handler mHandler;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;
    
    // Callback for screenshot capture
    private ScreenshotCallback mScreenshotCallback;
    
    // Binder for service connection
    private final IBinder mBinder = new LocalBinder();
    
    // Interface for screenshot callback
    public interface ScreenshotCallback {
        void onScreenshotTaken(Bitmap bitmap);
    }
    
    // Binder class for clients to access this service
    public class LocalBinder extends Binder {
        public ScreenCaptureService getService() {
            return ScreenCaptureService.this;
        }
    }

    public static Intent getStartIntent(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, START);
        intent.putExtra(RESULT_CODE, resultCode);
        intent.putExtra(DATA, data);
        return intent;
    }

    public static Intent getStopIntent(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, STOP);
        return intent;
    }

    private static boolean isStartCommand(Intent intent) {
        return intent.hasExtra(RESULT_CODE) && intent.hasExtra(DATA)
                && intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), START);
    }

    private static boolean isStopCommand(Intent intent) {
        return intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), STOP);
    }

    private static int getVirtualDisplayFlags() {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    }

    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            FileOutputStream fos = null;
            Bitmap bitmap = null;
            try (Image image = mImageReader.acquireLatestImage()) {
                if (image != null) {
                    Image.Plane[] planes = image.getPlanes();
                    ByteBuffer buffer = planes[0].getBuffer();
                    int pixelStride = planes[0].getPixelStride();
                    int rowStride = planes[0].getRowStride();
                    int rowPadding = rowStride - pixelStride * mWidth;

                    // create bitmap
                    bitmap = Bitmap.createBitmap(mWidth + rowPadding / pixelStride, mHeight, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(buffer);

                    // write bitmap to a file
                    fos = new FileOutputStream(mStoreDir + "/myscreen_" + IMAGES_PRODUCED + ".png");
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);

                    IMAGES_PRODUCED++;
                    Log.d(TAG, "captured image: " + IMAGES_PRODUCED);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                }

                if (bitmap != null) {
                    bitmap.recycle();
                }
            }
        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.d(TAG, "stopping projection.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    mMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // create store dir
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.getAbsolutePath() + "/screenshots/";
            File storeDirectory = new File(mStoreDir);
            if (!storeDirectory.exists()) {
                boolean success = storeDirectory.mkdirs();
                if (!success) {
                    Log.e(TAG, "failed to create file storage directory.");
                    stopSelf();
                }
            }
        } else {
            Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
            stopSelf();
        }

        // start capture handling thread
        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // CRITICAL: Start foreground immediately to avoid ANR
        Pair<Integer, Notification> notification = NotificationUtils.getNotification(this);
        startForeground(notification.first, notification.second);
        
        if (intent != null) {
            if (isStartCommand(intent)) {
                // start projection
                int resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED);
                Intent data = intent.getParcelableExtra(DATA);
                startProjection(resultCode, data);
            } else if (isStopCommand(intent)) {
                stopProjection();
                stopSelf();
            } else {
                stopSelf();
            }
        } else {
            Log.e(TAG, "Intent is null");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopProjection();
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mMediaProjection == null) {
            mMediaProjection = mpManager.getMediaProjection(resultCode, data);
            if (mMediaProjection != null) {
                // display metrics
                mDensity = Resources.getSystem().getDisplayMetrics().densityDpi;
                WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                mDisplay = windowManager.getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(this);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                mMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
            }
        }
    }

    private void stopProjection() {
        if (mHandler != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mMediaProjection != null) {
                        mMediaProjection.stop();
                    }
                }
            });
        }
    }

    @SuppressLint("WrongConstant")
    private void createVirtualDisplay() {
        // get width and height
        mWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        mHeight = Resources.getSystem().getDisplayMetrics().heightPixels;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight,
                mDensity, getVirtualDisplayFlags(), mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }
    
    /**
     * Takes a screenshot and returns it via callback
     * @param callback The callback to receive the screenshot bitmap
     */
    public void takeScreenshot(final ScreenshotCallback callback) {
        if (mMediaProjection == null) {
            Log.e(TAG, "MediaProjection not initialized");
            callback.onScreenshotTaken(null);
            return;
        }
        
        try {
            // Create a new image reader for this specific screenshot
            final ImageReader screenshotReader = ImageReader.newInstance(
                mWidth,
                mHeight,
                PixelFormat.RGBA_8888,
                1
            );
            
            // Create a temporary virtual display for this screenshot
            final VirtualDisplay screenshotDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenshotCapture",
                mWidth,
                mHeight,
                mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                screenshotReader.surface,
                null,
                mHandler
            );
            
            // Set up a one-time listener for the image
            screenshotReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    Bitmap bitmap = null;
                    
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            Image.Plane[] planes = image.getPlanes();
                            ByteBuffer buffer = planes[0].getBuffer();
                            int pixelStride = planes[0].getPixelStride();
                            int rowStride = planes[0].getRowStride();
                            int rowPadding = rowStride - pixelStride * mWidth;
                            
                            // Create bitmap
                            bitmap = Bitmap.createBitmap(
                                mWidth + rowPadding / pixelStride,
                                mHeight,
                                Bitmap.Config.ARGB_8888
                            );
                            bitmap.copyPixelsFromBuffer(buffer);
                            
                            // Crop bitmap if needed
                            if (bitmap.width > mWidth || bitmap.height > mHeight) {
                                bitmap = Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    mWidth,
                                    mHeight
                                );
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error capturing screenshot: " + e.getMessage());
                        bitmap = null;
                    } finally {
                        // Clean up resources
                        if (image != null) {
                            image.close();
                        }
                        screenshotDisplay.release();
                        screenshotReader.close();
                        
                        // Return the bitmap via callback
                        final Bitmap finalBitmap = bitmap;
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                callback.onScreenshotTaken(finalBitmap);
                            }
                        });
                    }
                }
            }, mHandler);
            
            // Add a timeout to prevent hanging
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        screenshotDisplay.release();
                        screenshotReader.close();
                        callback.onScreenshotTaken(null);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in timeout handler: " + e.getMessage());
                    }
                }
            }, 3000); // 3 second timeout
            
        } catch (Exception e) {
            Log.e(TAG, "Error taking screenshot: " + e.getMessage());
            callback.onScreenshotTaken(null);
        }
    }
    
    /**
     * Saves a bitmap to a file
     * @param bitmap The bitmap to save
     * @return The file where the bitmap was saved, or null if saving failed
     */
    public File saveBitmapToFile(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "Cannot save null bitmap");
            return null;
        }
        
        FileOutputStream fos = null;
        File imageFile = null;
        
        try {
            // Create a unique filename
            String filename = "screenshot_" + UUID.randomUUID().toString() + ".jpg";
            imageFile = new File(mStoreDir, filename);
            
            // Ensure directory exists
            File parent = imageFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            // Save the bitmap
            fos = new FileOutputStream(imageFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            
            Log.d(TAG, "Saved screenshot to " + imageFile.getAbsolutePath());
            return imageFile;
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving bitmap: " + e.getMessage());
            if (imageFile != null && imageFile.exists()) {
                imageFile.delete();
            }
            return null;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing file output stream: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Checks if MediaProjection is ready
     * @return true if MediaProjection is initialized and ready, false otherwise
     */
    public boolean isMediaProjectionReady() {
        return mMediaProjection != null;
    }
}
