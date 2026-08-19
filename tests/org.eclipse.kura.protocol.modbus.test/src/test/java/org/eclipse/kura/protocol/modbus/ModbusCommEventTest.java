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
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ModbusCommEventTest {

    @Test
    public void shouldStartOutEmpty() {
        whenACommEventIsCreated();

        thenStatusIs(0);
        thenEventCountIs(0);
        thenMessageCountIs(0);
        thenNoEventIsRecorded();
    }

    @Test
    public void shouldRetainTheAssignedValues() {
        givenACommEvent();

        whenStatusIsSetTo(0xffff);
        whenEventCountIsSetTo(12);
        whenMessageCountIsSetTo(34);
        whenEventsAreSetTo(1, 2, 3);

        thenStatusIs(0xffff);
        thenEventCountIs(12);
        thenMessageCountIs(34);
        thenRecordedEventsAre(1, 2, 3);
    }

    private ModbusCommEvent event;

    private void givenACommEvent() {
        this.event = new ModbusCommEvent();
    }

    private void whenACommEventIsCreated() {
        this.event = new ModbusCommEvent();
    }

    private void whenStatusIsSetTo(int status) {
        this.event.setStatus(status);
    }

    private void whenEventCountIsSetTo(int eventCount) {
        this.event.setEventCount(eventCount);
    }

    private void whenMessageCountIsSetTo(int messageCount) {
        this.event.setMessageCount(messageCount);
    }

    private void whenEventsAreSetTo(int... events) {
        this.event.setEvents(events);
    }

    private void thenStatusIs(int expected) {
        assertEquals(expected, this.event.getStatus());
    }

    private void thenEventCountIs(int expected) {
        assertEquals(expected, this.event.getEventCount());
    }

    private void thenMessageCountIs(int expected) {
        assertEquals(expected, this.event.getMessageCount());
    }

    private void thenNoEventIsRecorded() {
        assertNull(this.event.getEvents());
    }

    private void thenRecordedEventsAre(int... expected) {
        assertArrayEquals(expected, this.event.getEvents());
    }
}
