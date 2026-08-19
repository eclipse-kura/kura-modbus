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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Properties;

import org.eclipse.kura.KuraConnectionStatus;
import org.eclipse.kura.protocol.modbus.test.ScriptedTcpServer;
import org.eclipse.kura.protocol.modbus.test.ScriptedTcpServer.Behaviour;
import org.junit.After;
import org.junit.Test;

/**
 * Covers the failure paths of the Modbus TCP transport, which the happy path
 * tests in {@link ModbusProtocolDeviceTest} do not reach.
 */
public class EthernetModbusProtocolDeviceTest {

    private static final int UNIT = 1;

    private ScriptedTcpServer server;
    private ModbusProtocolDevice device;

    @After
    public void tearDown() throws IOException {
        if (this.server != null) {
            this.server.close();
        }
    }

    @Test
    public void shouldRefuseAsciiOverTcp() throws Exception {
        ModbusProtocolDevice device = connect(Behaviour.SILENT, ModbusTransmissionMode.ASCII,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);

        assertFailsWith(ModbusProtocolErrorCode.METHOD_NOT_SUPPORTED,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldFailWhenNothingIsListening() throws Exception {
        // take a port, then release it so that the connection is refused
        ScriptedTcpServer closed = new ScriptedTcpServer(Behaviour.CLOSE);
        int port = closed.getPort();
        closed.close();

        ModbusProtocolDevice device = configure(port, ModbusTransmissionMode.RTU,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);
        device.connect();

        assertEquals(KuraConnectionStatus.DISCONNECTED, device.getConnectStatus());
        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldFailWhenThePeerHangsUp() throws Exception {
        ModbusProtocolDevice device = connect(Behaviour.CLOSE, ModbusTransmissionMode.RTU,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);

        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldTimeOutWhenThePeerStaysSilent() throws Exception {
        ModbusProtocolDevice device = connect(Behaviour.SILENT, ModbusTransmissionMode.RTU,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("Recv timeout"));
    }

    @Test
    public void shouldRejectAResponseFromTheWrongUnit() throws Exception {
        // MBAP header, then unit 2 answering a request addressed to unit 1
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 4, 2, 3, 2, 0, 1);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("incorrect modbus id"));
    }

    @Test
    public void shouldRejectAResponseForTheWrongFunction() throws Exception {
        // function 4 answering a function 3 request
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 4, 1, 4, 2, 0, 1);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("incorrect function number"));
    }

    @Test
    public void shouldReportAnErrorResponse() throws Exception {
        // function 3 with the error bit set, exception code 2
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 3, 1, 0x83, 2);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("Modbus responds an error"));
    }

    @Test
    public void shouldRejectAShortRegisterResponse() throws Exception {
        // announces a single byte of payload where two are expected
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 3, 1, 3, 1, 0);

        assertFailsWith(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldRejectAnUnsupportedFunction() throws Exception {
        // the driver never learnt to parse the answers of functions 7, 11 and 12
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 4, 1, 7, 1, 0xAA);

        assertFailsWith(ModbusProtocolErrorCode.INVALID_DATA_TYPE, () -> device.readExceptionStatus(UNIT));
    }

    @Test
    public void shouldRejectAnUnsupportedEventCounterFunction() throws Exception {
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 4, 1, 11, 1, 0xAA);

        assertFailsWith(ModbusProtocolErrorCode.INVALID_DATA_TYPE, () -> device.getCommEventCounter(UNIT));
    }

    @Test
    public void shouldRejectAnUnsupportedEventLogFunction() throws Exception {
        ModbusProtocolDevice device = respondWith(0, 1, 0, 0, 0, 4, 1, 12, 1, 0xAA);

        assertFailsWith(ModbusProtocolErrorCode.INVALID_DATA_TYPE, () -> device.getCommEventLog(UNIT));
    }

    @Test
    public void shouldNotCompleteATransactionInRtuOverTcpMode() throws Exception {
        // RTU over TCP has no frame delimiter here, so the read can only time out
        ModbusProtocolDevice device = connect(Behaviour.SILENT, ModbusTransmissionMode.RTU,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_RTU);

        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private interface ModbusCall {

        void run() throws ModbusProtocolException;
    }

    private static ModbusProtocolException assertFailsWith(ModbusProtocolErrorCode expected, ModbusCall call) {
        try {
            call.run();
            fail("expected a " + expected + " failure");
            return null;
        } catch (ModbusProtocolException e) {
            assertEquals(expected, e.getCode());
            return e;
        }
    }

    private ModbusProtocolDevice respondWith(int... frame) throws Exception {
        byte[] response = new byte[frame.length];
        for (int i = 0; i < frame.length; i++) {
            response[i] = (byte) frame[i];
        }
        return connect(Behaviour.RESPOND, ModbusTransmissionMode.RTU,
                ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP, response);
    }

    private ModbusProtocolDevice connect(Behaviour behaviour, String transmissionMode, String connectionType,
            byte... response) throws Exception {
        this.server = new ScriptedTcpServer(behaviour, response);
        this.device = configure(this.server.getPort(), transmissionMode, connectionType);
        this.device.connect();
        return this.device;
    }

    private ModbusProtocolDevice configure(int port, String transmissionMode, String connectionType)
            throws ModbusProtocolException {
        Properties config = new Properties();
        config.setProperty("connectionType", connectionType);
        config.setProperty("transmissionMode", transmissionMode);
        // keep the timeout paths quick
        config.setProperty("respTimeout", "300");
        config.setProperty("ipAddress", "127.0.0.1");
        config.setProperty("ethport", Integer.toString(port));

        ModbusProtocolDevice device = new ModbusProtocolDevice();
        device.configureConnection(config);
        return device;
    }
}
