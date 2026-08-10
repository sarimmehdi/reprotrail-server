# Repository guidance

Follow the project-wide contribution rules in the
[ReproTrail community repository](https://github.com/sarimmehdi/.github/blob/main/CONTRIBUTING.md).

This service follows test-driven development. Run `./gradlew check` before every commit. Keep protocol validation pinned to a released `reprotrail-spec` artifact, never a moving branch.

Trace bodies and credentials are sensitive. Never log bearer tokens, raw traces, request bodies, download contents, or storage credentials. PostgreSQL and object-storage adapter changes require integration tests against real compatible services.
