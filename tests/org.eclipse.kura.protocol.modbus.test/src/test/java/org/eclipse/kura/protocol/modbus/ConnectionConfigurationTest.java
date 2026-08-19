/*******************************************************************************
 * Copyright (c) 2026 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 ******************************************************************************/

package org.eclipse.kura.protocol.modbus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.kura.KuraConnectionStatus;
import org.junit.Before;
import org.junit.Test;

/**
 * Feature: validating the connection configuration of the Modbus driver, and
 * refusing the requests that arrive before a connection has been configured.
 */
public class ConnectionConfigurationTest {

    @Test
    public void shouldBeNamedModbus() {
        whenADeviceIsCreated();

        thenTheProtocolNameIs("modbus");
    }

    @Test
    public void shouldStartOutNeverConnected() {
        whenADeviceIsCreated();

        thenTheConnectionStatusIs(KuraConnectionStatus.NEVERCONNECTED);
    }

    @Test
    public void shouldRefuseToConnectWithoutAConfiguration() {
        givenADevice();

        whenTheDeviceIsConnected();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldIgnoreADisconnectWithoutAConfiguration() {
        givenADevice();

        whenTheDeviceIsDisconnected();

        thenNoFailureIsReported();
    }

    @Test
    public void shouldRequireAConnectionType() {
        givenADevice();
        givenTheConfigurationWithout("connectionType");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRequireATransmissionMode() {
        givenADevice();
        givenTheConfigurationWithout("transmissionMode");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRequireAResponseTimeout() {
        givenADevice();
        givenTheConfigurationWithout("respTimeout");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRejectAnUnknownTransmissionMode() {
        givenADevice();
        givenTheConfigurationProperty("transmissionMode", "SMOKE_SIGNALS");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRejectANegativeResponseTimeout() {
        givenADevice();
        givenTheConfigurationProperty("respTimeout", "-1");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRejectAnUnknownConnectionType() {
        givenADevice();
        givenTheConfigurationProperty("connectionType", "CARRIER_PIGEON");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRequireAnEthernetPort() {
        givenADevice();
        givenTheConfigurationWithout("ethport");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRequireAnIpAddress() {
        givenADevice();
        givenTheConfigurationWithout("ipAddress");

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldRefuseToBeConfiguredTwiceInARow() {
        givenADevice();
        givenAConfiguredConnection();

        whenTheConnectionIsConfigured();

        thenTheFailureCodeIs(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    @Test
    public void shouldAcceptAFreshConfigurationAfterADisconnect() {
        givenADevice();
        givenAConfiguredConnection();
        givenTheDeviceIsDisconnected();

        whenTheConnectionIsConfigured();

        thenNoFailureIsReported();
        thenTheConnectionStatusIs(KuraConnectionStatus.DISCONNECTED);
    }

    @Test
    public void shouldDisconnectOnDeactivation() {
        givenADevice();
        givenTheComponentIsActivated();
        givenAConfiguredConnection();

        whenTheComponentIsDeactivated();

        thenTheConnectionStatusIs(KuraConnectionStatus.NEVERCONNECTED);
    }

    @Test
    public void shouldRefuseEveryReadBeforeAConfiguration() {
        givenADevice();

        whenEveryReadIsAttempted();

        thenEveryAttemptFailedWith(ModbusProtocolErrorCode.NOT_CONNECTED);
    }

    @Test
    public void shouldRefuseEveryWriteBeforeAConfiguration() {
        givenADevice();

        whenEveryWriteIsAttempted();

        thenEveryAttemptFailedWith(ModbusProtocolErrorCode.NOT_CONNECTED);
    }

    private ModbusProtocolDevice device;
    private Properties config;
    private ModbusProtocolException failure;
    private final List<ModbusProtocolException> attemptFailures = new ArrayList<>();
    private int attempts;

    @Before
    public void setup() {
        this.config = new Properties();
        this.config.setProperty("connectionType", ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);
        this.config.setProperty("transmissionMode", ModbusTransmissionMode.RTU);
        this.config.setProperty("respTimeout", "100");
        this.config.setProperty("ipAddress", "127.0.0.1");
        // nothing is listening: configuring a TCP connection must not open it
        this.config.setProperty("ethport", "1");
    }

    private void givenADevice() {
        this.device = new ModbusProtocolDevice();
    }

    private void whenADeviceIsCreated() {
        this.device = new ModbusProtocolDevice();
    }

    private void givenTheConfigurationWithout(String property) {
        this.config.remove(property);
    }

    private void givenTheConfigurationProperty(String property, String value) {
        this.config.setProperty(property, value);
    }

    private void givenAConfiguredConnection() {
        try {
            this.device.configureConnection(this.config);
        } catch (ModbusProtocolException e) {
            fail("the connection could not be configured: " + e.getMessage());
        }
    }

    private void givenTheDeviceIsDisconnected() {
        whenTheDeviceIsDisconnected();
    }

    private void givenTheComponentIsActivated() {
        this.device.activate(null);
    }

    private void whenTheConnectionIsConfigured() {
        try {
            this.device.configureConnection(this.config);
        } catch (ModbusProtocolException e) {
            this.failure = e;
        }
    }

    private void whenTheDeviceIsConnected() {
        try {
            this.device.connect();
        } catch (ModbusProtocolException e) {
            this.failure = e;
        }
    }

    private void whenTheDeviceIsDisconnected() {
        try {
            this.device.disconnect();
        } catch (ModbusProtocolException e) {
            this.failure = e;
        }
    }

    private void whenTheComponentIsDeactivated() {
        this.device.deactivate(null);
    }

    private void whenEveryReadIsAttempted() {
        attempt(() -> this.device.readCoils(1, 0, 1));
        attempt(() -> this.device.readDiscreteInputs(1, 0, 1));
        attempt(() -> this.device.readHoldingRegisters(1, 0, 1));
        attempt(() -> this.device.readInputRegisters(1, 0, 1));
        attempt(() -> this.device.readExceptionStatus(1));
        attempt(() -> this.device.getCommEventCounter(1));
        attempt(() -> this.device.getCommEventLog(1));
    }

    private void whenEveryWriteIsAttempted() {
        attempt(() -> this.device.writeSingleCoil(1, 0, true));
        attempt(() -> this.device.writeSingleRegister(1, 0, 1));
        attempt(() -> this.device.writeMultipleCoils(1, 0, new boolean[] { true }));
        attempt(() -> this.device.writeMultipleRegister(1, 0, new int[] { 1 }));
    }

    private void attempt(ModbusCall call) {
        this.attempts++;
        try {
            call.run();
        } catch (ModbusProtocolException e) {
            this.attemptFailures.add(e);
        }
    }

    private void thenNoFailureIsReported() {
        if (this.failure != null) {
            fail("unexpected failure: " + this.failure.getMessage());
        }
    }

    private void thenTheFailureCodeIs(ModbusProtocolErrorCode expected) {
        assertNotNull("no failure was reported", this.failure);
        assertEquals(expected, this.failure.getCode());
    }

    private void thenTheProtocolNameIs(String expected) {
        assertEquals(expected, this.device.getProtocolName());
    }

    private void thenTheConnectionStatusIs(int expected) {
        assertEquals(expected, this.device.getConnectStatus());
    }

    private void thenEveryAttemptFailedWith(ModbusProtocolErrorCode expected) {
        assertEquals(this.attempts, this.attemptFailures.size());
        for (ModbusProtocolException attemptFailure : this.attemptFailures) {
            assertEquals(expected, attemptFailure.getCode());
        }
    }

    private interface ModbusCall {

        void run() throws ModbusProtocolException;
    }
}
