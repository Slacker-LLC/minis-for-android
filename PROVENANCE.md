# Source Provenance

This document records source lineage and attribution for Minis for Android. It is a provenance/legal record, not an active runtime specification or an ongoing synchronization policy.

## Project lineage

Minis for Android contains substantial code derived from the open-source **OpenMinis** project:

- source repository: https://github.com/OpenMinis/OpenMinis
- repository identifier: `OpenMinis/OpenMinis`
- upstream license at the time of derivation: GPL-3.0
- project website: https://openminis.app

The current repository is independently maintained by Slacker-LLC and is not an official OpenMinis distribution. Its present product architecture, runtime contracts, release policy, and development decisions are defined by this repository's current source code, tests, and active documentation.

There is no standing policy that changes from OpenMinis are continuously synchronized into this repository. If code is later imported or adapted from any external project, the importing change must identify its source, preserve applicable notices, satisfy the relevant license, and be adapted to the current Minis for Android architecture.

## Copyright and license obligations

Minis for Android remains distributed under GPL-3.0. Derived source does not lose its original copyright or license obligations when architecture, packaging, platform scope, or runtime implementation changes.

When redistributing modified binaries, provide the corresponding source as required by GPL-3.0 and preserve applicable copyright and license notices.

See [LICENSE](LICENSE), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), and [CONTRIBUTORS.md](CONTRIBUTORS.md).

## Historical architecture

Earlier development inherited or experimented with runtime ideas and components associated with OpenMinis, including Alpine Linux and PRoot-based execution. Those details are historical context, not current architecture.

The retained history note is [docs/archive/RUNTIME-HISTORY.md](docs/archive/RUNTIME-HISTORY.md). Current runtime behavior is documented in [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md).
