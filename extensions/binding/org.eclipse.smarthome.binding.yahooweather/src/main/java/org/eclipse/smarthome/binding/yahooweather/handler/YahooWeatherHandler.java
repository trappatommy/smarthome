/**
 * Copyright (c) 2014,2018 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.smarthome.binding.yahooweather.handler;

import static org.eclipse.smarthome.binding.yahooweather.YahooWeatherBindingConstants.*;
import static org.eclipse.smarthome.core.library.unit.MetricPrefix.HECTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.measure.quantity.Dimensionless;
import javax.measure.quantity.Pressure;
import javax.measure.quantity.Temperature;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.binding.yahooweather.internal.connection.YahooWeatherConnection;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.status.ConfigStatusMessage;
import org.eclipse.smarthome.core.cache.ExpiringCacheMap;
import org.eclipse.smarthome.core.library.types.QuantityType;
import org.eclipse.smarthome.core.library.unit.SIUnits;
import org.eclipse.smarthome.core.library.unit.SmartHomeUnits;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.ConfigStatusThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.eclipse.smarthome.core.types.State;
import org.eclipse.smarthome.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link YahooWeatherHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Kai Kreuzer - Initial contribution
 * @author Stefan Bußweiler - Integrate new thing status handling
 * @author Thomas Höfer - Added config status provider
 * @author Christoph Weitkamp - Changed use of caching utils to ESH ExpiringCacheMap
 * @author Gaël L'hopital - Added usage of QuantityType
 * @author Henning Treu - Added usage of QuantityType
 *
 */
public class YahooWeatherHandler extends ConfigStatusThingHandler {

    private static final String LOCATION_PARAM = "location";

    private final Logger logger = LoggerFactory.getLogger(YahooWeatherHandler.class);

    private static final int MAX_DATA_AGE = 3 * 60 * 60 * 1000; // 3h
    private static final int CACHE_EXPIRY = 10 * 1000; // 10s
    private static final String CACHE_KEY_CONFIG = "CONFIG_STATUS";
    private static final String CACHE_KEY_WEATHER = "WEATHER";

    private final ExpiringCacheMap<String, String> cache = new ExpiringCacheMap<>(CACHE_EXPIRY);

    private final YahooWeatherConnection connection = new YahooWeatherConnection();

    private long lastUpdateTime;

    private BigDecimal location;
    private BigDecimal refresh;

    private String weatherData = null;

    ScheduledFuture<?> refreshJob;

    public YahooWeatherHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing YahooWeather handler.");

        Configuration config = getThing().getConfiguration();

        location = (BigDecimal) config.get(LOCATION_PARAM);

        try {
            refresh = (BigDecimal) config.get("refresh");
        } catch (Exception e) {
            logger.debug("Cannot set refresh parameter.", e);
        }

        if (refresh == null) {
            // let's go for the default
            refresh = new BigDecimal(60);
        }

        cache.put(CACHE_KEY_CONFIG, () -> connection.getResponseFromQuery(
                "SELECT location FROM weather.forecast WHERE woeid = " + location.toPlainString()));
        cache.put(CACHE_KEY_WEATHER, () -> connection.getResponseFromQuery(
                "SELECT * FROM weather.forecast WHERE u = 'c' AND woeid = " + location.toPlainString()));

