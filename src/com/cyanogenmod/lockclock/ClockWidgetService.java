/*
 * Copyright (C) 2012 The CyanogenMod Project (DvTonder)
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

package com.cyanogenmod.lockclock;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.mokee.utils.MoKeeUtils;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import com.cyanogenmod.lockclock.calendar.CalendarViewsService;
import com.cyanogenmod.lockclock.misc.Constants;
import com.cyanogenmod.lockclock.misc.IconUtils;
import com.cyanogenmod.lockclock.misc.Preferences;
import com.cyanogenmod.lockclock.misc.WidgetUtils;
import com.cyanogenmod.lockclock.weather.Utils;
import com.cyanogenmod.lockclock.weather.WeatherUpdateService;
import com.mokee.cloud.calendar.Lunar;

import static mokee.providers.WeatherContract.WeatherColumns.TempUnit.FAHRENHEIT;
import static mokee.providers.WeatherContract.WeatherColumns.TempUnit.CELSIUS;
import mokee.weather.MKWeatherManager;
import mokee.weather.WeatherInfo;
import mokee.weather.util.WeatherUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class ClockWidgetService extends IntentService {
    private static final String TAG = "ClockWidgetService";
    private static final boolean D = Constants.DEBUG;

    public static final String ACTION_REFRESH = "com.cyanogenmod.lockclock.action.REFRESH_WIDGET";
    public static final String ACTION_REFRESH_CALENDAR = "com.cyanogenmod.lockclock.action.REFRESH_CALENDAR";
    public static final String ACTION_HIDE_CALENDAR = "com.cyanogenmod.lockclock.action.HIDE_CALENDAR";

    // This needs to be static to persist between refreshes until explicitly changed by an intent
    private static boolean mHideCalendar = false;

    private int[] mWidgetIds;
    private AppWidgetManager mAppWidgetManager;
    private Context mContext;

    public ClockWidgetService() {
        super("ClockWidgetService");
    }

    @Override
    public void onCreate() {
        super.onCreate();

        ComponentName thisWidget = new ComponentName(this, ClockWidgetProvider.class);
        mAppWidgetManager = AppWidgetManager.getInstance(this);
        mWidgetIds = mAppWidgetManager.getAppWidgetIds(thisWidget);
        mContext = getApplicationContext();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (D) Log.d(TAG, "Got intent " + intent);

        if (mWidgetIds != null && mWidgetIds.length != 0) {
            // Check passed in intents
            if (intent != null) {
                if (ACTION_HIDE_CALENDAR.equals(intent.getAction())) {
                    if (D) Log.v(TAG, "Force hiding the calendar panel");
                    // Explicitly hide the panel since we received a broadcast indicating no events
                    mHideCalendar = true;
                } else if (ACTION_REFRESH_CALENDAR.equals(intent.getAction())) {
                    if (D) Log.v(TAG, "Forcing a calendar refresh");
                    // Start with the panel not explicitly hidden
                    // If there are no events, a broadcast to the service will hide the panel
                    mHideCalendar = false;
                    mAppWidgetManager.notifyAppWidgetViewDataChanged(mWidgetIds, R.id.calendar_list);
                }
            }
            refreshWidget();
        }
    }

    /**
     * Reload the widget including the Weather forecast, Alarm, Clock font and Calendar
     */
    private void refreshWidget() {
        // Get things ready
        RemoteViews remoteViews;
        boolean showWeather = Preferences.showWeather(this);
        boolean showWeatherWhenMinimized = Preferences.showWeatherWhenMinimized(this);

        // Update the widgets
        for (int id : mWidgetIds) {
            boolean showCalendar = false;

            // Determine if its a home or a lock screen widget
            Bundle myOptions = mAppWidgetManager.getAppWidgetOptions (id);
            boolean isKeyguard = false;
            if (WidgetUtils.isTextClockAvailable()) {
                // This is only available on API 17+, make sure we are not calling it on API16
                // This generates an API level Lint warning, ignore it
                int category = myOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, -1);
                isKeyguard = category == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD;
            }
            if (D) Log.d(TAG, "For Widget id " + id + " isKeyguard is set to " + isKeyguard);

            // Determine which layout to use
            boolean smallWidget = showWeather && showWeatherWhenMinimized
                    && WidgetUtils.showSmallWidget(this, id, isKeyguard);
            if (smallWidget) {
                // The small widget is only shown if weather needs to be shown
                // and there is not enough space for the full weather widget and
                // the user had selected to show the weather when minimized (default ON)
                remoteViews = new RemoteViews(getPackageName(), R.layout.appwidget_small);
                showCalendar = false;
            } else {
                remoteViews = new RemoteViews(getPackageName(), R.layout.appwidget);
                // show calendar if enabled and events available and enough space available
                showCalendar = Preferences.showCalendar(this) && !mHideCalendar
                        && WidgetUtils.canFitCalendar(this, id);
            }

            // Hide the Loading indicator
            remoteViews.setViewVisibility(R.id.loading_indicator, View.GONE);

            // Always Refresh the Clock widget
            refreshClock(remoteViews, smallWidget);
            refreshAlarmStatus(remoteViews, smallWidget, id);

            // Refresh the time if using TextView Clock (API 16)
            if(!WidgetUtils.isTextClockAvailable()) {
                refreshTime(remoteViews, smallWidget);
            }

            // Don't bother with Calendar if its not visible
            if (showCalendar) {
                refreshCalendar(remoteViews, id);
            }
            // Hide the calendar panel if not visible
            remoteViews.setViewVisibility(R.id.calendar_panel,
                    showCalendar ? View.VISIBLE : View.GONE);

            boolean canFitWeather = smallWidget
                    || WidgetUtils.canFitWeather(this, id, isKeyguard);
            // Now, if we need to show the actual weather, do so
            if (showWeather && canFitWeather) {
                WeatherInfo weatherInfo = Preferences.getCachedWeatherInfo(this);

                if (weatherInfo != null) {
                    setWeatherData(remoteViews, smallWidget, weatherInfo);
                } else {
                    setNoWeatherData(remoteViews, smallWidget);
                }
            }
            remoteViews.setViewVisibility(R.id.weather_panel,
                    (showWeather && canFitWeather) ? View.VISIBLE : View.GONE);

            // Resize the clock font
            float ratio = WidgetUtils.getScaleRatio(this, id);
            setClockSize(remoteViews, (showWeather && canFitWeather) ? ratio / 1.75f : ratio);

            // Do the update
            mAppWidgetManager.updateAppWidget(id, remoteViews);
        }
    }

    //===============================================================================================
    // Clock related functionality
    //===============================================================================================
    private void refreshClock(RemoteViews clockViews, boolean smallWidget) {
        // Hours/Minutes is specific to Digital, set it's size
        refreshClockFont(clockViews, smallWidget);
        clockViews.setViewVisibility(R.id.digital_clock, View.VISIBLE);

        // Date/Alarm is common to both clocks, set it's size
        refreshDateAlarmFont(clockViews, smallWidget);

        // Register an onClickListener on Clock, starting DeskClock
        Intent i = WidgetUtils.getDefaultClockIntent(this);
        if (i != null) {
            PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            clockViews.setOnClickPendingIntent(R.id.clock_panel, pi);
        }
    }

    // API 16 TextView Clock support
    private void refreshTime(RemoteViews clockViews, boolean smallWidget) {
        Locale locale = Locale.getDefault();
        Date now = new Date();
        String dateFormat = getString(R.string.abbrev_wday_month_day_no_year);
        CharSequence date = DateFormat.format(dateFormat, now);
        String hours = new SimpleDateFormat(getHourFormat(), locale).format(now);
        String minutes = new SimpleDateFormat(getString(R.string.widget_12_hours_format_no_ampm_m),
                locale).format(now);

        // Hours
        if (Preferences.useBoldFontForHours(this)) {
            clockViews.setTextViewText(R.id.clock1_bold, hours);
        } else {
            clockViews.setTextViewText(R.id.clock1_regular, hours);
        }

        // Minutes
        if (Preferences.useBoldFontForMinutes(this)) {
            clockViews.setTextViewText(R.id.clock2_bold, minutes);
        } else {
            clockViews.setTextViewText(R.id.clock2_regular, minutes);
        }

        // Date and Alarm font
        if (!smallWidget) {
            if (Preferences.useBoldFontForDateAndAlarms(this)) {
                clockViews.setTextViewText(R.id.date_bold, date);
            } else {
                clockViews.setTextViewText(R.id.date_regular, date);
            }
        } else {
            clockViews.setTextViewText(R.id.date, date);
        }
    }

    private void refreshClockFont(RemoteViews clockViews, boolean smallWidget) {
        int color = Preferences.clockFontColor(this);
        String amPM = new SimpleDateFormat("a", Locale.getDefault()).format(new Date());

        // Hours
        if (Preferences.useBoldFontForHours(this)) {
            clockViews.setViewVisibility(R.id.clock1_bold, View.VISIBLE);
            clockViews.setViewVisibility(R.id.clock1_regular, View.GONE);
            clockViews.setTextColor(R.id.clock1_bold, color);
        } else {
            clockViews.setViewVisibility(R.id.clock1_regular, View.VISIBLE);
            clockViews.setViewVisibility(R.id.clock1_bold, View.GONE);
            clockViews.setTextColor(R.id.clock1_regular, color);
        }

        // Minutes
        if (Preferences.useBoldFontForMinutes(this)) {
            clockViews.setViewVisibility(R.id.clock2_bold, View.VISIBLE);
            clockViews.setViewVisibility(R.id.clock2_regular, View.GONE);
            clockViews.setTextColor(R.id.clock2_bold, color);
        } else {
            clockViews.setViewVisibility(R.id.clock2_regular, View.VISIBLE);
            clockViews.setViewVisibility(R.id.clock2_bold, View.GONE);
            clockViews.setTextColor(R.id.clock2_regular, color);
        }

        // Show the AM/PM indicator
        if (!DateFormat.is24HourFormat(this) && Preferences.showAmPmIndicator(this)) {
            clockViews.setViewVisibility(R.id.clock_ampm, View.VISIBLE);
            clockViews.setTextViewText(R.id.clock_ampm, amPM);
            clockViews.setTextColor(R.id.clock_ampm, color);
        } else {
            clockViews.setViewVisibility(R.id.clock_ampm, View.GONE);
        }
    }

    private void refreshDateAlarmFont(RemoteViews clockViews, boolean smallWidget) {
        int color = Preferences.clockFontColor(this);

        // Date and Alarm font
        if (!smallWidget) {
            if (Preferences.useBoldFontForDateAndAlarms(this)) {
                clockViews.setViewVisibility(R.id.date_bold, View.VISIBLE);
                clockViews.setViewVisibility(R.id.date_regular, View.GONE);
                clockViews.setTextColor(R.id.date_bold, color);
            } else {
                clockViews.setViewVisibility(R.id.date_regular, View.VISIBLE);
                clockViews.setViewVisibility(R.id.date_bold, View.GONE);
                clockViews.setTextColor(R.id.date_regular, color);
            }

            if (MoKeeUtils.isSupportLanguage(false)) {
                Calendar calendar = Calendar.getInstance();
                Lunar lunar = new Lunar(calendar);
                String lunarDate = lunar.getLunarMonthString() + lunar.getLunarDayString();
                if (Preferences.useBoldFontForDateAndAlarms(this)) {
                    clockViews.setTextViewText(R.id.date_lunar_bold, " | " + lunarDate);
                    clockViews.setTextColor(R.id.date_lunar_bold, color);
                    clockViews.setViewVisibility(R.id.date_lunar_bold, View.VISIBLE);
                    clockViews.setViewVisibility(R.id.date_lunar_thin, View.GONE);
                } else {
                    clockViews.setTextViewText(R.id.date_lunar_thin, " | " + lunarDate);
                    clockViews.setTextColor(R.id.date_lunar_thin, color);
                    clockViews.setViewVisibility(R.id.date_lunar_thin, View.VISIBLE);
                    clockViews.setViewVisibility(R.id.date_lunar_bold, View.GONE);
                }
            } else {
                clockViews.setViewVisibility(R.id.date_lunar_thin, View.GONE);
                clockViews.setViewVisibility(R.id.date_lunar_bold, View.GONE);
            }

        } else {
            clockViews.setViewVisibility(R.id.date, View.VISIBLE);
            clockViews.setTextColor(R.id.date, color);
        }

        // Show the panel
        clockViews.setViewVisibility(R.id.date_alarm, View.VISIBLE);
    }

    private void setClockSize(RemoteViews clockViews, float scale) {
        float fontSize = getResources().getDimension(R.dimen.widget_big_font_size);
        clockViews.setTextViewTextSize(R.id.clock1_bold, TypedValue.COMPLEX_UNIT_PX, fontSize * scale);
        clockViews.setTextViewTextSize(R.id.clock1_regular, TypedValue.COMPLEX_UNIT_PX, fontSize * scale);
        clockViews.setTextViewTextSize(R.id.clock2_bold, TypedValue.COMPLEX_UNIT_PX, fontSize * scale);
        clockViews.setTextViewTextSize(R.id.clock2_regular, TypedValue.COMPLEX_UNIT_PX, fontSize * scale);
    }

    private String getHourFormat() {
        String format;
        if (DateFormat.is24HourFormat(this)) {
            format = getString(R.string.widget_24_hours_format_h_api_16);
        } else {
            format = getString(R.string.widget_12_hours_format_h);
        }
        return format;
    }

    //===============================================================================================
    // Alarm related functionality
    //===============================================================================================
    private void refreshAlarmStatus(RemoteViews alarmViews, boolean smallWidget, int id) {
        if (Preferences.showAlarm(this) && WidgetUtils.canFitCalendar(this, id)) {
            String nextAlarm = getNextAlarm();
            if (!TextUtils.isEmpty(nextAlarm)) {
                // An alarm is set, deal with displaying it
                int color = Preferences.clockAlarmFontColor(this);
                final Resources res = getResources();

                // Overlay the selected color on the alarm icon and set the imageview
                alarmViews.setImageViewBitmap(R.id.alarm_icon,
                        IconUtils.getOverlaidBitmap(res, R.drawable.ic_alarm_small, color));
                alarmViews.setViewVisibility(R.id.alarm_icon, View.VISIBLE);

                if (!smallWidget) {
                    if (Preferences.useBoldFontForDateAndAlarms(this)) {
                        alarmViews.setTextViewText(R.id.nextAlarm_bold, nextAlarm);
                        alarmViews.setViewVisibility(R.id.nextAlarm_bold, View.VISIBLE);
                        alarmViews.setViewVisibility(R.id.nextAlarm_regular, View.GONE);
                        alarmViews.setTextColor(R.id.nextAlarm_bold, color);
                    } else {
                        alarmViews.setTextViewText(R.id.nextAlarm_regular, nextAlarm);
                        alarmViews.setViewVisibility(R.id.nextAlarm_regular, View.VISIBLE);
                        alarmViews.setViewVisibility(R.id.nextAlarm_bold, View.GONE);
                        alarmViews.setTextColor(R.id.nextAlarm_regular, color);
                    }
                } else {
                    alarmViews.setTextViewText(R.id.nextAlarm, nextAlarm);
                    alarmViews.setViewVisibility(R.id.nextAlarm, View.VISIBLE);
                    alarmViews.setTextColor(R.id.nextAlarm, color);
                }
                return;
            }
        }

        // No alarm set or Alarm display is hidden, hide the views
        alarmViews.setViewVisibility(R.id.alarm_icon, View.GONE);
        if (!smallWidget) {
            alarmViews.setViewVisibility(R.id.nextAlarm_bold, View.GONE);
            alarmViews.setViewVisibility(R.id.nextAlarm_regular, View.GONE);
        } else {
            alarmViews.setViewVisibility(R.id.nextAlarm, View.GONE);
        }
    }

    /**
     * @return A formatted string of the next alarm or null if there is no next alarm.
     */
    private String getNextAlarm() {
        String nextAlarm = null;

        AlarmManager am =(AlarmManager) getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo alarmClock = am.getNextAlarmClock();
        if (alarmClock != null) {
            nextAlarm = getNextAlarmFormattedTime(this, alarmClock.getTriggerTime());
        }

        return nextAlarm;
    }

    private static String getNextAlarmFormattedTime(Context context, long time) {
        String skeleton = DateFormat.is24HourFormat(context) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return (String) DateFormat.format(pattern, time);
    }

    //===============================================================================================
    // Weather related functionality
    //===============================================================================================
    /**
     * Display the weather information
     */
    private void setWeatherData(RemoteViews weatherViews, boolean smallWidget, WeatherInfo w) {
        int color = Preferences.weatherFontColor(this);
        String iconsSet = Preferences.getWeatherIconSet(this);
        final boolean useMetric = Preferences.useMetricUnits(mContext);

        // Reset no weather visibility
        weatherViews.setViewVisibility(R.id.weather_no_data, View.GONE);
        weatherViews.setViewVisibility(R.id.weather_refresh, View.GONE);

        // Weather Image
        int resId = IconUtils.getWeatherIconResource(mContext, iconsSet, w.getConditionCode());
        weatherViews.setViewVisibility(R.id.weather_image, View.VISIBLE);
        if (resId != 0) {
            weatherViews.setImageViewResource(R.id.weather_image,
                    IconUtils.getWeatherIconResource(mContext, iconsSet, w.getConditionCode()));
        } else {
            weatherViews.setImageViewBitmap(R.id.weather_image,
                    IconUtils.getWeatherIconBitmap(mContext, iconsSet, color, w.getConditionCode()));
        }

        // Weather Condition
        weatherViews.setTextViewText(R.id.weather_condition,
                Utils.resolveWeatherCondition(mContext, w.getConditionCode()));
        weatherViews.setViewVisibility(R.id.weather_condition, View.VISIBLE);
        weatherViews.setTextColor(R.id.weather_condition, color);

        // Weather Temps Panel
        double temp = w.getTemperature();
        double todaysLow = w.getTodaysLow();
        double todaysHigh = w.getTodaysHigh();
        int tempUnit = w.getTemperatureUnit();
        if (tempUnit == FAHRENHEIT && useMetric) {
            temp = WeatherUtils.fahrenheitToCelsius(temp);
            todaysLow = WeatherUtils.fahrenheitToCelsius(todaysLow);
            todaysHigh = WeatherUtils.fahrenheitToCelsius(todaysHigh);
            tempUnit = CELSIUS;
        } else if (tempUnit == CELSIUS && !useMetric) {
            temp = WeatherUtils.celsiusToFahrenheit(temp);
            todaysLow = WeatherUtils.celsiusToFahrenheit(todaysLow);
            todaysHigh = WeatherUtils.celsiusToFahrenheit(todaysHigh);
            tempUnit = FAHRENHEIT;
        }

        weatherViews.setTextViewText(R.id.weather_temp, WeatherUtils.formatTemperature(temp, tempUnit));
        weatherViews.setTextColor(R.id.weather_temp, color);

        String uv = w.getUv();
        weatherViews.setTextViewText(R.id.weather_uv, uv);
        weatherViews.setTextColor(R.id.weather_uv, color);

        String aqi = w.getAqi();
        weatherViews.setTextViewText(R.id.weather_aqi, aqi);
        weatherViews.setTextColor(R.id.weather_aqi, color);

        if (!TextUtils.isEmpty(uv) && !TextUtils.isEmpty(aqi) || !TextUtils.isEmpty(aqi)) {
            weatherViews.setViewVisibility(R.id.weather_uv, View.GONE);
            weatherViews.setViewVisibility(R.id.weather_aqi, View.VISIBLE);
        } else if (!TextUtils.isEmpty(uv)) {
            weatherViews.setViewVisibility(R.id.weather_uv, View.VISIBLE);
            weatherViews.setViewVisibility(R.id.weather_aqi, View.GONE);
        } else {
            weatherViews.setViewVisibility(R.id.weather_uv, View.GONE);
            weatherViews.setViewVisibility(R.id.weather_aqi, View.GONE);
        }

        if (!smallWidget) {
            // Display the full weather information panel items
            // Load the preferences
            boolean showLocation = Preferences.showWeatherLocation(this);

            // City
            weatherViews.setTextViewText(R.id.weather_city, w.getCity());
            weatherViews.setViewVisibility(R.id.weather_city, showLocation ? View.VISIBLE : View.GONE);
            weatherViews.setTextColor(R.id.weather_city, color);

        }

        // Register an onClickListener on Weather
        setWeatherClickListener(weatherViews, false);
    }

    /**
     * There is no data to display, display 'empty' fields and the 'Tap to reload' message
     */
    private void setNoWeatherData(RemoteViews weatherViews, boolean smallWidget) {
        int color = Preferences.weatherFontColor(this);
        boolean firstRun = Preferences.isFirstWeatherUpdate(this);

        // Hide the normal weather stuff
        final MKWeatherManager weatherManager = MKWeatherManager.getInstance(mContext);
        final String activeProviderLabel = weatherManager.getActiveWeatherServiceProviderLabel();
        String noData;
        if (activeProviderLabel != null) {
            noData = getString(R.string.weather_cannot_reach_provider, activeProviderLabel);
        } else {
            noData = getString(R.string.weather_source_not_selected);
        }
        weatherViews.setViewVisibility(R.id.weather_image, View.GONE);
        if (!smallWidget) {
            weatherViews.setViewVisibility(R.id.weather_city, View.GONE);
            weatherViews.setViewVisibility(R.id.weather_condition, View.GONE);

            // Set up the no data and refresh indicators
            weatherViews.setTextViewText(R.id.weather_no_data, noData);
            if (activeProviderLabel != null) {
                weatherViews.setTextViewText(R.id.weather_refresh,
                        getString(R.string.weather_tap_to_refresh));
            } else {
                weatherViews.setTextViewText(R.id.weather_refresh,
                        getString(R.string.weather_tap_to_select_source));
            }
            weatherViews.setTextColor(R.id.weather_no_data, color);
            weatherViews.setTextColor(R.id.weather_refresh, color);

            // For a better OOBE, dont show the no_data message if this is the first run
            weatherViews.setViewVisibility(R.id.weather_no_data, firstRun ? View.GONE : View.VISIBLE);
            weatherViews.setViewVisibility(R.id.weather_refresh,  firstRun ? View.GONE : View.VISIBLE);
        } else {
            weatherViews.setTextViewText(R.id.weather_temp, firstRun ? null : noData);
            weatherViews.setTextViewText(R.id.weather_condition, firstRun ? null : getString(R.string.weather_tap_to_refresh));
            weatherViews.setTextColor(R.id.weather_temp, color);
            weatherViews.setTextColor(R.id.weather_condition, color);
        }

        // Register an onClickListener on Weather with the default (Refresh) action
        if (!firstRun) {
            if (activeProviderLabel != null) {
                setWeatherClickListener(weatherViews, true);
            } else {
                setWeatherClickListener(weatherViews);
            }
        }
    }

    private void setWeatherClickListener(RemoteViews weatherViews, boolean forceRefresh) {
        // Register an onClickListener on the Weather panel, default action is show forecast
        PendingIntent pi = null;
        if (forceRefresh) {
            pi = WeatherUpdateService.getUpdateIntent(this, true);
        }

        if (pi == null) {
            Intent i = new Intent(this, ClockWidgetProvider.class);
            i.setAction(Constants.ACTION_SHOW_FORECAST);
            pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        weatherViews.setOnClickPendingIntent(R.id.weather_panel, pi);
    }

    private void setWeatherClickListener(RemoteViews weatherViews) {
        PendingIntent pi = PendingIntent.getActivity(mContext, 0,
                new Intent("mokee.intent.action.MANAGE_WEATHER_PROVIDER_SERVICES"),
                        PendingIntent.FLAG_UPDATE_CURRENT);
        weatherViews.setOnClickPendingIntent(R.id.weather_panel, pi);
    }

    //===============================================================================================
    // Calendar related functionality
    //===============================================================================================
    private void refreshCalendar(RemoteViews calendarViews, int widgetId) {
        final Resources res = getResources();
        // Calendar icon: Overlay the selected color and set the imageview
        int color = Preferences.calendarFontColor(this);

        // Set up and start the Calendar RemoteViews service
        final Intent remoteAdapterIntent = new Intent(this, CalendarViewsService.class);
        remoteAdapterIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        remoteAdapterIntent.setData(Uri.parse(remoteAdapterIntent.toUri(Intent.URI_INTENT_SCHEME)));
        calendarViews.setRemoteAdapter(R.id.calendar_list, remoteAdapterIntent);
        calendarViews.setEmptyView(R.id.calendar_list, R.id.calendar_empty_view);

        final Intent eventClickIntent = new Intent(Intent.ACTION_VIEW);
        final PendingIntent eventClickPendingIntent = PendingIntent.getActivity(this, 0, eventClickIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        calendarViews.setPendingIntentTemplate(R.id.calendar_list, eventClickPendingIntent);
    }

    public static PendingIntent getRefreshIntent(Context context) {
        Intent i = new Intent(context, ClockWidgetService.class);
        i.setAction(ClockWidgetService.ACTION_REFRESH_CALENDAR);
        return PendingIntent.getService(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void cancelUpdates(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(getRefreshIntent(context));
    }
}
