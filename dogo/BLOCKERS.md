# Dogo — known blockers

## #1 AGP jar-instrumentation corrupts signed multi-release jars (blocks Robolectric)

**Status:** open, workarounds exhausted for now.

### Symptom

Any Robolectric test fails during class init:

```
java.lang.InternalError: cannot create instance of
  org.bouncycastle.jcajce.provider.digest.Blake2b$Mappings :
  java.lang.SecurityException: SHA-256 digest error for
  org/bouncycastle/internal/asn1/misc/MiscObjectIdentifiers.class
    at org.bouncycastle.jce.provider.BouncyCastleProvider.loadServiceClass(...)
    at org.robolectric.android.internal.AndroidTestEnvironment.<clinit>(AndroidTestEnvironment.java:113)
```

### Root cause

1. Robolectric 4.17 transitively pulls `org.bouncycastle:bcprov-jdk18on:1.85`
   (via `plugins-maven-dependency-resolver`, which uses BouncyCastle to verify
   the `android-all` jars it downloads at test time).
2. `bcprov-jdk18on` is a **signed** multi-release jar. Its `META-INF/MANIFEST.MF`
   carries **7,285 per-entry SHA-256 digests**.
3. AGP 9.4.1 applies a bytecode-instrumentation artifact transform to every jar
   on the unit-test runtime classpath. It **rewrites class bytes but does not
   recompute the manifest digests**.
4. The JDK's `JarVerifier` therefore rejects the jar.

Evidence: the transform output is at
`~/.gradle/caches/9.8.0/transforms/<hash>/transformed/bcprov-jdk18on-1.85.jar`
— same entry count (7,611) and same `META-INF/versions/9` count (1,431) as the
original, so it is not an MR-JAR "explode"; but the compressed size grows
10.3M -> 20M and `MiscObjectIdentifiers.class` hashes differ:

```
orig:       ed94751452fd91ab85969d5954a8cc612ece1235
transformed: fa85f8decf3e998a1d872f55b3d04a1bf19ca9a4
```

### Ruled out

- **Corrupt downloads** — both cached bcprov jars are byte-identical to Maven Central
  (`sha1 698ebb55...` for 1.80.2, `2320b854...` for 1.85).
- **`-Pandroid.experimental.self-instrumenting=false`** — does not avoid the transform.
- **Robolectric/SDK mismatch** — not the issue: Robolectric 4.17 *does* ship an
  `android-all` for SDK 17 / API 37 (`17-robolectric-15733970-i7`), which matches
  our `compileSdk 37`.

### Possible fixes to try, in rough order of promise

1. Make AGP skip instrumentation for this artifact (look for an AGP 9 DSL/flag on
   `VariantDependenciesBuilder` self-instrumentation; the property exists as
   `ModulePropertyKey.BooleanWithDefault.SELF_INSTRUMENTING`).
2. Replace `bcprov-jdk18on` on the test classpath with a build of it whose manifest
   digests have been stripped, so JarVerifier has nothing to check.
3. Drive Robolectric from a separate, non-AGP Gradle module (so no AGP transform is
   applied to its classpath).
4. File upstream with the AGP team (repro: signed MR-JAR + unit test runtime classpath).

### Current mitigation

- The pure-JVM JUnit harness works (`dogo/tests`), which covers the majority of the
  Dogo logic: `CloudRouter`, `ActionExecutor`, `HardwareProfile`,
  `ModelRunner.supportedVariants`, `SetupChatAgent`, `FdroidHelper`.
- `RobolectricSmokeTest` is `@Ignore`d so it documents the blocker without
  breaking `./gradlew :testLawnWithQuickstepGithubDebugUnitTest`.
- Anything needing a real `Context`, `SharedPreferences` or merged `R` must be an
  **instrumented** test (`dogo/androidTest`, on a device) until this is fixed.