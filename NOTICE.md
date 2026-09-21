# Source and build provenance

This is an original implementation for Minecraft 1.21.1 / NeoForge.
The user supplied https://github.com/xkball/LetMeSeeSee as a functional reference.
Reference inspected: commit 298808532af7f6aab0a0f0a1c28164e049ac0dd6 (LGPL-3.0).
No LetMeSeeSee source, assets, class exporter or decompiler are included.

Build pins: Java 21, Gradle 8.14.3, ModDevGradle 2.0.144, NeoForge 21.1.248.
Gradle wrapper bootstrap files are reused from the existing validated local Gradle
8.14.3 setup, distributed under Gradle's Apache-2.0 license.
Runtime permits NeoForge 21.1.x; only the version recorded in validation is tested.
KubeJS is optional and is never linked as a compile-time or runtime dependency.
KubeJS snippet APIs checked against KubeJS 2101 commit
1b4e9b819e4b372d92529f43542e1992c45701a2.
