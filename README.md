# kura-modbus

Eclipse Kura™ Modbus addon.

The addon provides the **`ModbusProtocolDeviceService`**, a Modbus master
implementation that talks to Modbus slaves over a serial line (through the Kura
`CommConnection`), over Modbus TCP, or with RTU framing tunnelled over TCP. It
exposes the usual Modbus primitives — read and write of coils, discrete inputs,
input registers and holding registers — to any other bundle.

The bundle used to live in the [Eclipse Kura](https://github.com/eclipse-kura/kura)
monorepo (`kura/org.eclipse.kura.protocol.modbus`) and used to be shipped inside
the Kura core deployment package. It now lives here and is released as a
standalone Debian package.

## Contents

| Module | Artifact | Description |
|---|---|---|
| `org.eclipse.kura.protocol.modbus` | bundle | the Modbus master. Exports `org.eclipse.kura.protocol.modbus` version 1.0.1 |
| `bom` | `kura-modbus-bom` | the bundles released by this project |
| `distrib` | `kura-modbus-distrib` | Debian packaging (`jdeb`) |
| `tests` | `kura-modbus-tests` | unit tests and OSGi integration tests |

The build is **Maven + [bnd](https://bnd.bndtools.org/)** targeting Java 21 —
there is no Tycho and no target definition. The project was bootstrapped with
[`kura-archetype`](https://github.com/eclipse-kura/kura-archetype).

The Declarative Services descriptor is hand-written and kept in source control
under `org.eclipse.kura.protocol.modbus/OSGI-INF/`.

### The exported package version

The bundle exports `org.eclipse.kura.protocol.modbus` at version **1.0.1**,
independently of the bundle version. That version is the contract consumers
import, so it is pinned explicitly in the bnd instructions of the bundle
`pom.xml` — it must not be allowed to follow the bundle version.

## Prerequisites

| | |
|---|---|
| **JDK 21** | the project sets `maven.compiler.release=21` |
| **Maven 3.9.x** | |
| **git** | `git-commit-id-maven-plugin` stamps the commit hash into the snapshot Debian version |

## Building

```bash
mvn clean install
```

Add `-Presolve-integration-tests` whenever `-runrequires` or the bundle imports
change: the profile runs `bnd-resolver-maven-plugin:resolve`, which recomputes
the `-runbundles` list of
`tests/org.eclipse.kura.protocol.modbus.test/integration-test.bndrun`. Commit
the resolved `.bndrun`.

```bash
mvn clean install -Presolve-integration-tests
```

### Tests

Both test kinds run as part of `mvn verify`/`mvn install`:

- **unit tests** — `maven-surefire-plugin`, from
  `tests/org.eclipse.kura.protocol.modbus.test/src/test/java`. They start an
  in-process Modbus TCP server on a local port and drive the master against it,
  so neither hardware nor an external slave is needed;
- **OSGi integration test** — `bnd-testing-maven-plugin`, from
  `.../src/main/java` (it is part of the test bundle). It starts an embedded
  Kura framework and checks that the `ModbusProtocolDeviceService` is published.
  The component has mandatory references to the OSGi `ConnectionFactory`
  (provided by `org.eclipse.kura.core.comm`) and to the Kura `UsbService`, so
  the service only appears once both are wired.

Reports land in `tests/org.eclipse.kura.protocol.modbus.test/target/surefire-reports/`
(unit) and `.../surefire-reports/integration-test/` (OSGi); JaCoCo writes to
`.../target/site/jacoco-aggregate/`.

## Debian package

`jdeb` is bound to the `package` phase, so every `mvn package`/`install`
produces `distrib/target/deb/kura-modbus_<version>-<revision>_all.deb`.

| Build | Version | Command |
|---|---|---|
| development (default) | `3.0.0~git202608170900.5aba853-1` | `mvn clean install` |
| release | `3.0.0-1` | `mvn clean install -DreleaseBuild` |

`-DreleaseBuild` also activates the enforcer rule that fails the build if the
project version is still a `-SNAPSHOT`.

The package depends on `kura-core (>= 6.0.0~), kura-core (<< 7.0.0~)` and
installs `org.eclipse.kura.protocol.modbus` in `/opt/eclipse/kura/plugins/6s/`.
Everything the bundle imports is already part of the Kura runtime — the Kura
API, `org.eclipse.equinox.io` for `org.osgi.service.io` and
`org.eclipse.kura.core.comm` for the serial `ConnectionFactory` — so the package
ships a single jar.

Install it on a device and restart Kura:

```bash
apt install ./kura-modbus_<version>_all.deb
systemctl restart kura
```

## Using the service

The bundle registers a single `ModbusProtocolDeviceService`; take it from the
service registry and configure the connection before use:

```java
Properties config = new Properties();
config.setProperty("connectionType", ModbusProtocolDevice.PROTOCOL_CONNECTION_TYPE_ETHER_TCP);
config.setProperty("ipAddress", "192.168.1.50");
config.setProperty("ethport", "502");
config.setProperty("respTimeout", "10000");
config.setProperty("transmissionMode", ModbusTransmissionMode.RTU);

modbusService.configureConnection(config);
modbusService.connect();

boolean[] coils = modbusService.readCoils(1, 0, 8);
```

`connectionType` selects the link:

| Constant | Value | Link |
|---|---|---|
| `PROTOCOL_CONNECTION_TYPE_SERIAL` | `RS232` | serial line; needs `port`, `baudRate`, `bitsPerWord`, `stopBits`, `parity` |
| `PROTOCOL_CONNECTION_TYPE_ETHER_TCP` | `TCP/IP` | Modbus TCP; needs `ipAddress` and `ethport` |
| `PROTOCOL_CONNECTION_TYPE_ETHER_RTU` | `TCP-RTU` | RTU framing over TCP; needs `ipAddress` and `ethport` |

See the javadoc of `ModbusProtocolDeviceService.configureConnection` for the
full list of properties of each mode.

## Contributing

See the [Kura contribution guide](https://github.com/eclipse-kura/kura/blob/develop/CONTRIBUTING.md).
Pull request titles must follow the
[Conventional Commits](https://www.conventionalcommits.org/) format, and signing
the [Eclipse Contributor Agreement](https://www.eclipse.org/legal/ECA.php) is
required.

## License

[Eclipse Public License 2.0](https://www.eclipse.org/legal/epl-2.0/)
