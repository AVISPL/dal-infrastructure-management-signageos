/*
 * Copyright (c) 2025 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.data;
/**
 * Telemetry settings to grant telemetry retrieval based on a given source
 * @author Maksym.Rossiitsev/AVISPL Team
 * @since 1.0.0
 */
public enum TelemetrySetting {
    ONLINE_STATUS("TelemetryOnlineStatus"),
    /**Tizen with enabled extended control*/
    DISPLAY_SETTING("TelemetryDisplaySetting"), //backlight, contrast, sharpness
    INPUT_SOURCE("TelemetryInputSource"), //inputSource: urlLauncher, hdmi1, hdmi2, hdmi3, hdmi4
    /**Tizen with enabled extended control end*/

    /**Tizen and WebOS*/
    AUTO_RECOVERY("TelemetryAutoRecovery"), //enabled: true/false, healthcheckIntervalMs
    /**Tizen and WebOS end*/

    /**Tizen*/
    PEER_RECOVERY("TelemetryPeerRecovery"), //enabled: true/false
    /**Tizen end*/
    VOLUME("TelemetryVolume"),
    BRIGHTNESS("TelemetryBrightness"),
    RESOLUTION("TelemetryResolution"),
    ORIENTATION("TelemetryOrientation"),
    REMOTE_CONTROL("TelemetryRemoteControl"),
    APPLICATION_VERSION("TelemetryApplicationVersion"),
    FRONT_DISPLAY_VERSION("TelemetryFrontDisplayVersion"),
    FIRMWARE_VERSION("TelemetryFirmwareVersion"),
    DEBUG("TelemetryDebug"),
    DATETIME("TelemetryDateTime"),
    TEMPERATURE("TelemetryTemperature"),
    BUNDLED_APPLET("TelemetryBundledApplet"),
    PROXY("TelemetryProxy"),
    WIFI_STRENGTH("TelemetryWifiStrength"),
    STORAGE("TelemetryStorage");

    private String name;
    TelemetrySetting(String name) {
        this.name = name;
    }

    /**
     * Retrieves {@link #name}
     *
     * @return value of {@link #name}
     */
    public String getName() {
        return name;
    }
}
