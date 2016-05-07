/*
 * Copyright (C) 2013 David van Tonder
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

package com.cyanogenmod.lockclock.weather;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import com.cyanogenmod.lockclock.R;
import com.cyanogenmod.lockclock.misc.Preferences;
import com.cyanogenmod.lockclock.misc.WidgetUtils;
import mokee.weather.WeatherInfo;

public class ForecastActivity extends Activity {
    private static final String TAG = "ForecastActivity";

    private BroadcastReceiver mUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra(WeatherUpdateService.EXTRA_UPDATE_CANCELLED, false)) {
                updateForecastPanel();
            }
        }
    };

    @SuppressLint("InlinedApi")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Get the window ready
        Window window = getWindow();
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (WidgetUtils.isTranslucencyAvailable()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS,
                    WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        }

        registerReceiver(mUpdateReceiver, new IntentFilter(WeatherUpdateService.ACTION_UPDATE_FINISHED));
        updateForecastPanel();
        forceUpdate();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mUpdateReceiver);
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finish();
    }

    private void updateForecastPanel() {
        // Get the forecasts data
        WeatherInfo weather = Preferences.getCachedWeatherInfo(this);
        if (weather == null) {
            Log.e(TAG, "Error retrieving forecast data, exiting");
            finish();
            return;
        }

        View fullLayout = ForecastBuilder.buildFullPanel(this, R.layout.forecast_activity, weather);
        setContentView(fullLayout);
        fullLayout.requestFitSystemWindows();
    }

    private void forceUpdate() {
        Intent i = new Intent(this, WeatherUpdateService.class);
        i.setAction(WeatherUpdateService.ACTION_FORCE_UPDATE);
        startService(i);
    }

}
