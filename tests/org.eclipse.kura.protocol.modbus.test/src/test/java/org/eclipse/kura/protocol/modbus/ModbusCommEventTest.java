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
    public void shouldStartEmpty() {
        ModbusCommEvent event = new ModbusCommEvent();

        assertEquals(0, event.getStatus());
        assertEquals(0, event.getEventCount());
        assertEquals(0, event.getMessageCount());
        assertNull(event.getEvents());
    }

    @Test
    public void shouldRetainTheAssignedValues() {
        ModbusCommEvent event = new ModbusCommEvent();

        event.setStatus(0xffff);
        event.setEventCount(12);
        event.setMessageCount(34);
        event.setEvents(new int[] { 1, 2, 3 });

        assertEquals(0xffff, event.getStatus());
        assertEquals(12, event.getEventCount());
        assertEquals(34, event.getMessageCount());
        assertArrayEquals(new int[] { 1, 2, 3 }, event.getEvents());
    }
}
