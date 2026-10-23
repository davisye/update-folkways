# Third-party components

The companion includes an embedded standalone update helper. This helper packages Google Gson 2.10.1 under Apache License 2.0.
The game-side companion uses the Gson library supplied by the NeoForge runtime. Legacy standalone Bootstrap builds also include Gson.

- [Gson 2.10.1](https://github.com/google/gson/tree/gson-parent-2.10.1) — [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- [Gradle Wrapper 8.14.5](https://github.com/gradle/gradle) — Apache License 2.0.
- NeoForge 21.1.233 and FancyModLoader are build/runtime dependencies. This repository does not redistribute Minecraft or the NeoForge server distribution.

Folkways is a separate project. This updater does not bundle its runtime JARs; users obtain the modules from their selected source or an authorized mirror. Refer to upstream components for their own licenses.
