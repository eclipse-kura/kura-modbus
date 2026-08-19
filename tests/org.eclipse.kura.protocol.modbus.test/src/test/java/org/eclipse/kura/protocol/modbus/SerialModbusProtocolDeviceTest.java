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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.Properties;

import org.eclipse.kura.KuraConnectionStatus;
import org.eclipse.kura.comm.CommConnection;
import org.eclipse.kura.protocol.modbus.test.ModbusSlave;
import org.eclipse.kura.protocol.modbus.test.SerialSlave;
import org.eclipse.kura.protocol.modbus.test.SerialSlave.Fault;
import org.eclipse.kura.usb.UsbService;
import org.eclipse.kura.usb.UsbTtyDevice;
import org.junit.Test;
import org.osgi.service.io.ConnectionFactory;

/**
 * Exercises the RS232 side of the driver against an in-memory Modbus slave, so
 * that the RTU and ASCII framing are covered without a serial port.
 */
public class SerialModbusProtocolDeviceTest {

    private static final String USB_PORT = "1-1.3";
    private static final String DEVICE_NODE = "/dev/ttyUSB0";
    private static final int UNIT = 1;

    private CommConnection connection;

    @Test
    public void shouldReadHoldingRegistersInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        assertArrayEquals(new int[] { 1, 2, 3 }, device.readHoldingRegisters(UNIT, 0, 3));
    }

    @Test
    public void shouldReadInputRegistersInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        assertArrayEquals(new int[] { 1 }, device.readInputRegisters(UNIT, 4, 1));
    }

    @Test
    public void shouldReadCoilsInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        assertArrayEquals(new boolean[] { true, true, true }, device.readCoils(UNIT, 0, 3));
    }

    @Test
    public void shouldReadDiscreteInputsInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        assertArrayEquals(new boolean[] { true, true }, device.readDiscreteInputs(UNIT, 0, 2));
    }

    @Test
    public void shouldWriteSingleCoilInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.writeSingleCoil(UNIT, 3, true);
    }

    @Test
    public void shouldWriteSingleRegisterInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.writeSingleRegister(UNIT, 3, 0x1234);
    }

    @Test
    public void shouldWriteMultipleCoilsInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.writeMultipleCoils(UNIT, 0, new boolean[] { true, false, true });
    }

    @Test
    public void shouldWriteMultipleRegistersInRtuMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.writeMultipleRegister(UNIT, 0, new int[] { 1, 2 });
    }

    @Test
    public void shouldReadHoldingRegistersInAsciiMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.ASCII, ModbusSlave::reply);

        assertArrayEquals(new int[] { 1, 2 }, device.readHoldingRegisters(UNIT, 0, 2));
    }

    @Test
    public void shouldReadCoilsInAsciiMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.ASCII, ModbusSlave::reply);

        assertArrayEquals(new boolean[] { true, true }, device.readCoils(UNIT, 0, 2));
    }

    @Test
    public void shouldWriteSingleRegisterInAsciiMode() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.ASCII, ModbusSlave::reply);

        device.writeSingleRegister(UNIT, 1, 7);
    }

    @Test
    public void shouldReportTheSerialConnectionAsConnected() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        assertEquals(KuraConnectionStatus.CONNECTED, device.getConnectStatus());
    }

    @Test
    public void shouldCloseTheSerialConnectionOnDisconnect() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.disconnect();

        verify(this.connection).close();
        assertEquals(KuraConnectionStatus.NEVERCONNECTED, device.getConnectStatus());
    }

    @Test
    public void shouldFailWhenTheSerialConnectionCannotBeClosed() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);
        doThrow(new IOException("cannot close")).when(this.connection).close();

        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE, device::disconnect);
    }

    @Test
    public void shouldReportAnExceptionResponse() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::refuse);

        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldTimeOutWhenTheSlaveStaysSilent() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.SILENT);

        assertFailsWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldDiscardAFrameWithABadCrc() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.BAD_CHECKSUM);

        // the frame is dropped a byte at a time, and no more data ever arrives
        assertFailsWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldRejectAnAsciiFrameWithABadLrc() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.ASCII, ModbusSlave::reply, Fault.BAD_CHECKSUM);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("Bad LRC"));
    }

    @Test
    public void shouldFailWhenTheStreamEndsMidFrame() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.END_OF_STREAM);

        ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
        assertTrue(failure.getMessage().contains("End of stream"));
    }

    @Test
    public void shouldRejectAnIncompleteSerialConfiguration() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        Properties config = serialConfig(ModbusTransmissionMode.RTU);
        config.remove("baudRate");

        assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION, () -> device.configureConnection(config));
    }

    @Test
    public void shouldReportAFailureToOpenThePort() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        when(this.factory.createConnection(anyString(), anyInt(), anyBoolean()))
                .thenThrow(new IOException("no such port"));

        assertFailsWith(ModbusProtocolErrorCode.CONNECTION_FAILURE,
                () -> device.configureConnection(serialConfig(ModbusTransmissionMode.RTU)));
    }

    @Test
    public void shouldReportAFailureToOpenTheStreams() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        when(this.connection.openInputStream()).thenThrow(new IOException("no streams"));

        assertFailsWith(ModbusProtocolErrorCode.CONNECTION_FAILURE,
                () -> device.configureConnection(serialConfig(ModbusTransmissionMode.RTU)));
    }

    @Test
    public void shouldRefuseToConfigureAnAbsentPort() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        Properties config = serialConfig(ModbusTransmissionMode.RTU);
        config.setProperty("port", "1-9.9");

        assertFailsWith(ModbusProtocolErrorCode.NOT_AVAILABLE, () -> device.configureConnection(config));
    }

    @Test
    public void shouldResolveAPortGivenByItsDeviceNode() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        Properties config = serialConfig(ModbusTransmissionMode.RTU);
        config.setProperty("port", "/dev/does-not-exist");

        assertFailsWith(ModbusProtocolErrorCode.NOT_AVAILABLE, () -> device.configureConnection(config));
    }

    @Test
    public void shouldRefuseToConfigureWithoutAUsbService() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        when(this.usbService.getUsbTtyDevices()).thenReturn(null);

        assertFailsWith(ModbusProtocolErrorCode.NOT_AVAILABLE,
                () -> device.configureConnection(serialConfig(ModbusTransmissionMode.RTU)));
    }

    @Test
    public void shouldRebindTheServicesAfterAnUnbind() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));

        device.unsetConnectionFactory(this.factory);
        device.unsetUsbService(this.usbService);
        device.setConnectionFactory(this.factory);
        device.setUsbService(this.usbService);

        device.configureConnection(serialConfig(ModbusTransmissionMode.RTU));

        assertEquals(KuraConnectionStatus.CONNECTED, device.getConnectStatus());
    }

    @Test
    public void shouldRequireAPortName() throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
        Properties config = serialConfig(ModbusTransmissionMode.RTU);
        config.remove("port");

        assertFailsWith(ModbusProtocolErrorCode.NOT_AVAILABLE, () -> device.configureConnection(config));
    }

    @Test
    public void shouldRequireEveryPortParameter() throws Exception {
        for (String parameter : new String[] { "stopBits", "parity", "bitsPerWord" }) {
            ModbusProtocolDevice device = newDevice(new SerialSlave(false, ModbusSlave::reply));
            Properties config = serialConfig(ModbusTransmissionMode.RTU);
            config.remove(parameter);

            assertFailsWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION,
                    () -> device.configureConnection(config));
        }
    }

    @Test
    public void shouldTolerateADoubleDisconnect() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);

        device.disconnect();
        device.disconnect();
    }

    @Test
    public void shouldSurviveAFailingDisconnectOnDeactivation() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply);
        doThrow(new IOException("cannot close")).when(this.connection).close();

        device.deactivate(null);
    }

    @Test
    public void shouldDiscardAnExceptionResponseWithABadCrc() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::refuse, Fault.BAD_CHECKSUM);

        // the frame is not trusted, so it is dropped rather than reported
        assertFailsWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldDiscardAnEchoWithABadCrc() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.BAD_CHECKSUM);

        assertFailsWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT, () -> device.writeSingleRegister(UNIT, 0, 1));
    }

    @Test
    public void shouldIgnoreBytesThatDoNotOpenAFrame() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::fromAnotherUnit);

        // the leading address never matches, so no frame is ever assembled
        assertFailsWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldReportAFailureToWriteTheRequest() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.WRITE_ERROR);

        assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                () -> device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldFlushStaleBytesBeforeTheNextTransaction() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.EXTRA_BYTES);

        assertArrayEquals(new int[] { 1 }, device.readHoldingRegisters(UNIT, 0, 1));
        // the stray byte of the first answer must not corrupt the second one
        assertArrayEquals(new int[] { 1 }, device.readHoldingRegisters(UNIT, 0, 1));
    }

    @Test
    public void shouldGiveUpWhenTheThreadIsInterrupted() throws Exception {
        ModbusProtocolDevice device = connect(ModbusTransmissionMode.RTU, ModbusSlave::reply, Fault.SILENT);

        Thread.currentThread().interrupt();
        try {
            ModbusProtocolException failure = assertFailsWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    () -> device.readHoldingRegisters(UNIT, 0, 1));
            assertTrue(failure.getMessage().contains("Thread interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private ConnectionFactory factory;
    private UsbService usbService;

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

    private ModbusProtocolDevice connect(String transmissionMode, SerialSlave.Responder responder) throws Exception {
        return connect(transmissionMode, responder, Fault.NONE);
    }

    private ModbusProtocolDevice connect(String transmissionMode, SerialSlave.Responder responder, Fault fault)
            throws Exception {
        ModbusProtocolDevice device = newDevice(new SerialSlave(ModbusTransmissionMode.ASCII.equals(transmissionMode),
                responder, fault));
        device.configureConnection(serialConfig(transmissionMode));
        device.connect();
        return device;
    }

    private ModbusProtocolDevice newDevice(SerialSlave slave) throws IOException {
        this.connection = mock(CommConnection.class);
        when(this.connection.openInputStream()).thenReturn(slave.getInputStream());
        when(this.connection.openOutputStream()).thenReturn(slave.getOutputStream());

        this.factory = mock(ConnectionFactory.class);
        when(this.factory.createConnection(anyString(), anyInt(), anyBoolean())).thenReturn(this.connection);

        UsbTtyDevice tty = mock(UsbTtyDevice.class);
        when(tty.getUsbPort()).thenReturn(USB_PORT);
        when(tty.getDeviceNode()).thenReturn(DEVICE_NODE);

        this.usbService = mock(UsbService.class);
        when(this.usbService.getUsbTtyDevices()).thenReturn(Collections.singletonList(tty));

        ModbusProtocolDevice device = new ModbusProtocolDevice();
        device.setConnectionFactory(this.factory);
        device.setUsbService(this.usbService);
        return device;
    }

    private static Properties serialConfig(String transmissionMode) {
        Properties config = new Properties();
        config.setProperty("connectionType", ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_SERIAL);
        config.setProperty("transmissionMode", transmissionMode);
        // keep the failure paths quick
        config.setProperty("respTimeout", "150");
        config.setProperty("port", USB_PORT);
        config.setProperty("baudRate", "9600");
        config.setProperty("stopBits", "1");
        config.setProperty("parity", "0");
        config.setProperty("bitsPerWord", "8");
        return config;
    }
}
