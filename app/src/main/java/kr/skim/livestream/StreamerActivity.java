/*
 * Copyright (c) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package kr.skim.livestream;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;

import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.rtplibrary.rtmp.RtmpCamera2;

import net.ossrs.rtmp.ConnectCheckerRtmp;

import kr.skim.livestream.util.YouTubeApi;


/**
 * @author Ibrahim Ulukaya <ulukaya@google.com>
 * <p/>
 * StreamerActivity class which previews the camera and streams via StreamerService.
 */
public class StreamerActivity extends Activity {
    // CONSTANTS
    // TODO: Stop hardcoding this and read values from the camera's supported sizes.
    public static final int CAMERA_WIDTH = 1920;
    public static final int CAMERA_HEIGHT = 1080;
    private static final int REQUEST_CAMERA_MICROPHONE = 0;

    private TextureView textureView;
    private RtmpCamera2 camera2;
    private StreamerService streamerService;
    private PowerManager.WakeLock wakeLock;
    private String rtmpUrl;
    private ServiceConnection streamerConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(MainActivity.APP_NAME, "onServiceConnected");

            streamerService = ((StreamerService.LocalBinder) service).getService();

            int rotation = CameraHelper.getCameraOrientation(getApplicationContext());
            if (camera2.prepareAudio() && camera2.prepareVideo(CAMERA_WIDTH, CAMERA_HEIGHT, 30, 1200 * 1024, false, rotation))
                startStreaming();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(MainActivity.APP_NAME, "onServiceDisconnected");

            // This should never happen, because our service runs in the same process.
            streamerService = null;
        }
    };
    private String broadcastId;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(MainActivity.APP_NAME, "onCreate");
        super.onCreate(savedInstanceState);

        broadcastId = getIntent().getStringExtra(YouTubeApi.BROADCAST_ID_KEY);
        //Log.v(MainActivity.APP_NAME, broadcastId);

        rtmpUrl = getIntent().getStringExtra(YouTubeApi.RTMP_URL_KEY);

        if (rtmpUrl == null) {
            Log.w(MainActivity.APP_NAME, "No RTMP URL was passed in; bailing.");
            finish();
        }
        Log.i(MainActivity.APP_NAME, String.format("Got RTMP URL '%s' from calling activity.", rtmpUrl));

        setContentView(R.layout.streamer);

        textureView = (TextureView) findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                int viewWidth = 1280;
                int viewHeight = 720;
                Matrix matrix = new Matrix();
                RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
                RectF bufferRect = new RectF(0, 0, CAMERA_HEIGHT, CAMERA_WIDTH);
                float centerX = viewRect.centerX();
                float centerY = viewRect.centerY();
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                matrix.postScale(0.5f, 0.5f, centerX, centerY);
                matrix.postRotate(-90, centerX, centerY);
                textureView.setTransform(matrix);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                camera2.startPreview();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (camera2.isStreaming()) {
                    camera2.stopStream();
                }
                camera2.stopPreview();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
        camera2 = new RtmpCamera2(textureView, new ConnectCheckerRtmp() {
            @Override
            public void onConnectionSuccessRtmp() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StreamerActivity.this, "Connection success", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onConnectionFailedRtmp(@NonNull final String reason) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StreamerActivity.this, "Connection failed. " + reason, Toast.LENGTH_SHORT).show();
                        camera2.stopStream();
                    }
                });
            }

            @Override
            public void onNewBitrateRtmp(long bitrate) {

            }

            @Override
            public void onDisconnectRtmp() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StreamerActivity.this, "Disconnected", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onAuthErrorRtmp() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StreamerActivity.this, "Auth error", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onAuthSuccessRtmp() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StreamerActivity.this, "Auth success", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        if (!bindService(new Intent(this, StreamerService.class), streamerConnection,
                BIND_AUTO_CREATE | BIND_DEBUG_UNBIND)) {
            Log.e(MainActivity.APP_NAME, "Failed to bind StreamerService!");
        }

        final ToggleButton toggleButton = (ToggleButton) findViewById(R.id.toggleBroadcasting);
        toggleButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (toggleButton.isChecked()) {
                    camera2.startStream(rtmpUrl);
                    streamerService.startStreaming();
                } else {
                    camera2.stopStream();
                    streamerService.stopStreaming();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        Log.d(MainActivity.APP_NAME, "onResume");

        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(MainActivity.APP_NAME, "onPause");

        super.onPause();

        if (camera2 != null) {
            camera2.stopPreview();
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(MainActivity.APP_NAME, "onDestroy");

        super.onDestroy();

        if (streamerConnection != null) {
            unbindService(streamerConnection);
        }

        stopStreaming();

        if (camera2 != null) {
            camera2.stopPreview();
        }
    }

    private void startStreaming() {
        Log.d(MainActivity.APP_NAME, "startStreaming");

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, this.getClass().getName());
        wakeLock.acquire();

        streamerService.startStreaming();

        if (camera2 != null) {
            camera2.startStream(rtmpUrl);
        }
    }

    private void stopStreaming() {
        Log.d(MainActivity.APP_NAME, "stopStreaming");

        if (wakeLock != null) {
            wakeLock.release();
            wakeLock = null;
        }

        streamerService.stopStreaming();
    }

    public void endEvent(View view) {
        Intent data = new Intent();
        data.putExtra(YouTubeApi.BROADCAST_ID_KEY, broadcastId);
        if (getParent() == null) {
            setResult(Activity.RESULT_OK, data);
        } else {
            getParent().setResult(Activity.RESULT_OK, data);
        }
        finish();
    }

}
