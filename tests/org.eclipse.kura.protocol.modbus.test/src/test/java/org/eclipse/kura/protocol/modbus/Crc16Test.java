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

import org.junit.Test;

public class Crc16Test {

    private static final int SEED = 0x0ffff;

    @Test
    public void shouldComputeTheCrcOfAKnownReadHoldingRegistersRequest() {
        // 01 03 00 00 00 01 is answered with the CRC bytes 84 0A, low byte first
        byte[] request = { 0x01, 0x03, 0x00, 0x00, 0x00, 0x01 };

        assertEquals(0x0A84, Crc16.getCrc16(request, request.length, SEED));
    }

    @Test
    public void shouldComputeTheCrcOfAKnownReadCoilsRequest() {
        // 01 01 00 00 00 01 is answered with the CRC bytes FD CA, low byte first
        byte[] request = { 0x01, 0x01, 0x00, 0x00, 0x00, 0x01 };

        assertEquals(0xCAFD, Crc16.getCrc16(request, request.length, SEED));
    }

    @Test
    public void shouldYieldZeroOverAFrameThatCarriesItsOwnCrc() {
        byte[] request = { 0x01, 0x03, 0x00, 0x00, 0x00, 0x01 };
        int crc = Crc16.getCrc16(request, request.length, SEED);

        byte[] frame = new byte[request.length + 2];
        System.arraycopy(request, 0, frame, 0, request.length);
        frame[request.length] = (byte) crc;
        frame[request.length + 1] = (byte) (crc >> 8);

        assertEquals(0, Crc16.getCrc16(frame, frame.length, SEED));
    }

    @Test
    public void shouldReturnTheSeedForAnEmptyBuffer() {
        assertEquals(SEED, Crc16.getCrc16(new byte[0], 0, SEED));
    }

    @Test
    public void shouldOnlyConsiderTheRequestedNumberOfBytes() {
        byte[] shortBuffer = { 0x01, 0x03 };
        byte[] longBuffer = { 0x01, 0x03, 0x7f, 0x7f };

        assertEquals(Crc16.getCrc16(shortBuffer, 2, SEED), Crc16.getCrc16(longBuffer, 2, SEED));
    }
}
