/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.data;

import java.util.Arrays;
import java.util.List;

/**
 * Storage for SignageOS API related constants, property names and default values
 * @author Maksym.Rossiitsev/AVISPL Team
 * @since 1.0.0
 */
public interface Constant {
    /**
     * Storage for SignageOS API related URIs
     * @author Maksym.Rossiitsev/AVISPL Team
     * @since 1.0.0
     */
    interface URI {
        String DEVICES = "v2/device?limit=%s";
        String RESOLUTION = "v1/device/%s/resolution";
        String REMOTE_CONTROL = "v1/device/%s/remote-control";
        String SCREENSHOT = "v1/device/screenshot?deviceUids=%s&limit=%s"; //supports pagination
        String SCREENSHOT_COMMAND = "v1/device/%s/screenshot";
        String POWER_ACTION = "v1/device/%s/power-action";
        String ACTION_LOG = "v1/device/%s/action-log?limit=%s"; //supports pagination
        String STORAGE = "v1/device/%s/storage";
        String VOLUME = "v1/device/%s/volume";
        String VPN = "v1/device/%s/vpn";
        String TELEMETRY = "v1/device/%s/telemetry/%s/latest"; //2nd parameter - telemetry type
        String UPTIME = "v1/uptime?deviceUid=%s";
        String DEBUG = "v1/device/%s/debug";
        String DEVICE_APPLET = "v1/device/%s/applet";
        String APPLET = "v1/applet/%s";
    }

    /**
     * Storage for property names and labels
     * @author Maksym.Rossiitsev/AVISPL Team
     * @since 1.0.0
     */
    interface Properties {
        List<String> ORIENTATION_OPTIONS = Arrays.asList("PORTRAIT", "LANDSCAPE", "PORTRAIT_FLIPPED", "LANDSCAPE_FLIPPED", "AUTO");
        List<String> RESOLUTION_OPTIONS = Arrays.asList("1920x1080", "1536x864", "1366x768", "1280x720", "1440x900", "1280x960");
        String ADAPTER_UPTIME = "AdapterUptime";
        String ADAPTER_UPTIME_MIN = "AdapterUptime(min)";
        String ADAPTER_BUILD_DATE = "AdapterBuildDate";
        String ADAPTER_VERSION = "AdapterVersion";
        String LAST_MONITORING_CYCLE_DURATION_S = "LastMonitoringCycleDuration(s)";
        String MONITORED_DEVICES_TOTAL = "MonitoredDevicesTotal";

        String STORAGE_INTERNAL_CAPACITY = "Storage#InternalCapacity(MB)";
        String STORAGE_INTERNAL_USED = "Storage#InternalUsed(%)";
        String STORAGE_INTERNAL_AVAILABLE = "Storage#InternalAvailable(MB)";
        String STORAGE_REMOVABLE_CAPACITY = "Storage#RemovableCapacity(MB)";
        String STORAGE_REMOVABLE_USED = "Storage#RemovableUsed(%)";
        String STORAGE_REMOVABLE_AVAILABLE = "Storage#RemovableAvailable(MB)";
        String STORAGE_LAST_UPDATED = "Storage#LastUpdated";

