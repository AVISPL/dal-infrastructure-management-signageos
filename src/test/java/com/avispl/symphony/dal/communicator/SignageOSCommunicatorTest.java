/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.fail;

public class SignageOSCommunicatorTest {

    SignageOSCommunicator communicator;

    @BeforeEach
    public void setUp() throws Exception {
        communicator = new SignageOSCommunicator();
        communicator.setHost("api.signageos.io");
        communicator.setPort(443);
        communicator.setLogin("");
        communicator.setPassword("");
        communicator.init();
    }

    @Test
    public void testGetMultupleStatistics() throws Exception {
        List<Statistics> statistics = communicator.getMultipleStatistics();
        Assertions.assertEquals(1, statistics.size());
    }

    @Test
    public void testRetrieveMultupleStatistics() throws Exception {
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(statistics);
        for (int i = 0; i < 5; i++) {
            Thread.sleep(30000);
            statistics = communicator.retrieveMultipleStatistics();
        }
        Assertions.assertEquals(2, statistics.size());
    }

    @Test
    public void testDebugModeUpdateNative() throws Exception {
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        String controlValue = "1";
        Assertions.assertNotNull(statistics);
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Debugging#Native");
        controllableProperty.setValue(controlValue);
        controllableProperty.setDeviceId("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24");
        communicator.controlProperty(controllableProperty);
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        Optional<AggregatedDevice> aggregatedDevice = statistics.stream().filter(device -> device.getDeviceId().equals("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24")).findAny();
        if (!aggregatedDevice.isPresent()) {
            Assertions.fail("Unable to find device");
        }
        aggregatedDevice.flatMap(device -> device.getControllableProperties().stream().filter(control -> control.getName().equals("Debugging#Native")).findAny()).ifPresent(control -> {
            Assertions.assertEquals(controlValue, control.getValue());
        });
    }

    @Test
    public void testDebugModeUpdateApplet() throws Exception {
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        String controlValue = "1";
        Assertions.assertNotNull(statistics);
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Debugging#Applet");
        controllableProperty.setValue(controlValue);
        controllableProperty.setDeviceId("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24");
        communicator.controlProperty(controllableProperty);
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        Optional<AggregatedDevice> aggregatedDevice = statistics.stream().filter(device -> device.getDeviceId().equals("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24")).findAny();
        if (!aggregatedDevice.isPresent()) {
            Assertions.fail("Unable to find device");
        }
        aggregatedDevice.flatMap(device -> device.getControllableProperties().stream().filter(control -> control.getName().equals("Debugging#Applet")).findAny()).ifPresent(control -> {
            Assertions.assertEquals(controlValue, control.getValue());
        });
    }

    @Test
    public void testRemoteControlSwitch() throws Exception {
        List<AggregatedDevice> statistics = communicator.retrieveMultipleStatistics();
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        Integer controlValue = 1;
        Assertions.assertNotNull(statistics);
        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty("Configuration#RemoteControl");
        controllableProperty.setValue(controlValue);
        controllableProperty.setDeviceId("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24");
        communicator.controlProperty(controllableProperty);
        for (int i = 0; i < 2; i++) {
            statistics = communicator.retrieveMultipleStatistics();
            Thread.sleep(30000);
        }
        Optional<AggregatedDevice> aggregatedDevice = statistics.stream().filter(device -> device.getDeviceId().equals("c18809e0eb09bc8141d6123cef06255067bf5adf78b16d29b1d24")).findAny();
        if (!aggregatedDevice.isPresent()) {
            Assertions.fail("Unable to find device");
        }
        aggregatedDevice.flatMap(device -> device.getControllableProperties().stream().filter(control -> control.getName().equals("Configuration#RemoteControl")).findAny()).ifPresent(control -> {
            Assertions.assertEquals(controlValue, control.getValue());
        });
    }
}
