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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class ModbusProtocolExceptionTest {

    @Test
    public void shouldReportTheCode() {
        ModbusProtocolException exception = new ModbusProtocolException(ModbusProtocolErrorCode.NOT_CONNECTED);

        assertEquals(ModbusProtocolErrorCode.NOT_CONNECTED, exception.getCode());
        assertNull(exception.getCause());
    }

    @Test
    public void shouldNameTheCodeInTheMessage() {
        ModbusProtocolException exception = new ModbusProtocolException(
                ModbusProtocolErrorCode.INVALID_CONFIGURATION);

        assertTrue(exception.getMessage().contains("INVALID_CONFIGURATION"));
    }

    @Test
    public void shouldAppendTheComplementToTheMessage() {
        ModbusProtocolException exception = new ModbusProtocolException(ModbusProtocolErrorCode.TRANSACTION_FAILURE,
                "Bad LRC");

        assertEquals(ModbusProtocolErrorCode.TRANSACTION_FAILURE, exception.getCode());
        assertTrue(exception.getMessage().endsWith("Bad LRC"));
    }

    @Test
    public void shouldRetainTheCause() {
        IOException cause = new IOException("boom");

        ModbusProtocolException exception = new ModbusProtocolException(ModbusProtocolErrorCode.CONNECTION_FAILURE,
                cause, "first", "second");

        assertSame(cause, exception.getCause());
        assertEquals(ModbusProtocolErrorCode.CONNECTION_FAILURE, exception.getCode());
        assertTrue(exception.getMessage().contains("CONNECTION_FAILURE"));
    }

    @Test
    public void shouldLocalizeUsingTheDefaultLocale() {
        ModbusProtocolException exception = new ModbusProtocolException(ModbusProtocolErrorCode.RESPONSE_TIMEOUT);

        assertTrue(exception.getLocalizedMessage().contains("RESPONSE_TIMEOUT"));
    }

    @Test
    public void shouldDefineAMessageCodeForEveryErrorCode() {
        for (ModbusProtocolErrorCode code : ModbusProtocolErrorCode.values()) {
            ModbusProtocolException exception = new ModbusProtocolException(code);

            assertTrue(exception.getMessage().contains(code.name()));
            assertEquals(code, ModbusProtocolErrorCode.valueOf(code.name()));
        }
    }
}
