/*******************************************************************************
 * Copyright (c) 2011, 2026 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 *  Red Hat Inc
 *******************************************************************************/
package org.eclipse.kura.protocol.modbus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.kura.KuraConnectionStatus;
import org.eclipse.kura.comm.CommConnection;
import org.eclipse.kura.comm.CommURI;
import org.eclipse.kura.usb.UsbService;
import org.eclipse.kura.usb.UsbTtyDevice;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.io.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Modbus protocol implements a subset of the Modbus standard command set.
 * It also provides for the extension of some data typing to allow register
 * pairings to hold 32 bit data (see the configureDataMap for more detail).
 * <p>
 * The protocol supports RTU and ASCII mode operation.
 *
 */
public class ModbusProtocolDevice implements ModbusProtocolDeviceService {

    private static final Logger logger = LoggerFactory.getLogger(ModbusProtocolDevice.class);

    static final String PROTOCOL_NAME = "modbus";
    public static final String PROTOCOL_CONNECTION_TYPE_SERIAL = "RS232";
    public static final String PROTOCOL_CONNECTION_TYPE_ETHER_RTU = "TCP-RTU";
    public static final String PROTOCOL_CONNECTION_TYPE_ETHER_TCP = "TCP/IP";

    private static final int MAX_RESPONSE_LENGTH = 262;
    private static final int RTU_MINIMUM_LENGTH = 5;
    private static final int ASCII_MINIMUM_LENGTH = 11;
    private static final int ECHO_FRAME_LENGTH = 8;
    private static final int MBAP_HEADER_LENGTH = 6;
    private static final int MAX_RECV_ATTEMPTS = 1000;
    private static final int CHARACTER_TIMEOUT = 100;
    private static final long POLL_INTERVAL = 5;
    private static final byte ASCII_FRAME_START = ':';
    private static final byte CR = 13;
    private static final byte LF = 10;

    private ConnectionFactory connectionFactory;
    private UsbService usbService;

