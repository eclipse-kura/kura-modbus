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
 *******************************************************************************/
package org.eclipse.kura.protocol.modbus.integration;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.kura.protocol.modbus.ModbusProtocolDeviceService;
import org.junit.AfterClass;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feature: the Modbus bundle resolves and activates in a real Kura framework.
 * The component has mandatory references to the OSGi {@code ConnectionFactory}
 * and to the Kura {@code UsbService}, so the service only appears once both are
 * wired: seeing it published is what proves the bundle works once installed by
 * the Debian package.
 *
 * The service is looked up through the {@link BundleContext} instead of being
 * injected with {@code @Reference}: the Kura bundles carry no
 * {@code osgi.service} capability, so a Declarative Services reference to them
 * would make the bnd resolution of this bndrun fail.
 */
@Component(immediate = true)
public class ModbusProtocolDeviceServiceItTest {

    @Test
    public void shouldPublishTheModbusProtocolDeviceService() {
        givenAnActivatedTestComponent();

        whenTheProtocolDeviceServiceIsLookedUp();

        thenTheServiceIsPublished();
    }

    private static final Logger logger = LoggerFactory.getLogger(ModbusProtocolDeviceServiceItTest.class);

    private static final long TIMEOUT_SECONDS = 60;

    private static final CountDownLatch activated = new CountDownLatch(1);

    // needs to be static for being available to JUnit Runner
    private static BundleContext bundleContext;

    private ModbusProtocolDeviceService modbusService;

    @Activate
    public void activate(BundleContext context) {
        bundleContext = context;
        activated.countDown();
    }

    @AfterClass
    public static void cleanup() {
        logger.info("Shutting down OSGi framework...");
        if (bundleContext != null) {
            try {
                Bundle systemBundle = bundleContext.getBundle(0);
                systemBundle.stop();
            } catch (BundleException e) {
                logger.error("Error stopping framework", e);
            }
        }
    }

    private void givenAnActivatedTestComponent() {
        try {
            if (!activated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("test component not activated in " + TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the test component");
        }
    }

    private void whenTheProtocolDeviceServiceIsLookedUp() {
        this.modbusService = awaitService(ModbusProtocolDeviceService.class);
    }

    private void thenTheServiceIsPublished() {
        assertNotNull(this.modbusService);
    }

    private static <T> T awaitService(final Class<T> clazz) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

        try {
            while (System.nanoTime() < deadline) {
                final Collection<ServiceReference<T>> references = bundleContext.getServiceReferences(clazz, null);
                if (!references.isEmpty()) {
                    return bundleContext.getService(references.iterator().next());
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the " + clazz.getSimpleName() + " service");
        } catch (Exception e) {
            fail("the " + clazz.getSimpleName() + " service could not be looked up: " + e.getMessage());
        }

        return null;
    }

}
