/*
 * Copyright (C) 2013 David van Tonder
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use context file except in compliance with the License.
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
import android.content.Context;
import android.graphics.Color;
import android.text.format.DateFormat;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.cyanogenmod.lockclock.R;
import com.cyanogenmod.lockclock.misc.IconUtils;
import com.cyanogenmod.lockclock.misc.Preferences;
import com.cyanogenmod.lockclock.misc.WidgetUtils;

import static mokee.providers.WeatherContract.WeatherColumns.WindSpeedUnit.MPH;
import static mokee.providers.WeatherContract.WeatherColumns.WindSpeedUnit.KPH;
import static mokee.providers.WeatherContract.WeatherColumns.TempUnit.FAHRENHEIT;
import static mokee.providers.WeatherContract.WeatherColumns.TempUnit.CELSIUS;

import mokee.providers.WeatherContract;
import mokee.weather.MKWeatherManager;
import mokee.weather.WeatherInfo;
import mokee.weather.WeatherInfo.DayForecast;
import mokee.weather.util.WeatherUtils;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class ForecastBuilder {
    private static final String TAG = "ForecastBuilder";

    /**
     * This method is used to build the full current conditions and horizontal forecasts
     * panels
     *
     * @param context
     * @param w = the Weather info object that contains the forecast data
     * @return = a built view that can be displayed
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static View buildFullPanel(Context context, int resourceId, WeatherInfo w) {

        // Load some basic settings
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE); 
        int color = Preferences.weatherFontColor(context);
        boolean invertLowHigh = Preferences.invertLowHighTemperature(context);
        final boolean useMetric = Preferences.useMetricUnits(context);

        //Make any conversion needed in case the data was not provided in the desired unit
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

        double windSpeed = w.getWindSpeed();
        int windSpeedUnit = w.getWindSpeedUnit();
        if (windSpeedUnit == MPH && useMetric) {
            windSpeedUnit = KPH;
            windSpeed = Utils.milesToKilometers(windSpeed);
        } else if (windSpeedUnit == KPH && !useMetric) {
            windSpeedUnit = MPH;
            windSpeed = Utils.kilometersToMiles(windSpeed);
        }

        View view = inflater.inflate(resourceId, null);

        // Set the weather source
        TextView weatherSource = (TextView) view.findViewById(R.id.weather_source);
        final MKWeatherManager mkWeatherManager = MKWeatherManager.getInstance(context);
        String activeWeatherLabel = mkWeatherManager.getActiveWeatherServiceProviderLabel();
        weatherSource.setText(activeWeatherLabel != null ? activeWeatherLabel : "");

        // Set the current conditions
        // Weather Image
        ImageView weatherImage = (ImageView) view.findViewById(R.id.weather_image);
        String iconsSet = Preferences.getWeatherIconSet(context);
        weatherImage.setImageBitmap(IconUtils.getWeatherIconBitmap(context, iconsSet, color,
                w.getConditionCode(), IconUtils.getNextHigherDensity(context)));

        // Weather Condition
        TextView weatherCondition = (TextView) view.findViewById(R.id.weather_condition);
        weatherCondition.setText(Utils.resolveWeatherCondition(context, w.getConditionCode()));

        // Weather Aqi
        String aqiLabel = w.getAqi();
        TextView weatherAqi = (TextView) view.findViewById(R.id.weather_aqi);
        ImageView weatherDivider = (ImageView) view.findViewById(R.id.weather_divider_left);
        if (TextUtils.isEmpty(aqiLabel)) {
            weatherAqi.setVisibility(View.GONE);
            weatherDivider.setVisibility(View.GONE);
        } else {
            weatherAqi.setText(aqiLabel);
            weatherAqi.setVisibility(View.VISIBLE);
            weatherDivider.setVisibility(View.VISIBLE);
        }

        // Weather Temps
        TextView weatherTemp = (TextView) view.findViewById(R.id.weather_temp);
        weatherTemp.setText(WeatherUtils.formatTemperature(temp, tempUnit));

        // Wind
        TextView weatherWind = (TextView) view.findViewById(R.id.weather_wind);
        weatherWind.setText(Utils.formatWindSpeed(context, windSpeed, windSpeedUnit) + " "
                + Utils.resolveWindDirection(context, w.getWindDirection()));

        // City
        TextView city = (TextView) view.findViewById(R.id.weather_city);
        city.setText(w.getCity());

        String uvLabel = w.getUv();
        TextView weatherUv = (TextView) view.findViewById(R.id.weather_uv);
        weatherUv.setText(uvLabel);
        weatherUv.setVisibility(!TextUtils.isEmpty(uvLabel) ? View.VISIBLE : View.GONE);

        // Weather Update Time
        Date lastUpdate = new Date(w.getTimestamp());
        StringBuilder sb = new StringBuilder();
        sb.append(DateFormat.format("E", lastUpdate));
        sb.append(" ");
        sb.append(DateFormat.getTimeFormat(context).format(lastUpdate));
        TextView updateTime = (TextView) view.findViewById(R.id.update_time);
        updateTime.setText(sb.toString());
        updateTime.setVisibility(
                Preferences.showWeatherTimestamp(context) && TextUtils.isEmpty(uvLabel) ? View.VISIBLE : View.GONE);

        // Weather Humidity and Temps Panel additional items
        final String low = WeatherUtils.formatTemperature(todaysLow, tempUnit);

        final String high = WeatherUtils.formatTemperature(todaysHigh, tempUnit);
        TextView weatherLowHigh = (TextView) view.findViewById(R.id.weather_low_high_hum);
        weatherLowHigh.setText(invertLowHigh ? WidgetUtils.formatTemperatureUnit(high, tempUnit) + "/" + low
                : WidgetUtils.formatTemperatureUnit(low, tempUnit) + "/" + high);

        if (!Double.isNaN(w.getHumidity())) {
            weatherLowHigh.setText(weatherLowHigh.getText() + " | " + Utils.formatHumidity(w.getHumidity()));
        }

        // Get things ready
        LinearLayout forecastView = (LinearLayout) view.findViewById(R.id.forecast_view);
        final View progressIndicator = view.findViewById(R.id.progress_indicator);

        // Build the forecast panel
        if (buildSmallPanel(context, forecastView, w)) {
            // Success, hide the progress container
            progressIndicator.setVisibility(View.GONE);
        } else {
            // TODO: Display a text notifying the user that the forecast data is not available
            // rather than keeping the indicator spinning forever
        }

        return view;
    }

    /**
     * This method is used to build the small, horizontal forecasts panel
     * @param context
     * @param smallPanel = a horizontal linearlayout that will contain the forecasts
     * @param w = the Weather info object that contains the forecast data
     */
    public static boolean buildSmallPanel(Context context, LinearLayout smallPanel, WeatherInfo w) {
        if (smallPanel == null) {
          Log.d(TAG, "Invalid view passed");
          return false;
        }

        // Get things ready
        LayoutInflater inflater
              = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        int color = Preferences.weatherFontColor(context);
        boolean invertLowHigh = Preferences.invertLowHighTemperature(context);
        final boolean useMetric = Preferences.useMetricUnits(context);

        List<DayForecast> forecasts = w.getForecasts();
        if (forecasts == null || forecasts.size() <= 1) {
          smallPanel.setVisibility(View.GONE);
          return false;
        }

        TimeZone MyTimezone = TimeZone.getDefault();
        Calendar calendar = new GregorianCalendar(MyTimezone);
        if (forecasts.size() > 1) {
            forecasts.remove(0);
            calendar.add(Calendar.DAY_OF_WEEK, 1);
        }
        int weatherTempUnit = w.getTemperatureUnit();
        // Iterate through the forecasts
        for (DayForecast d : forecasts) {
            // Load the views
            View forecastItem = inflater.inflate(R.layout.forecast_item, null);

            // The day of the week
            TextView day = (TextView) forecastItem.findViewById(R.id.forecast_day);
            day.setText(calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT,
                  Locale.getDefault()));
            calendar.roll(Calendar.DAY_OF_WEEK, true);

            // Weather Image
            ImageView image = (ImageView) forecastItem.findViewById(R.id.weather_image);
            String iconsSet = Preferences.getWeatherIconSet(context);
            final int resId = IconUtils.getWeatherIconResource(context, iconsSet,
                  d.getConditionCode());
            if (resId != 0) {
              image.setImageResource(resId);
            } else {
              image.setImageBitmap(IconUtils.getWeatherIconBitmap(context, iconsSet,
                      color, d.getConditionCode()));
            }

            // Temperatures
            double lowTemp = d.getLow();
            double highTemp = d.getHigh();
            int tempUnit = weatherTempUnit;
            if (weatherTempUnit == FAHRENHEIT && useMetric) {
                lowTemp = WeatherUtils.fahrenheitToCelsius(lowTemp);
                highTemp = WeatherUtils.fahrenheitToCelsius(highTemp);
                tempUnit = CELSIUS;
            } else if (weatherTempUnit == CELSIUS && !useMetric) {
                lowTemp = WeatherUtils.celsiusToFahrenheit(lowTemp);
                highTemp = WeatherUtils.celsiusToFahrenheit(highTemp);
                tempUnit = FAHRENHEIT;
            }
            String dayLow = WeatherUtils.formatTemperature(lowTemp, tempUnit);
            String dayHigh = WeatherUtils.formatTemperature(highTemp, tempUnit);
            TextView temps = (TextView) forecastItem.findViewById(R.id.weather_temps);
            temps.setText(invertLowHigh ? WidgetUtils.formatTemperatureUnit(dayHigh, tempUnit) + "/" + dayLow : WidgetUtils.formatTemperatureUnit(dayLow, tempUnit) + "/" + dayHigh);

            // Add the view
            smallPanel.addView(forecastItem,
                  new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        return true;
    }
}
