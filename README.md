# libpebble3

libpebble3 is a kotlin multiplatform library for interacting with Pebble devices. It is designed to do everything that a Pebble/Core watch companion app needs to do, except for the UI and specific web services.

This includes: connection management/bluetooth on all supported platforms, notification listener/processing, timeline, blobdb storage/management, firmware updating, PebbleKit JS, music control, etc etc. A "host" app using this library will only need to provide a UI and implementations to fetch things like the app locker (likely via web services). The library will do everything else in the background.

It is not yet at feature parity with the original Pebble apps. We have not yet documented which features are complete/missing.

# Using libpebble3

libpebble3 is still in development - maybe best described as alpha. The API surface is subject to (large) changes as we develop the library along with CoreApp (which uses the library). There is no documentation (yet) on how to use the library.

No artifacts are published yet (we include libpebble3 as a submodule for CoreApp development).

In the future we envisage supporting more targets, but today only iOS/Android are supported.

# Contributing

We aren't actively encouraging contributions yet, while we are aggressively building out feature parity with the original Pebble apps. CI testing is not comprehensive, so changes need to be manually tested with real hardware using CoreApp, and our roadmap/bug tracker is not currently on github. We will update when this changes.

# Development Guidelines

(We don't follow all of these everywhere, yet, and need to document a lot more..)

- We share a version catalog with CoreApp to avoid duplicating definitions. This means a few extra library entries which are not used in libpebble (so they can share the version definition).
- Use `optIn` in `build.gradle.kts` rather than individual source files.
- Only use injected coroutine scopes: either LibPebbleCoroutineScope (instead of GlobalScope) or ConnectionCoroutineScope (scoped per-connection).

Connection:
- Services are scoped to the connection. Their main job is to translate raw pebble protocol messages to something readable by the rest of the app.
- Endpoint managers are also scoped to the connection, and manage complex state around services.

# Copyright and Licensing

See https://ericmigi.notion.site/Core-Devices-Software-Licensing-1c0fbb55ea8480f88d27ccf20fcb84a8

Copyright 2025 Core Devices LLC

This software is dual-licensed by Core Devices LLC. It can be used either:
  
(1) for free under the terms of the GNU GPLv3; OR
  
(2) under the terms of a paid-for Core Devices Commercial License agreement between you and Core Devices (the terms of which may vary depending on what you and Core Devices have agreed to).

Unless required by applicable law or agreed to in writing, software distributed under the Licenses is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the Licenses for the specific language governing permissions and limitations under the Licenses.

Additional Permissions For Submission to Apple App Store: Provided that you are otherwise in compliance with the GPLv3 for each covered work you convey (including without limitation making the Corresponding Source available in compliance with Section 6 of the GPLv3), Core Devices also grants you the additional permission to convey through the Apple App Store non-source executable versions of the Program as incorporated into each applicable covered work as Executable Versions only under the Mozilla Public License version 2.0 (https://www.mozilla.org/en-US/MPL/2.0/).
