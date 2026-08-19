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
import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

public class ModbusProtocolExceptionTest {

    @Test
    public void shouldReportTheCode() {
        whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode.NOT_CONNECTED);

        thenTheCodeIs(ModbusProtocolErrorCode.NOT_CONNECTED);
        thenThereIsNoCause();
    }

    @Test
    public void shouldNameTheCodeInTheMessage() {
        whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode.INVALID_CONFIGURATION);

        thenTheMessageMentions("INVALID_CONFIGURATION");
    }

    @Test
    public void shouldAppendTheComplementToTheMessage() {
        whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode.TRANSACTION_FAILURE, "Bad LRC");

        thenTheCodeIs(ModbusProtocolErrorCode.TRANSACTION_FAILURE);
        thenTheMessageEndsWith("Bad LRC");
    }

    @Test
    public void shouldRetainTheCause() {
        givenACause("boom");

        whenAnExceptionIsBuiltWithTheCause(ModbusProtocolErrorCode.CONNECTION_FAILURE, "first", "second");

        thenTheCodeIs(ModbusProtocolErrorCode.CONNECTION_FAILURE);
        thenTheCauseIsReported();
        thenTheMessageMentions("CONNECTION_FAILURE");
    }

    @Test
    public void shouldLocalizeUsingTheDefaultLocale() {
        whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode.RESPONSE_TIMEOUT);

        thenTheLocalizedMessageMentions("RESPONSE_TIMEOUT");
    }

    @Test
    public void shouldNameEveryErrorCodeInItsOwnMessage() {
        whenAnExceptionIsBuiltForEveryErrorCode();

        thenEveryMessageMentionsItsOwnCode();
    }

    private ModbusProtocolException exception;
    private IOException cause;
    private final Map<ModbusProtocolErrorCode, String> messages = new EnumMap<>(ModbusProtocolErrorCode.class);

    private void givenACause(String message) {
        this.cause = new IOException(message);
    }

    private void whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode code) {
        this.exception = new ModbusProtocolException(code);
    }

    private void whenAnExceptionIsBuiltWith(ModbusProtocolErrorCode code, String complement) {
        this.exception = new ModbusProtocolException(code, complement);
    }

    private void whenAnExceptionIsBuiltWithTheCause(ModbusProtocolErrorCode code, Object... arguments) {
        this.exception = new ModbusProtocolException(code, this.cause, arguments);
    }

    private void whenAnExceptionIsBuiltForEveryErrorCode() {
        for (ModbusProtocolErrorCode code : ModbusProtocolErrorCode.values()) {
            this.messages.put(code, new ModbusProtocolException(code).getMessage());
        }
    }

    private void thenTheCodeIs(ModbusProtocolErrorCode expected) {
        assertEquals(expected, this.exception.getCode());
    }

    private void thenThereIsNoCause() {
        assertNull(this.exception.getCause());
    }

    private void thenTheCauseIsReported() {
        assertSame(this.cause, this.exception.getCause());
    }

    private void thenTheMessageMentions(String expected) {
        assertTrue(this.exception.getMessage().contains(expected));
    }

    private void thenTheMessageEndsWith(String expected) {
        assertTrue(this.exception.getMessage().endsWith(expected));
    }

    private void thenTheLocalizedMessageMentions(String expected) {
        assertTrue(this.exception.getLocalizedMessage().contains(expected));
    }

    private void thenEveryMessageMentionsItsOwnCode() {
        assertEquals(ModbusProtocolErrorCode.values().length, this.messages.size());
        for (Map.Entry<ModbusProtocolErrorCode, String> entry : this.messages.entrySet()) {
            assertTrue(entry.getValue().contains(entry.getKey().name()));
        }
    }
}