        startAutomaticRefresh();
    }

    @Override
    public void dispose() {
        refreshJob.cancel(true);
    }

    private void startAutomaticRefresh() {
        refreshJob = scheduler.scheduleWithFixedDelay(() -> {
            try {
                boolean success = updateWeatherData();
                if (success) {
                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_TEMPERATURE), getTemperature());
                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_HUMIDITY), getHumidity());
                    updateState(new ChannelUID(getThing().getUID(), CHANNEL_PRESSURE), getPressure());
                }
            } catch (Exception e) {
                logger.debug("Exception occurred during execution: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            }
        }, 0, refresh.intValue(), TimeUnit.SECONDS);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            boolean success = updateWeatherData();
            if (success) {
                switch (channelUID.getId()) {
                    case CHANNEL_TEMPERATURE:
                        updateState(channelUID, getTemperature());
                        break;
                    case CHANNEL_HUMIDITY:
                        updateState(channelUID, getHumidity());
                        break;
                    case CHANNEL_PRESSURE:
                        updateState(channelUID, getPressure());
                        break;
                    default:
                        logger.debug("Command received for an unknown channel: {}", channelUID.getId());
                        break;
                }
            }
        } else {
            logger.debug("Command {} is not supported for channel: {}", command, channelUID.getId());
        }
    }

    @Override
    public Collection<ConfigStatusMessage> getConfigStatus() {
        Collection<ConfigStatusMessage> configStatus = new ArrayList<>();

        final String locationData = cache.get(CACHE_KEY_CONFIG);
        if (locationData != null) {
            String city = getValue(locationData, "location", "city");
            if (city == null) {
                configStatus.add(ConfigStatusMessage.Builder.error(LOCATION_PARAM)
                        .withMessageKeySuffix("location-not-found").withArguments(location.toPlainString()).build());
            }
        }

        return configStatus;
    }

    private synchronized boolean updateWeatherData() {
        final String data = cache.get(CACHE_KEY_WEATHER);
        if (data != null) {
            if (data.contains("\"results\":null")) {
                if (isCurrentDataExpired()) {
                    logger.trace(
                            "The Yahoo Weather API did not return any data. Omiting the old result because it became too old.");
                    weatherData = null;
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                            "@text/offline.no-data");
                    return false;
                } else {
                    // simply keep the old data
                    logger.trace("The Yahoo Weather API did not return any data. Keeping the old result.");
                    return false;
                }
            } else {
                lastUpdateTime = System.currentTimeMillis();
                weatherData = data;
            }
            updateStatus(ThingStatus.ONLINE);
            return true;
        }
        weatherData = null;
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR,
                "@text/offline.location [\"" + location.toPlainString() + "\"");
        return false;
    }

    private boolean isCurrentDataExpired() {
        return lastUpdateTime + MAX_DATA_AGE < System.currentTimeMillis();
    }

    private State getHumidity() {
        if (weatherData != null) {
            String humidity = getValue(weatherData, "atmosphere", "humidity");
            if (humidity != null) {
                return new QuantityType<Dimensionless>(Double.parseDouble(humidity), SmartHomeUnits.PERCENT);
            }
        }
        return UnDefType.UNDEF;
    }

    private State getPressure() {
        State result = UnDefType.UNDEF;
        if (weatherData != null) {
            String pressure = getValue(weatherData, "atmosphere", "pressure");
            if (pressure != null) {
                double pressDouble = Double.parseDouble(pressure);
                if (pressDouble > 10000) {
                    // The Yahoo! waether API delivers wrong pressure values. Instead of the requested mbar
                    // it responds with mbar * 33.86 which is somehow inHg*1000.
                    // This is documented in several issues:
                    // https://github.com/pvizeli/yahooweather/issues/2
                    // https://github.com/monkeecreate/jquery.simpleWeather/issues/227
                    // So we provide a "special" unit here:
                    result = new QuantityType<Pressure>(pressDouble / 33.86d, HECTO(SIUnits.PASCAL));
                } else {
                    result = new QuantityType<Pressure>(pressDouble, HECTO(SIUnits.PASCAL));
                }
                return result;
            }
        }
        return UnDefType.UNDEF;
    }

    private State getTemperature() {
        if (weatherData != null) {
            String temp = getValue(weatherData, "condition", "temp");
            if (temp != null) {
                return new QuantityType<Temperature>(Double.parseDouble(temp), SIUnits.CELSIUS);
            }
        }
        return UnDefType.UNDEF;
    }

    private String getValue(String data, String element, String param) {
        String tmp = StringUtils.substringAfter(data, element);
        if (tmp != null) {
            return StringUtils.substringBetween(tmp, param + "\":\"", "\"");
        }
        return null;
    }
}