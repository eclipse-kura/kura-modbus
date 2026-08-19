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
import static org.junit.Assert.fail;

import java.util.Properties;

import org.eclipse.kura.KuraConnectionStatus;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers configuration validation and the guards that reject calls made before
 * a connection has been configured.
 */
public class ModbusProtocolDeviceConfigurationTest {

    private ModbusProtocolDevice device;

    @Before
    public void setup() {
        this.device = new ModbusProtocolDevice();
    }

    @Test
    public void shouldBeNamedModbus() {
        assertEquals("modbus", this.device.getProtocolName());
    }

    @Test
    public void shouldStartOutNeverConnected() {
        assertEquals(KuraConnectionStatus.NEVERCONNECTED, this.device.getConnectStatus());
    }

    @Test
    public void shouldRejectAConnectionWithoutConfiguration() {
        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION, () -> this.device.connect());
    }

    @Test
    public void shouldIgnoreADisconnectWithoutConfiguration() throws ModbusProtocolException {
        this.device.disconnect();
    }

    @Test
    public void shouldRequireAConnectionType() {
        Properties config = tcpConfig();
        config.remove("connectionType");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRequireATransmissionMode() {
        Properties config = tcpConfig();
        config.remove("transmissionMode");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRequireAResponseTimeout() {
        Properties config = tcpConfig();
        config.remove("respTimeout");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRejectAnUnknownTransmissionMode() {
        Properties config = tcpConfig();
        config.setProperty("transmissionMode", "SMOKE_SIGNALS");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRejectANegativeResponseTimeout() {
        Properties config = tcpConfig();
        config.setProperty("respTimeout", "-1");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRejectAnUnknownConnectionType() {
        Properties config = tcpConfig();
        config.setProperty("connectionType", "CARRIER_PIGEON");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRequireAnEthernetPort() {
        Properties config = tcpConfig();
        config.remove("ethport");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRequireAnIpAddress() {
        Properties config = tcpConfig();
        config.remove("ipAddress");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(config));
    }

    @Test
    public void shouldRefuseToConfigureTwiceInARow() throws ModbusProtocolException {
        this.device.configureConnection(tcpConfig());

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                () -> this.device.configureConnection(tcpConfig()));
    }

    @Test
    public void shouldAcceptAFreshConfigurationAfterADisconnect() throws ModbusProtocolException {
        this.device.configureConnection(tcpConfig());
        this.device.disconnect();

        this.device.configureConnection(tcpConfig());

        assertEquals(KuraConnectionStatus.DISCONNECTED, this.device.getConnectStatus());
    }

    @Test
    public void shouldDisconnectOnDeactivation() throws ModbusProtocolException {
        this.device.activate(null);
        this.device.configureConnection(tcpConfig());

        this.device.deactivate(null);

        assertEquals(KuraConnectionStatus.NEVERCONNECTED, this.device.getConnectStatus());
    }

    @Test
    public void shouldRejectEveryReadBeforeConfiguration() {
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.readCoils(1, 0, 1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.readDiscreteInputs(1, 0, 1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.readHoldingRegisters(1, 0, 1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.readInputRegisters(1, 0, 1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.readExceptionStatus(1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.getCommEventCounter(1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.getCommEventLog(1));
    }

    @Test
    public void shouldRejectEveryWriteBeforeConfiguration() {
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.writeSingleCoil(1, 0, true));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED, () -> this.device.writeSingleRegister(1, 0, 1));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED,
                () -> this.device.writeMultipleCoils(1, 0, new boolean[] { true }));
        assertFailsWith(ModbusProtocolErrorCode.NOT_CONNECTED,
                () -> this.device.writeMultipleRegister(1, 0, new int[] { 1 }));
    }

    private interface ModbusCall {

        void run() throws ModbusProtocolException;
    }

    private static void assertFailsWith(ModbusProtocolErrorCode expected, ModbusCall call) {
        try {
            call.run();
            fail("expected a " + expected + " failure");
        } catch (ModbusProtocolException e) {
            assertEquals(expected, e.getCode());
        }
    }

    private static Properties tcpConfig() {
        Properties config = new Properties();
        config.setProperty("connectionType", ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);
        config.setProperty("transmissionMode", ModbusTransmissionMode.RTU);
        config.setProperty("respTimeout", "100");
        config.setProperty("ipAddress", "127.0.0.1");
        // nothing is listening: configuring a TCP connection must not open it
        config.setProperty("ethport", "1");
        return config;
    }
}