    private int respTout;
    private int txMode;
    private boolean connConfigd = false;
    private boolean protConfigd = false;
    private Communicate comm;
    private Properties modbusProperties = null;

    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public void unsetConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = null;
    }

    public void setUsbService(UsbService usbService) {
        this.usbService = usbService;
    }

    public void unsetUsbService(UsbService usbService) {
        this.usbService = null;
    }

    private boolean serialPortExists() {
        if (this.modbusProperties == null) {
            return false;
        }

        String portName = this.modbusProperties.getProperty("port");
        if (portName == null) {
            return false;
        }
        if (portName.contains("/dev/")) {
            return new File(portName).exists();
        }
        return resolveUsbPort(portName);
    }

    private boolean resolveUsbPort(String portName) {
        List<UsbTtyDevice> utd = this.usbService.getUsbTtyDevices();
        if (utd == null) {
            return false;
        }
        for (UsbTtyDevice u : utd) {
            if (portName.equals(u.getUsbPort())) {
                // replace device number with tty
                this.modbusProperties.setProperty("port", u.getDeviceNode());
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------
    //
    // Activation APIs
    //
    // ----------------------------------------------------------------
    protected void activate(ComponentContext componentContext) {
        logger.info("activate...");
    }

    protected void deactivate(ComponentContext componentContext) {
        logger.info("deactivate...");
        try {
            disconnect();
        } catch (ModbusProtocolException e) {
            logger.error("ModbusProtocolException :  {}", e.getCode());
        }
    }

    /**
     * two connection types are available:
     * <ul>
     * <li>serial mode (PROTOCOL_CONNECTION_TYPE_SERIAL)
     *
     * <li>Ethernet with 2 possible modes : RTU over TCP/IP (PROTOCOL_CONNECTION_TYPE_ETHER_RTU) or real MODBUS-TCP/IP
     * (PROTOCOL_CONNECTION_TYPE_ETHER_TCP).
     * <ul>
     * <p>
     * <h4>PROTOCOL_CONNECTION_TYPE_SERIAL</h4>
     * see {@link org.eclipse.kura.comm.CommConnection CommConnection} package for more detail.
     * <table border="1">
     * <tr>
     * <th>Key</th>
     * <th>Description</th>
     * </tr>
     * <tr>
     * <td>connectionType</td>
     * <td>"RS232" (from PROTOCOL_CONNECTION_TYPE_SERIAL). This parameter indicates the connection type for the
     * configuration. See {@link org.eclipse.kura.comm.CommConnection CommConnection} for more details on serial port
     * configuration.
     * </tr>
     * <tr>
     * <td>port</td>
     * <td>the actual device port, such as "/dev/ttyUSB0" in linux</td>
     * </tr>
     * <tr>
     * <td>baudRate</td>
     * <td>baud rate to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>stopBits</td>
     * <td>number of stop bits to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>parity</td>
     * <td>parity mode to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>bitsPerWord</td>
     * <td>only RTU mode supported, bitsPerWord must be 8</td>
     * </tr>
     * </table>
     * <p>
     * <h4>PROTOCOL_CONNECTION_TYPE_ETHER_TCP</h4>
     * The Ethernet mode merely opens a socket and sends the full RTU mode Modbus packet over that socket connection and
     * expects to receive a full RTU mode Modbus response, including the CRC bytes.
     * <table border="1">
     * <tr>
     * <th>Key</th>
     * <th>Description</th>
     * </tr>
     * <tr>
     * <td>connectionType</td>
     * <td>"ETHERTCP" (from PROTOCOL_CONNECTION_TYPE_ETHER_TCP). This parameter indicates the connection type for the
     * configurator.
     * </tr>
     * <tr>
     * <td>ipAddress</td>
     * <td>the 4 octet IP address of the field device (xxx.xxx.xxx.xxx)</td>
     * </tr>
     * <tr>
     * <td>port</td>
     * <td>port on the field device to connect to</td>
     * </tr>
     * </table>
     */
    @Override
    public void configureConnection(Properties connectionConfig) throws ModbusProtocolException {
        String connectionType = connectionConfig.getProperty("connectionType");
        if (connectionType == null) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
        }

        this.modbusProperties = connectionConfig;

        String transmissionMode = connectionConfig.getProperty("transmissionMode");
        String respTimeout = connectionConfig.getProperty("respTimeout");
        if (this.protConfigd || transmissionMode == null || respTimeout == null) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
        }
        this.txMode = parseTransmissionMode(transmissionMode);
        this.respTout = Integer.parseInt(respTimeout);
        if (this.respTout < 0) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
        }
        this.protConfigd = true;

        if (this.connConfigd) {
            this.comm.disconnect();
            this.comm = null;
            this.connConfigd = false;
        }

        this.comm = createCommunicate(connectionType, connectionConfig);
        this.connConfigd = true;
    }

    private static int parseTransmissionMode(String transmissionMode) throws ModbusProtocolException {
        if (ModbusTransmissionMode.RTU.equals(transmissionMode)) {
            return ModbusTransmissionMode.RTU_MODE;
        }
        if (ModbusTransmissionMode.ASCII.equals(transmissionMode)) {
            return ModbusTransmissionMode.ASCII_MODE;
        }
        throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    private Communicate createCommunicate(String connectionType, Properties connectionConfig)
            throws ModbusProtocolException {
        if (PROTOCOL_CONNECTION_TYPE_SERIAL.equals(connectionType)) {
            if (!serialPortExists()) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.NOT_AVAILABLE);
            }
            return new SerialCommunicate(this.connectionFactory, connectionConfig);
        }
        if (PROTOCOL_CONNECTION_TYPE_ETHER_TCP.equals(connectionType)
                || PROTOCOL_CONNECTION_TYPE_ETHER_RTU.equals(connectionType)) {
            return new EthernetCommunicate(connectionConfig);
        }
        throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
    }

    private boolean isAsciiMode() {
        return this.txMode == ModbusTransmissionMode.ASCII_MODE;
    }

    /**
     * get the name "modbus" for this protocol
     *
     * @return "modbus"
     */
    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    @Override
    public void connect() throws ModbusProtocolException {
        if (!this.connConfigd) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
        }
        this.comm.connect();
    }

    @Override
    public void disconnect() throws ModbusProtocolException {
        if (this.connConfigd) {
            this.comm.disconnect();
            this.comm = null;
            this.connConfigd = false;
            logger.info("Serial comm disconnected");
        }
        this.protConfigd = false;
    }

    @Override
    public int getConnectStatus() {
        if (!this.connConfigd) {
            return KuraConnectionStatus.NEVERCONNECTED;
        }
        return this.comm.getConnectStatus();
    }

    /**
     * @return true if the given function code is answered by echoing back the
     *         header of the write command.
     */
    private static boolean isWriteEcho(byte functionCode) {
        return functionCode == ModbusFunctionCodes.FORCE_SINGLE_COIL
                || functionCode == ModbusFunctionCodes.PRESET_SINGLE_REG
                || functionCode == ModbusFunctionCodes.FORCE_MULTIPLE_COILS
                || functionCode == ModbusFunctionCodes.PRESET_MULTIPLE_REGS;
    }

    /**
     * @return true if the given function code is answered by a byte count followed
     *         by that many bytes of data.
     */
    private static boolean isDataRead(byte functionCode) {
        return functionCode == ModbusFunctionCodes.READ_COIL_STATUS
                || functionCode == ModbusFunctionCodes.READ_INPUT_STATUS
                || functionCode == ModbusFunctionCodes.READ_INPUT_REGS
                || functionCode == ModbusFunctionCodes.READ_HOLDING_REGS;
    }

    /**
     * Appends the RTU mode CRC to a Modbus command.
     */
    private static byte[] appendCrc(byte[] msg) {
        byte[] cmd = Arrays.copyOf(msg, msg.length + 2);
        // Add crc calculation to end of message
        int crc = Crc16.getCrc16(msg, msg.length, 0x0ffff);
        cmd[msg.length] = (byte) crc;
        cmd[msg.length + 1] = (byte) (crc >> 8);
        return cmd;
    }

    private static int binLrcCalc(byte[] msg) {
        int llrc = 0;
        for (byte element : msg) {
            llrc += element & 0xff;
        }
        llrc = (llrc ^ 0xff) + 1;
        return llrc;
    }

    /**
     * The only constructor must be the configuration mechanism
     */
    private abstract class Communicate {

        public abstract void connect();

        public abstract void disconnect() throws ModbusProtocolException;

        public abstract int getConnectStatus();

        public abstract byte[] msgTransaction(byte[] msg) throws ModbusProtocolException;
    }

    /**
     * Installation of a serial connection to communicate, using javax.comm.SerialPort
     * <p>
     * <table border="1">
     * <tr>
     * <th>Key</th>
     * <th>Description</th>
     * </tr>
     * <tr>
     * <td>port</td>
     * <td>the actual device port, such as "/dev/ttyUSB0" in linux</td>
     * </tr>
     * <tr>
     * <td>serialMode</td>
     * <td>SERIAL_232 or SERIAL_485</td>
     * </tr>
     * <tr>
     * <td>baudRate</td>
     * <td>baud rate to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>stopBits</td>
     * <td>number of stop bits to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>parity</td>
     * <td>parity mode to be configured for the port</td>
     * </tr>
     * <tr>
     * <td>bitsPerWord</td>
     * <td>only RTU mode supported, bitsPerWord must be 8</td>
     * </tr>
     * </table>
     * see {@link org.eclipse.kura.comm.CommConnection CommConnection} package for more detail.
     */
    private final class SerialCommunicate extends Communicate {

        InputStream in;
        OutputStream out;
        CommConnection conn = null;

        public SerialCommunicate(ConnectionFactory connFactory, Properties connectionConfig)
                throws ModbusProtocolException {
            logger.info("Configure serial connection");

            String sPort;
            String sBaud;
            String sStop;
            String sParity;
            String sBits;

            if ((sPort = connectionConfig.getProperty("port")) == null
                    || (sBaud = connectionConfig.getProperty("baudRate")) == null
                    || (sStop = connectionConfig.getProperty("stopBits")) == null
                    || (sParity = connectionConfig.getProperty("parity")) == null
                    || (sBits = connectionConfig.getProperty("bitsPerWord")) == null) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
            }

            int baud = Integer.parseInt(sBaud);
            int stop = Integer.parseInt(sStop);
            int parity = Integer.parseInt(sParity);
            int bits = Integer.parseInt(sBits);

            String uri = new CommURI.Builder(sPort).withBaudRate(baud).withDataBits(bits).withStopBits(stop)
                    .withParity(parity).withTimeout(2000).build().toString();

            try {
                this.conn = (CommConnection) connFactory.createConnection(uri, 1, false);
            } catch (IOException e1) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.CONNECTION_FAILURE, e1.getMessage());
            }

            // get the streams
            try {
                this.in = this.conn.openInputStream();
                this.out = this.conn.openOutputStream();
            } catch (Exception e) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.CONNECTION_FAILURE, e);
            }
            logger.info("Serial connection connected");
        }

        @Override
        public void connect() {
            /*
             * always connected
             */
        }

        @Override
        public void disconnect() throws ModbusProtocolException {
            if (this.conn != null) {
                try {
                    this.conn.close();
                    logger.debug("Serial connection closed");
                } catch (IOException e) {
                    throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, e.getMessage());
                }
                this.conn = null;
            }
        }

        @Override
        public int getConnectStatus() {
            return KuraConnectionStatus.CONNECTED;
        }

        /**
         * convertCommandToAscii: convert a binary command into a standard Modbus
         * ASCII frame
         */
        private byte[] convertCommandToAscii(byte[] msg) {
            int lrc = binLrcCalc(msg);

            char[] hexArray = "0123456789ABCDEF".toCharArray();
            byte[] ab = new byte[msg.length * 2 + 5];
            ab[0] = ASCII_FRAME_START;
            int v;
            for (int i = 0; i < msg.length; i++) {
                v = msg[i] & 0xff;
                ab[i * 2 + 1] = (byte) hexArray[v >>> 4];
                ab[i * 2 + 2] = (byte) hexArray[v & 0x0f];
            }
            v = lrc & 0x0ff;
            ab[ab.length - 4] = (byte) hexArray[v >>> 4];
            ab[ab.length - 3] = (byte) hexArray[v & 0x0f];
            ab[ab.length - 2] = CR;
            ab[ab.length - 1] = LF;
            return ab;
        }

        /**
         * msgTransaction must be called with a byte array having two extra
         * bytes for the CRC. It will return a byte array of the response to the
         * message. Validation will include checking the CRC and verifying the
         * command matches.
         */
        @Override
        public byte[] msgTransaction(byte[] msg) throws ModbusProtocolException {
            byte[] cmd = isAsciiMode() ? convertCommandToAscii(msg) : appendCrc(msg);

            // Send the message
            try {
                synchronized (this.out) {
                    synchronized (this.in) {
                        flushInput();
                        // send all data
                        this.out.write(cmd, 0, cmd.length);
                        this.out.flush();

                        return receiveResponse(msg);
                    }
                }
            } catch (IOException e) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, e.getMessage());
            }
        }

        private void flushInput() throws IOException {
            while (this.in.available() > 0) {
                this.in.read();
            }
        }

        /**
         * Reads response frames until one of them validates as the answer to the
         * given request.
         */
        private byte[] receiveResponse(byte[] msg) throws IOException, ModbusProtocolException {
            SerialResponse response = new SerialResponse(this.in, msg, isAsciiMode(),
                    ModbusProtocolDevice.this.respTout);

            for (int attempt = 0; attempt < MAX_RECV_ATTEMPTS; attempt++) {
                response.readFrame();
                response.convertFromAscii();

                byte[] payload = response.extractPayload();
                if (payload != null) {
                    return payload;
                }

                /*
                 * if required length then must have failed, drop
                 * first byte and try again
                 */
                response.dropFirstByte();
            }

            throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                    "Too much activity on recv line");
        }
    }

    /**
     * Assembles and validates the response frame of a single serial transaction.
     */
    private static final class SerialResponse {

        private final InputStream in;
        private final byte[] request;
        private final boolean ascii;

        private byte[] data = new byte[MAX_RESPONSE_LENGTH];
        private int length = 0;
        private int minimumLength;
        private int timeOut;

        SerialResponse(InputStream in, byte[] request, boolean ascii, int timeOut) {
            this.in = in;
            this.request = request;
            this.ascii = ascii;
            this.minimumLength = ascii ? ASCII_MINIMUM_LENGTH : RTU_MINIMUM_LENGTH;
            this.timeOut = timeOut;
        }

        /**
         * Reads bytes until the end of a frame has been detected.
         */
        void readFrame() throws IOException, ModbusProtocolException {
            boolean endFrame = false;
            while (!endFrame) {
                waitForData();
                readNextByte();
                if (this.ascii) {
                    endFrame = isAsciiFrameComplete();
                } else {
                    this.timeOut = CHARACTER_TIMEOUT; // move to character timeout
                    endFrame = this.length >= this.minimumLength;
                }
            }
        }

        private void waitForData() throws IOException, ModbusProtocolException {
            long start = System.currentTimeMillis();
            while (this.in.available() == 0) {
                pause();

                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > this.timeOut) {
                    logger.warn("Recv timeout : {} minimumLength={} respIndex={}", elapsed, this.minimumLength,
                            this.length);
                    throw new ModbusProtocolException(ModbusProtocolErrorCode.RESPONSE_TIMEOUT, "Recv timeout");
                }
            }
        }

        private void pause() throws ModbusProtocolException {
            try {
                // avoid a high cpu load; wait() releases
                // the monitor while pausing
                this.in.wait(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Thread interrupted");
            }
        }

        private void readNextByte() throws IOException, ModbusProtocolException {
            if (this.length > 0) {
                this.data[this.length++] = readByte();
                return;
            }
            // address byte must match first
            byte expected = this.ascii ? ASCII_FRAME_START : this.request[0];
            this.data[0] = readByte();
            if (this.data[0] == expected) {
                this.length++;
            }
        }

        private byte readByte() throws IOException, ModbusProtocolException {
            int value = this.in.read();
            if (value == -1) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        "End of stream reached while reading the response");
            }
            return (byte) value;
        }

        private boolean isAsciiFrameComplete() {
            return this.length >= 2 && this.data[this.length - 1] == LF && this.data[this.length - 2] == CR;
        }

        /**
         * Verifies the LRC of an ASCII frame and converts it to its binary form.
         */
        void convertFromAscii() throws ModbusProtocolException {
            if (!this.ascii) {
                return;
            }
            byte lrcRec = asciiLrcCalc(this.data, this.length);
            this.data = convertAsciiResponseToBin(this.data, this.length);
            byte lrcCalc = (byte) binLrcCalc(this.data);
            if (lrcRec != lrcCalc) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Bad LRC");
            }
        }

        private byte asciiLrcCalc(byte[] msg, int len) {
            char[] ac = new char[] { (char) msg[len - 4], (char) msg[len - 3] };
            return (byte) Integer.parseInt(new String(ac), 16);
        }

        /**
         * convertAsciiResponseToBin: convert a standard Modbus frame to
         * byte array
         */
        private byte[] convertAsciiResponseToBin(byte[] msg, int len) {
            int l = (len - 5) / 2;
            byte[] ab = new byte[l];
            char[] ac = new char[2];
            for (int i = 0; i < l; i++) {
                ac[0] = (char) msg[i * 2 + 1];
                ac[1] = (char) msg[i * 2 + 2];
                ab[i] = (byte) Integer.parseInt(new String(ac), 16);
            }
            return ab;
        }

        /**
         * @return the payload to hand back to the caller, or null when the frame is
         *         incomplete or invalid and another read is required.
         */
        byte[] extractPayload() throws ModbusProtocolException {
            // Check first for an Exception response
            if ((this.data[1] & 0x80) == 0x80) {
                checkExceptionResponse();
                return null;
            }
            // then check for a valid message
            return extractValidPayload();
        }

        private void checkExceptionResponse() throws ModbusProtocolException {
            if (this.ascii || Crc16.getCrc16(this.data, RTU_MINIMUM_LENGTH, 0xffff) == 0) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        "Exception response = " + Byte.toString(this.data[2]));
            }
        }

        private byte[] extractValidPayload() {
            byte function = this.data[1];
            if (isWriteEcho(function)) {
                return extractEcho();
            }
            if (isDataRead(function)) {
                return extractRegisters();
            }
            return null;
        }

        private byte[] extractEcho() {
            if (this.length < ECHO_FRAME_LENGTH) {
                // wait for more data
                this.minimumLength = ECHO_FRAME_LENGTH;
                return null;
            }
            if (this.ascii || Crc16.getCrc16(this.data, ECHO_FRAME_LENGTH, 0xffff) == 0) {
                return Arrays.copyOf(this.data, 6);
            }
            return null;
        }

        private byte[] extractRegisters() {
            int byteCnt = this.ascii ? (this.data[2] & 0xff) + 3 : (this.data[2] & 0xff) + 5;
            if (this.length < byteCnt) {
                // wait for more data
                this.minimumLength = byteCnt;
                return null;
            }
            if (this.ascii || Crc16.getCrc16(this.data, byteCnt, 0xffff) == 0) {
                return Arrays.copyOf(this.data, byteCnt);
            }
            return null;
        }

        void dropFirstByte() {
            if (this.length >= this.minimumLength) {
                this.length--;
                System.arraycopy(this.data, 1, this.data, 0, this.length);
                this.minimumLength = RTU_MINIMUM_LENGTH; // reset minimum length
            }
        }
    }

    /**
     * Installation of an ethernet connection to communicate
     */
    private final class EthernetCommunicate extends Communicate {

        private static final AtomicInteger TRANSACTION_INDEX = new AtomicInteger();

        InputStream inputStream;
        OutputStream outputStream;
        Socket socket;
        int port;
        String ipAddress;
        String connType;
        boolean connected = false;

        public EthernetCommunicate(Properties connectionConfig) throws ModbusProtocolException {
            logger.debug("Configure TCP connection");
            String sPort;
            this.connType = connectionConfig.getProperty("connectionType");

            if ((sPort = connectionConfig.getProperty("ethport")) == null
                    || (this.ipAddress = connectionConfig.getProperty("ipAddress")) == null) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_CONFIGURATION);
            }
            this.port = Integer.parseInt(sPort);
            this.socket = new Socket();
        }

        private boolean isModbusTcp() {
            return PROTOCOL_CONNECTION_TYPE_ETHER_TCP.equals(this.connType);
        }

        @Override
        public void connect() {
            if (!ModbusProtocolDevice.this.connConfigd) {
                logger.error("Can't connect, port not configured");
                return;
            }
            if (this.connected) {
                return;
            }
            try {
                this.socket = new Socket();
                this.socket.connect(new InetSocketAddress(this.ipAddress, this.port),
                        ModbusProtocolDevice.this.respTout);
                openStreams();
            } catch (IOException e) {
                this.socket = null;
                logger.error("Failed to connect to remote", e);
            }
        }

        private void openStreams() {
            try {
                this.inputStream = this.socket.getInputStream();
                this.outputStream = this.socket.getOutputStream();
                this.connected = true;
                logger.info("TCP connected");
            } catch (IOException e) {
                disconnect();
                logger.error("Failed to get socket streams", e);
            }
        }

        @Override
        public void disconnect() {
            if (this.socket == null) {
                return;
            }
            if (ModbusProtocolDevice.this.connConfigd && this.connected) {
                try {
                    if (!this.socket.isInputShutdown()) {
                        this.socket.shutdownInput();
                    }
                    if (!this.socket.isOutputShutdown()) {
                        this.socket.shutdownOutput();
                    }
                    this.socket.close();
                } catch (IOException eClose) {
                    logger.error("Error closing TCP", eClose);
                }
                this.inputStream = null;
                this.outputStream = null;
                this.connected = false;
                this.socket = null;
            }
        }

        @Override
        public int getConnectStatus() {
            if (this.connected) {
                return KuraConnectionStatus.CONNECTED;
            } else if (ModbusProtocolDevice.this.connConfigd) {
                return KuraConnectionStatus.DISCONNECTED;
            } else {
                return KuraConnectionStatus.NEVERCONNECTED;
            }
        }

        @Override
        public byte[] msgTransaction(byte[] msg) throws ModbusProtocolException {
            byte[] cmd = buildCommand(msg);

            // Check connection status and connect
            connect();
            if (!this.connected) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        "Cannot transact on closed socket");
            }

            sendCommand(cmd);

            return receiveResponse(msg).extractPayload();
        }

        private byte[] buildCommand(byte[] msg) throws ModbusProtocolException {
            if (isAsciiMode()) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.METHOD_NOT_SUPPORTED,
                        "Only RTU over TCP/IP supported");
            }
            if (isModbusTcp()) {
                return buildModbusTcpCommand(msg);
            }
            return appendCrc(msg);
        }

        private byte[] buildModbusTcpCommand(byte[] msg) {
            byte[] cmd = new byte[msg.length + MBAP_HEADER_LENGTH];
            // build MBAP header
            int index = getNextTransactionIndex();
            cmd[0] = (byte) (index >> 8);
            cmd[1] = (byte) index;
            cmd[2] = 0;
            cmd[3] = 0;
            // length
            int len = msg.length;
            cmd[4] = (byte) (len >> 8);
            cmd[5] = (byte) len;
            System.arraycopy(msg, 0, cmd, MBAP_HEADER_LENGTH, msg.length);
            // No crc in Modbus TCP
            return cmd;
        }

        private void sendCommand(byte[] cmd) throws ModbusProtocolException {
            try {
                // flush input
                while (this.inputStream.available() > 0) {
                    this.inputStream.read();
                }
                // send all data
                this.outputStream.write(cmd, 0, cmd.length);
                this.outputStream.flush();
            } catch (IOException e) {
                // Assume this means the socket is closed...make sure it is
                logger.error("Socket disconnect in send", e);
                disconnect();
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        "Send failure: " + e.getMessage());
            }
        }

        private TcpResponse receiveResponse(byte[] msg) throws ModbusProtocolException {
            TcpResponse response = new TcpResponse(msg, isModbusTcp());

            boolean endFrame = false;
            while (!endFrame) {
                endFrame = response.append(readNextByte(response.buffer(), response.size()));
            }
            return response;
        }

        private int readNextByte(byte[] buffer, int offset) throws ModbusProtocolException {
            try {
                this.socket.setSoTimeout(ModbusProtocolDevice.this.respTout);
                int count = this.inputStream.read(buffer, offset, 1);
                if (count <= 0) {
                    logger.error("Socket disconnect in recv");
                    throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Recv failure");
                }
                return count;
            } catch (SocketTimeoutException e) {
                logger.warn("Recv timeout");
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Recv timeout");
            } catch (IOException e) {
                logger.error("Socket disconnect in recv", e);
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Recv failure");
            }
        }

        /**
         * Calculates and returns the next transaction index for Modbus TCP.
         *
         * @return the next transaction index.
         */
        private static int getNextTransactionIndex() {
            return TRANSACTION_INDEX.updateAndGet(index -> index >= 0xffff ? 0 : index + 1);
        }
    }

    /**
     * Assembles and validates the response frame of a single Modbus TCP transaction.
     */
    private static final class TcpResponse {

        private final byte[] data = new byte[MAX_RESPONSE_LENGTH];
        private final byte[] request;
        private final boolean modbusTcp;

        private int length = 0;
        private int minimumLength;

        TcpResponse(byte[] request, boolean modbusTcp) {
            this.request = request;
            this.modbusTcp = modbusTcp;
            this.minimumLength = modbusTcp ? RTU_MINIMUM_LENGTH + MBAP_HEADER_LENGTH : RTU_MINIMUM_LENGTH;
        }

        byte[] buffer() {
            return this.data;
        }

        int size() {
            return this.length;
        }

        /**
         * Accounts for the bytes just read and validates the frame as it grows.
         *
         * @return true once the whole frame has been received.
         */
        boolean append(int count) throws ModbusProtocolException {
            this.length += count;
            if (!this.modbusTcp) {
                return false;
            }
            if (this.length == MBAP_HEADER_LENGTH + 1) {
                checkUnitId();
            } else if (this.length == MBAP_HEADER_LENGTH + 2) {
                checkFunctionCode();
            } else if (this.length == MBAP_HEADER_LENGTH + 3) {
                this.minimumLength = expectedLength();
            } else if (this.length == this.minimumLength) {
                return true;
            }
            return false;
        }

        private void checkUnitId() throws ModbusProtocolException {
            // test modbus id
            if (this.data[MBAP_HEADER_LENGTH] != this.request[0]) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        String.format("incorrect modbus id %02X", this.data[MBAP_HEADER_LENGTH]));
            }
        }

        private void checkFunctionCode() throws ModbusProtocolException {
            // test function number
            if ((this.data[MBAP_HEADER_LENGTH + 1] & 0x7f) != this.request[1]) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        String.format("incorrect function number %02X", this.data[MBAP_HEADER_LENGTH + 1]));
            }
        }

        private int expectedLength() throws ModbusProtocolException {
            byte function = this.data[MBAP_HEADER_LENGTH + 1];
            // Check first for an Exception response
            if ((function & 0x80) == 0x80) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                        String.format("Modbus responds an error = %02X", this.data[MBAP_HEADER_LENGTH + 2]));
            }
            if (isWriteEcho(function)) {
                return MBAP_HEADER_LENGTH + 6;
            }
            // bytes count
            return (this.data[MBAP_HEADER_LENGTH + 2] & 0xff) + MBAP_HEADER_LENGTH + 3;
        }

        byte[] extractPayload() {
            // then check for a valid message
            byte function = this.data[MBAP_HEADER_LENGTH + 1];
            if (isWriteEcho(function)) {
                byte[] echo = new byte[ECHO_FRAME_LENGTH];
                System.arraycopy(this.data, MBAP_HEADER_LENGTH, echo, 0, 6);
                return echo;
            }
            if (isDataRead(function)) {
                int byteCnt = (this.data[MBAP_HEADER_LENGTH + 2] & 0xff) + 3 + MBAP_HEADER_LENGTH;
                return Arrays.copyOfRange(this.data, MBAP_HEADER_LENGTH, byteCnt);
            }
            return new byte[0];
        }
    }

    private void checkConnected() throws ModbusProtocolException {
        if (!this.connConfigd) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.NOT_CONNECTED);
        }
    }

    @Override
    public boolean[] readCoils(int unitAddr, int dataAddress, int count) throws ModbusProtocolException {
        return readBooleans(unitAddr, dataAddress, count, (byte) ModbusFunctionCodes.READ_COIL_STATUS);
    }

    @Override
    public boolean[] readDiscreteInputs(int unitAddr, int dataAddress, int count) throws ModbusProtocolException {
        return readBooleans(unitAddr, dataAddress, count, (byte) ModbusFunctionCodes.READ_INPUT_STATUS);
    }

    private boolean[] readBooleans(int unitAddr, int dataAddress, int count, byte functionCode)
            throws ModbusProtocolException {
        checkConnected();

        /*
         * construct the command issue and get results
         */
        byte[] cmd = new byte[6];
        cmd[0] = (byte) unitAddr;
        cmd[1] = functionCode;
        cmd[2] = (byte) (dataAddress / 256);
        cmd[3] = (byte) (dataAddress % 256);
        cmd[4] = (byte) (count / 256);
        cmd[5] = (byte) (count % 256);

        /*
         * send the message and get the response
         */
        byte[] resp = this.comm.msgTransaction(cmd);

        /*
         * process the response (address & CRC already confirmed)
         */
        if (resp.length < 3 || resp.length < (resp[2] & 0xff) + 3) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }
        if ((resp[2] & 0xff) != (count + 7) / 8) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS);
        }

        return unpackBooleans(resp, 3, count);
    }

    /**
     * Unpacks count bit values, least significant bit first, starting at the given
     * offset of the response.
     */
    private static boolean[] unpackBooleans(byte[] resp, int offset, int count) {
        boolean[] ret = new boolean[count];

        byte mask = 1;
        int byteOffset = offset;
        for (int index = 0; index < count; index++) {
            // get this point's value
            ret[index] = (resp[byteOffset] & mask) == mask;
            // advance the mask and offset index
            mask = (byte) (mask << 1);
            if (mask == 0) {
                mask = 1;
                byteOffset++;
            }
        }

        return ret;
    }

    @Override
    public void writeSingleCoil(int unitAddr, int dataAddress, boolean data) throws ModbusProtocolException {
        writeSingle(unitAddr, (byte) ModbusFunctionCodes.FORCE_SINGLE_COIL, dataAddress,
                data ? (byte) 0xff : (byte) 0, (byte) 0);
    }

    @Override
    public void writeSingleRegister(int unitAddr, int dataAddress, int data) throws ModbusProtocolException {
        writeSingle(unitAddr, (byte) ModbusFunctionCodes.PRESET_SINGLE_REG, dataAddress, (byte) (data >> 8),
                (byte) data);
    }

    private void writeSingle(int unitAddr, byte functionCode, int dataAddress, byte high, byte low)
            throws ModbusProtocolException {
        checkConnected();

        byte[] cmd = new byte[6];
        cmd[0] = (byte) unitAddr;
        cmd[1] = functionCode;
        cmd[2] = (byte) (dataAddress / 256);
        cmd[3] = (byte) (dataAddress % 256);
        cmd[4] = high;
        cmd[5] = low;

        /*
         * send the message and get the response
         */
        byte[] resp = this.comm.msgTransaction(cmd);

        /*
         * process the response
         */
        verifyEcho(cmd, resp);
    }

    @Override
    public void writeMultipleCoils(int unitAddr, int dataAddress, boolean[] data) throws ModbusProtocolException {
        checkConnected();

        /*
         * write multiple boolean values
         */
        int localCnt = data.length;
        /*
         * construct the command, issue and verify response
         */
        int dataLength = (localCnt + 7) / 8;
        byte[] cmd = buildMultipleWriteCommand(unitAddr, (byte) ModbusFunctionCodes.FORCE_MULTIPLE_COILS, dataAddress,
                localCnt, dataLength);

        // put the data on the command
        byte mask = 1;
        int byteOffset = 7;
        cmd[byteOffset] = 0;
        for (int index = 0; index < localCnt; index++) {
            // get this point's value
            if (data[index]) {
                cmd[byteOffset] += mask;
            }
            // advance the mask and offset index
            mask = (byte) (mask << 1);
            if (mask == 0) {
                mask = 1;
                byteOffset++;
                if (byteOffset < cmd.length) {
                    cmd[byteOffset] = 0;
                }
            }
        }

        /*
         * send the message and get the response
         */
        byte[] resp = this.comm.msgTransaction(cmd);

        /*
         * process the response
         */
        verifyEcho(cmd, resp);
    }

    @Override
    public void writeMultipleRegister(int unitAddr, int dataAddress, int[] data) throws ModbusProtocolException {
        checkConnected();

        int localCnt = data.length;
        /*
         * construct the command, issue and verify response
         */
        int dataLength = localCnt * 2;
        byte[] cmd = buildMultipleWriteCommand(unitAddr, (byte) ModbusFunctionCodes.PRESET_MULTIPLE_REGS, dataAddress,
                localCnt, dataLength);

        // put the data on the command
        int byteOffset = 7;
        for (int index = 0; index < localCnt; index++) {
            cmd[byteOffset] = (byte) (data[index] >> 8);
            cmd[byteOffset + 1] = (byte) data[index];

            byteOffset += 2;
        }

        /*
         * send the message and get the response
         */
        byte[] resp = this.comm.msgTransaction(cmd);

        /*
         * process the response
         */
        verifyEcho(cmd, resp);
    }

    private static byte[] buildMultipleWriteCommand(int unitAddr, byte functionCode, int dataAddress, int count,
            int dataLength) {
        byte[] cmd = new byte[dataLength + 7];
        cmd[0] = (byte) unitAddr;
        cmd[1] = functionCode;
        cmd[2] = (byte) (dataAddress / 256);
        cmd[3] = (byte) (dataAddress % 256);
        cmd[4] = (byte) (count / 256);
        cmd[5] = (byte) (count % 256);
        cmd[6] = (byte) dataLength;
        return cmd;
    }

    /**
     * Verifies that the device echoed back the header of the write command.
     */
    private static void verifyEcho(byte[] cmd, byte[] resp) throws ModbusProtocolException {
        if (resp.length < 6) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }
        for (int i = 0; i < 6; i++) {
            if (cmd[i] != resp[i]) {
                throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
            }
        }
    }

    @Override
    public int[] readHoldingRegisters(int unitAddr, int dataAddress, int count) throws ModbusProtocolException {
        return readRegisters(unitAddr, dataAddress, count, (byte) ModbusFunctionCodes.READ_HOLDING_REGS);
    }

    @Override
    public int[] readInputRegisters(int unitAddr, int dataAddress, int count) throws ModbusProtocolException {
        return readRegisters(unitAddr, dataAddress, count, (byte) ModbusFunctionCodes.READ_INPUT_REGS);
    }

    private int[] readRegisters(int unitAddr, int dataAddress, int count, byte functionCode)
            throws ModbusProtocolException {
        checkConnected();

        /*
         * construct the command issue and get results, putting the results
         * away at index and then incrementing index for the next command
         */
        byte[] cmd = new byte[6];
        cmd[0] = (byte) unitAddr;
        cmd[1] = functionCode;
        cmd[2] = (byte) (dataAddress / 256);
        cmd[3] = (byte) (dataAddress % 256);
        cmd[4] = 0;
        cmd[5] = (byte) count;

        /*
         * send the message and get the response
         */
        byte[] resp = this.comm.msgTransaction(cmd);

        /*
         * process the response (address & CRC already confirmed)
         */
        if (resp.length < 3 || resp.length < (resp[2] & 0xff) + 3) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }
        if ((resp[2] & 0xff) != count * 2) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_ADDRESS);
        }

        int[] ret = new int[count];
        int byteOffset = 3;
        for (int index = 0; index < count; index++) {
            ret[index] = toWord(resp, byteOffset);
            byteOffset += 2;
        }
        return ret;
    }

    /**
     * Reads a big endian 16 bit word at the given offset.
     */
    private static int toWord(byte[] data, int offset) {
        int val = data[offset] & 0xff;
        val <<= 8;
        val += data[offset + 1] & 0xff;
        return val;
    }

    @Override
    public boolean[] readExceptionStatus(int unitAddr) throws ModbusProtocolException {
        byte[] resp = readStatus(unitAddr, (byte) ModbusFunctionCodes.READ_EXCEPTION_STATUS);

        /*
         * process the response (address & CRC already confirmed)
         */
        if (resp.length < 3) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }

        return unpackBooleans(resp, 2, 8);
    }

    @Override
    public ModbusCommEvent getCommEventCounter(int unitAddr) throws ModbusProtocolException {
        byte[] resp = readStatus(unitAddr, (byte) ModbusFunctionCodes.GET_COMM_EVENT_COUNTER);

        /*
         * process the response (address & CRC already confirmed)
         */
        if (resp.length < 6) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }

        ModbusCommEvent mce = new ModbusCommEvent();
        mce.setStatus(toWord(resp, 2));
        mce.setEventCount(toWord(resp, 4));
        return mce;
    }

    @Override
    public ModbusCommEvent getCommEventLog(int unitAddr) throws ModbusProtocolException {
        byte[] resp = readStatus(unitAddr, (byte) ModbusFunctionCodes.GET_COMM_EVENT_LOG);

        /*
         * process the response (address & CRC already confirmed)
         */
        if (resp.length < 3 || resp.length < (resp[2] & 0xff) + 3 || (resp[2] & 0xff) > 64 + 7) {
            throw new ModbusProtocolException(ModbusProtocolErrorCode.INVALID_DATA_TYPE);
        }

        ModbusCommEvent mce = new ModbusCommEvent();
        mce.setStatus(toWord(resp, 3));
        mce.setEventCount(toWord(resp, 5));
        mce.setMessageCount(toWord(resp, 7));

        int count = (resp[2] & 0xff) - 4;
        int[] events = new int[count];
        for (int j = 0; j < count; j++) {
            events[j] = resp[9 + j] & 0xff;
        }
        mce.setEvents(events);

        return mce;
    }

    /**
     * Issues a command that carries no data beyond the unit address and the
     * function code.
     */
    private byte[] readStatus(int unitAddr, byte functionCode) throws ModbusProtocolException {
        checkConnected();

        /*
         * construct the command issue and get results
         */
        byte[] cmd = new byte[2];
        cmd[0] = (byte) unitAddr;
        cmd[1] = functionCode;

        /*
         * send the message and get the response
         */
        return this.comm.msgTransaction(cmd);
    }

}
