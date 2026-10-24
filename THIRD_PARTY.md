# Third-party components

## Scope and offline build

The build uses the JARs committed under `lib`; it does not resolve dependencies
from a package repository. See [COMPILATION.md](COMPILATION.md) for build commands
and [PROJET.md](PROJET.md) for architecture and qualification limits.

Inventory of bundled JARs (a bundled component is not necessarily used on every
execution path):

| Component | Bundled version | Role |
|---|---|---|
| Oracle JDBC | 21.1.0.0 (`ojdbc11`) | Oracle repository and target connections |
| PostgreSQL JDBC | 42.7.13 | PostgreSQL target connections |
| Microsoft JDBC for SQL Server | 13.4.0.jre11 | SQL Server target connections |
| Eclipse Jetty | 12.0.38 | Embedded HTTP server |
| SLF4J API and simple provider | 2.0.7 | Logging |
| HikariCP | 5.0.1 | Bundled connection-pool library |
| FlatLaf | 3.4.1 | Bundled desktop UI library |
| FlatLaf Extras | 3.2.2 | Bundled desktop UI support |
| XChart | 4.0.3 | Bundled charting library |

This inventory is not a license or vulnerability audit. Before redistributing
packages, verify the applicable licenses and notices for every bundled artifact.
Updating a dependency requires compatibility checks and refreshed artifact
references; the version numbers here do not imply that they are the latest.

## Eclipse Jetty 12.0.38

- Source: `https://jetty.org/download.html`
- Distribution: `jetty-home-12.0.38.zip`
- Distribution SHA-256: `017DD600C6A855EB8B053458F7676A68D2FF0E37AE767F7BF798264FFB24740B`
- Installed home: `runtime/jetty-home-12.0.38`
- Licenses: Eclipse Public License 2.0 and Apache License 2.0

Embedded runtime artifacts:

| Artifact | SHA-256 |
|---|---|
| `jetty-http-12.0.38.jar` | `67C39E71FE445A452ED12D18AD429B0CEAE2726204F20F69EDDDD290F2E454AD` |
| `jetty-io-12.0.38.jar` | `F121F9AABD6B06BA307CECB7A0AD8B140C97D56F482FC44A9BB76A1D3FC4D794` |
| `jetty-server-12.0.38.jar` | `03C8B9FE97556D4320456AAE450F4CC1DB5DA09A976ADB41A1EC6AE397AB4E38` |
| `jetty-util-12.0.38.jar` | `CB2DAE2045CCE0DEC4C5D3556504EB9848ABE9D716DB00AF22B7DBD63CA92D56` |

Jetty uses the SLF4J API and provider already shipped in `lib`.

## Database product logos

- Oracle wordmark: Oracle Corporation trademark, sourced from Wikimedia Commons.
- PostgreSQL elephant: PostgreSQL Community trademark, sourced from the PostgreSQL project logo resources.
- Microsoft SQL Server logo: Microsoft Corporation trademark, sourced from UXWing.

The logos identify supported database technologies. Their inclusion does not imply endorsement or partnership.