        String VOLUME = "Configuration#Volume";
        String TEMPERATURE = "Temperature";
        String INPUT_SOURCE = "Configuration#InputSource";
        String BRIGHTNESS = "Configuration#Brightness";
        String SCREEN_RESOLUTION = "Configuration#Resolution";
        String SCREEN_FRAMERATE = "Configuration#Framerate";
        String SCREEN_ORIENTATION = "Configuration#Orientation";
        String REMOTE_CONTROL = "Configuration#RemoteControl";
        String FRONT_DISPLAY_VERSION = "System#FrontDisplayVersion";
        String FRONT_DISPLAY_VERSION_NUMBER = "System#FrontDisplayVersionNumber";
        String APPLICATION_VERSION = "System#ApplicationVersion";
        String APPLICATION_VERSION_NUMBER = "System#ApplicationVersionNumber";
        String WIFI_STRENGTH = "Network#WiFiStrength";
        String PROXY_ENABLED = "Network#ProxyEnabled";
        String PROXY_URI = "Network#ProxyURI";
        String TIMEZONE = "Time#TimeZone";
        String NTP_SERVER = "Time#NTPServer";
        String DEBUG_APPLET = "Debugging#Applet";
        String DEBUG_NATIVE = "Debugging#Native";
        String SCREENSHOT = "Configuration#Screenshot";
        String REBOOT = "Configuration#Reboot";
        String APP_RESTART = "Configuration#AppRestart";
        String APPLET_RELOAD = "Configuration#AppletReload";
        String APPLET_REFRESH = "Configuration#AppletRefresh";
        String UPTIME_S = "Uptime#Uptime(s)";
        String UPTIME = "Uptime#Uptime";
        String DOWNTIME_S = "Uptime#Downtime(s)";
        String DOWNTIME = "Uptime#Downtime";
        String UPTIME_TOTAL_S = "Uptime#Total(s)";
        String UPTIME_TOTAL = "Uptime#Total";
        String UPTIME_SINCE = "Uptime#DateFrom";
        String UPTIME_UNTIL = "Uptime#DateTo";
        String UPTIME_AVAILABILITY = "Uptime#Availability(%)";
        String SCREENSHOT_TAKEN_AT = "Screenshot[%s]#TakenAt";
        String SCREENSHOT_URI = "Screenshot[%s]#URI";
        String ACTION_LOG_CREATED_AT = "ActionLog[%s]#CreatedAt";
        String ACTION_LOG_FAILED_AT = "ActionLog[%s]#FailedAt";
        String ACTION_LOG_ORIGINATOR_ACCOUNT_ID = "ActionLog[%s]#OriginatorAccountId";
        String ACTION_LOG_SUCCEEDED_AT = "ActionLog[%s]#SucceededAt";
        String ACTION_LOG_TYPE = "ActionLog[%s]#Type";
        String VPN_ENABLED = "VPN#Enabled";
    }

    /**
     * Storage for property group names
     * @author Maksym.Rossiitsev/AVISPL Team
     * @since 1.0.0
     */
    interface PropertyGroups {
        String ASSIGNED_APPLETS = "AssignedApplets";
        String STORAGE = "Storage";
        String SCREENSHOTS = "Screenshots";
        String ACTION_LOGS = "ActionLogs";
        String VPN = "VPN";
        String UPTIME = "Uptime";
        String METADATA = "Metadata";
        String TELEMETRY = "Telemetry";
        String TELEMETRY_VOLUME = "TelemetryVolume";
        String TELEMETRY_BRIGHTNESS = "TelemetryBrightness";
        String TELEMETRY_RESOLUTION = "TelemetryResolution"; //framerate, orientation and resolution
        String TELEMETRY_REMOTE_CONTROL = "TelemetryRemoteControl";
        String TELEMETRY_APPLICATION_VERSION = "TelemetryApplicationVersion";
        String TELEMETRY_FRONT_DISPLAY_VERSION = "TelemetryFrontDisplayVersion";
        String TELEMETRY_FIRMWARE_VERSION = "TelemetryFirmwareVersion";
        String TELEMETRY_DEBUG = "TelemetryDebug";
        String TELEMETRY_DATE_TIME = "TelemetryDateTime";
        String TELEMETRY_POWER_ACTIONS_SCHEDULE = "TelemetryPowerActionsSchedule";
        String TELEMETRY_TEMPERATURE = "TelemetryTemperature";
        String TELEMETRY_ONLINE_STATUS = "TelemetryOnlineStatus";
        String TELEMETRY_BUNDLED_APPLET = "TelemetryBundledApplet";
        String TELEMETRY_PROXY = "TelemetryProxy";
        String TELEMETRY_WIFI_STRENGTH = "TelemetryWiFiStrength";
        String TELEMETRY_STORAGE = "TelemetryStorage";
        String ALL = "All";
    }
}