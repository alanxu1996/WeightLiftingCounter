/*
 * Copyright (C) 2016 Yihong Xu. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.yihongxu.weightliftingcounter;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.Timer;
import java.util.TimerTask;


/**
 * The main activity for the Weight Lifting Counter application on wear side. This activity
 * registers itself to receive sensor values. Since on wearable devices a full screen activity is
 * very short-lived, we set the FLAG_KEEP_SCREEN_ON to give user adequate time for taking actions
 * but since we don't want to keep screen on for an extended period of time, there is a
 * SCREEN_ON_TIMEOUT_MS that is enforced if no interaction is discovered.
 *
 * This activity includes a {@link android.support.v4.view.ViewPager} with two pages, one that
 * shows the current count and one that allows user to reset the counter. the current value of the
 * counter is persisted so that upon re-launch, the counter picks up from the last value. At any
 * stage, user can set this counter to 0.
 */
public class MainActivity extends Activity implements
        SensorEventListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = "RSMainActivity";

    /** How long to keep the screen on when no activity is happening **/
    private static final long SCREEN_ON_TIMEOUT_MS = 200000; // in milliseconds

    /** an up-down movement that takes more than this will not be registered as such **/
    private static final long TIME_THRESHOLD_NS = 2000000000; // in nanoseconds (= 2sec)

    /**
     * Earth gravity is around 9.8 m/s^2 but user may not completely direct his/her hand vertical
     * during the exercise so we leave some room. Basically if the x-component of gravity, as
     * measured by the Gravity sensor, changes with a variation (delta) > GRAVITY_THRESHOLD,
     * we consider that a successful count.
     */
    private static final float GRAVITY_THRESHOLD = 8.0f;
    private static final String COUNT_KEY = "com.yihongxu.ropeskippingcounter.count";


    private SensorManager mSensorManager;
    private Sensor mSensor;
    private long mLastTime = 0;
    private boolean mUp = false;
    private int mCounter = 0;
    private ViewPager mPager;
    private CounterFragment mCounterPage;
    private SettingsFragment mSettingPage;
    private ImageView mSecondIndicator;
    private ImageView mFirstIndicator;
    private PutDataMapRequest mPutDataMapReq;
    private Timer mTimer;
    private TimerTask mTimerTask;
    private Handler mHandler;
    private DataMap mDataMap;
    private boolean mPaired = false;

    private GoogleApiClient mGoogleApiClient;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rs_layout);
        setupViews();
        mHandler = new Handler();
        mCounter = Utils.getCounterFromPreference(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renewTimer();
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    }

    public void pairDevice() {
        Utils.vibrate(this, 0);
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();
        mPaired = true;
    }

    @Override
    public void onConnected(Bundle bundle) {
        mPutDataMapReq = PutDataMapRequest.create("/count");
        mDataMap = mPutDataMapReq.getDataMap();
    }

    @Override
    public void onConnectionSuspended(int cause) { }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        mSettingPage.setT("Connection Failed");
        mPaired = false;
    }

    private void setupViews() {
        mPager = (ViewPager) findViewById(R.id.pager);
        mFirstIndicator = (ImageView) findViewById(R.id.indicator_0);
        mSecondIndicator = (ImageView) findViewById(R.id.indicator_1);
        final PagerAdapter adapter = new PagerAdapter(getFragmentManager());
        mCounterPage = new CounterFragment();
        mSettingPage = new SettingsFragment();
        adapter.addFragment(mCounterPage);
        adapter.addFragment(mSettingPage);
        setIndicator(0);
        mPager.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i2) {
            }

            @Override
            public void onPageSelected(int i) {
                setIndicator(i);
                renewTimer();
            }

            @Override
            public void onPageScrollStateChanged(int i) {
            }
        });

        mPager.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mSensorManager.registerListener(this, mSensor,
                SensorManager.SENSOR_DELAY_NORMAL)) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Successfully registered for the sensor updates");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Unregistered for sensor events");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        detectJump(event.values[0], event.values[1], event.values[2], event.timestamp);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    /**
     * A simple algorithm to detect a successful up-down movement of hand(s). The algorithm is
     * based on the assumption that when a person is wearing the watch on his left hand, the
     * x-component of gravity as measured by the Gravity Sensor is >0 when the hand is upward
     * and -9.8 when the hand is upward and less than -gravity when downward.
     * We leave some room and instead of 9.8, we use GRAVITY_THRESHOLD for xValue.
     * We also consider the up <-> down movement successful if it takes less than
     * TIME_THRESHOLD_NS. zValue is for not updating when the user is looking at the watch screen.
     */
    private void detectJump(float xValue, float yValue, float zValue, long timestamp) {
        if (xValue > 0) {
            if ((Math.abs(zValue - 9.8) > 1) && (!mUp || (timestamp - mLastTime > TIME_THRESHOLD_NS))) {
                mUp = true;
                mLastTime = timestamp;
            }
        } else {
            if (mUp) {
                if (xValue < -GRAVITY_THRESHOLD && timestamp - mLastTime < TIME_THRESHOLD_NS) {
                    onDetected();
                    mUp = false;
                }
            }
        }
    }

    /**
     * Called on detection of a successful up->down movement of hand.
     */
    private void onDetected() {
        mCounter++;
        setCounter(mCounter);
        renewTimer();
        syncCount();
    }

    private void syncCount() {
        if (mPaired) {
            mDataMap.putInt(COUNT_KEY, mCounter);
            PutDataRequest putDataReq = mPutDataMapReq.asPutDataRequest();
            putDataReq.setUrgent();
            PendingResult<DataApi.DataItemResult> pendingResult =
                    Wearable.DataApi.putDataItem(mGoogleApiClient, putDataReq);
        }
    }

    // Disconnect from the data layer when the Activity stops
    @Override
    protected void onStop() {
        super.onStop();
        if (null != mGoogleApiClient && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    /**
     * Updates the counter on UI, saves it to preferences and vibrates the watch when counter
     * reaches a multiple of 10.
     */
    private void setCounter(int i) {
        mCounterPage.setCounter(i);
        Utils.saveCounterToPreference(this, i);
        if (i > 0 && i % 10 == 0) {
            Utils.vibrate(this, 0);
        }
    }

    public void resetCounter() {
        setCounter(0);
        mCounter = 0;
        renewTimer();
        syncCount();
    }

    /**
     * Starts a timer to clear the flag FLAG_KEEP_SCREEN_ON.
     */
    private void renewTimer() {
        if (null != mTimer) {
            mTimer.cancel();
        }
        mTimerTask = new TimerTask() {
            @Override
            public void run() {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG,
                            "Removing the FLAG_KEEP_SCREEN_ON flag to allow going to background");
                }
                resetFlag();
            }
        };
        mTimer = new Timer();
        mTimer.schedule(mTimerTask, SCREEN_ON_TIMEOUT_MS);
    }

    /**
     * Resets the FLAG_KEEP_SCREEN_ON flag so activity can go into background.
     */
    private void resetFlag() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Resetting FLAG_KEEP_SCREEN_ON flag to allow going to background");
                }
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                finish();
            }
        });
    }

    /**
     * Sets the page indicator for the ViewPager.
     */
    private void setIndicator(int i) {
        switch (i) {
            case 0:
                mFirstIndicator.setImageResource(R.drawable.full_10);
                mSecondIndicator.setImageResource(R.drawable.empty_10);
                break;
            case 1:
                mFirstIndicator.setImageResource(R.drawable.empty_10);
                mSecondIndicator.setImageResource(R.drawable.full_10);
                break;
        }
    }


}
