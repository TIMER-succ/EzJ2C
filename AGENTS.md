# AGENTS.md — native-obfuscator (EzJ2C)

Java bytecode → JNI C++ transpiler. Tool does **not** obfuscate; it emits native code. Fully supported: Java 8. Java 9+/Android experimental. Upstream: `radioegor146/native-obfuscator`. Tool version string: `3.5.4r` in `Main`.

GPL-3.0 for the tool. **Output Exception** (LICENSE tail): emitted runtime (`native_jvm.*`, `string_pool.hpp`, Loader, `cppsnippets.properties` fragments) may be linked/distributed under any terms.

## Commands

- Build fat jar (skip tests): `./gradlew assemble` (Windows: `gradlew.bat assemble`)
- Full suite (needs JDK, CMake, C++ toolchain; Krakatau `krak2` optional): `./gradlew build`
- One unit test: `./gradlew :obfuscator:test --tests by.radioegor146.source.StringPoolTest`
- One ClassicTest folder: set `ALLOWED_TESTS` in `TestsGenerator` (see Testing), then `./gradlew :obfuscator:test`
- Run tool: `java -jar obfuscator/build/libs/obfuscator.jar <jar> <outDir> [flags]`
- Native after transpile: `cmake .` then `cmake --build . --config Release` in `<outDir>/cpp`; libs land in `cpp/build/lib/`

No dedicated lint/typecheck. Source/target **Java 8**. Gradle 9.3.1 wrapper. Shadow plugin is **`com.gradleup.shadow` 9.6.1** (not johnrengelman). `assemble` depends on `shadowJar`; classifier is empty so output is `obfuscator.jar`. Main: `by.radioegor146.Main`.

CI (`.github/workflows/main.yml`): JDK 8/11/17/21/25 × ubuntu/macos/windows, installs Krakatau + CMake, runs `./gradlew build`. macOS JDK 8 uses `macos-13`.

## Pipeline (do not skip stages)

1. CLI `Main` → `NativeObfuscator.process`
2. Filter class/method (`ClassMethodFilter` + annotations/lists)
3. **Preprocessor** rewrites indy/LDC Handle/MethodType to ordinary bytecode
4. Re-read via `SafeClassWriter` (COMPUTE_MAXS|FRAMES)
5. Inject empty `<clinit>` if missing
6. `MethodProcessor` emits C++ per method; Java body becomes `native` or hidden-proxy stub
7. Write output jar (classfile version **forced to 52**), `cpp/output/*.cpp|hpp`, `string_pool.cpp`, `native_jvm_output.cpp`, `CMakeLists.txt`
8. Hidden classes: HOTSPOT/STD_JAVA embed bytes + `DefineClass`; ANDROID writes `Hidden*.class` into the jar (no DefineClass)

Hard reject: input jar parent **must not** be the same path as output dir.

## Gradle modules

| Module | Role |
|---|---|
| `:annotations` | Tiny API: `@Native` / `@NotNative`. CLASS retention. Depend from user projects; `-a` enables. |
| `:obfuscator` | Tool + runtime templates + tests. Depends on `:annotations`. |

## Packages — `by.radioegor146`

Orchestration / CLI

- `Main` — picocli. Flags: `-l` libs, `-b`/`-w` lists, `-p` platform (`hotspot` default), `-a` annotations, `-i`/`--inline` (default on, `--no-inline` off), `--plain-lib-name`, `--custom-lib-dir`, `--debug`.
- `NativeObfuscator` — jar walk, preprocess, process, emit cpp/jar/loader. Picks `nativeN/` unless `--custom-lib-dir`. Prints `/{nativeDir}/` when using unpack loader.
- `Platform` — `HOTSPOT` (CMake `-DUSE_HOTSPOT=1`, JVM internals), `STD_JAVA` (portable), `ANDROID` (no DefineClass).
- `MethodProcessor` — JNI function skeleton, locals/stack (`clocalN`/`cstackN` as `jvalue`), dispatch by `AbstractInsnNode.getType()`. Skips abstract, already-native, **`<init>`**. Filter key: `class#method!desc` (bang before descriptor).
- `MethodContext` — per-method state: output buffers, stack/locals, try-catches, `proxyMethod`, `cppNativeMethodName`.
- `ClassMethodFilter` — blacklist wins; if whitelist present, must match; then annotations if `-a`. Cleans `@Native`/`@NotNative` from output when `-a`.
- `ClassMethodList` — exact entries + glob: `*` = one `/` segment, `**` = any. Patterns compiled after unicode-quoting.
- `Snippets` — loads `sources/cppsnippets.properties`. Keys = ASM opcode names (`IADD`, `GETFIELD_5`, …). `_S_VARS` lists tokens; `#const` / `$token` go through `StringPool`.
- `NodeCache<T>` — per-class intern of strings/classes/methods/fields → `cstrings[i]` etc. Cleared each class.
- `CachedMethodInfo` / `CachedFieldInfo` — cache keys (owner, name, desc, static).
- `HiddenMethodsPool` — synthetic `nativeDir/hidden/HiddenN` classes (≤10000 methods each). Used for `<clinit>`, interface natives.
- `HiddenCppMethod` — hidden MethodNode ↔ C++ symbol for `RegisterNatives`.
- `LabelPool` — ASM `Label` → `L1`, `L2`, … (WeakHashMap).
- `CatchesBlock` — unique catch-chain identity for generated `L_CATCH_N`.
- `Util` — resource copy, `$token` format, C++ identifier escape, zip helpers, opcode names.

`bytecode` — rewrite **before** C++ (indy cannot survive)

- `Preprocessor` / `PreprocessorRunner` — order: `IndyPreprocessor`, `LdcPreprocessor`.
- `IndyPreprocessor` — HOTSPOT: rewrite to `linkCallSite` magic; STD_JAVA/ANDROID: `invokeWithArguments` + BSM varargs fix. Leftover indy is a bug.
- `LdcPreprocessor` — `LDC Handle` / `LDC MethodType` → MethodHandles API seq.
- `MethodHandleUtils` — those sequences.
- `PreprocessorUtils` — sentinel invokes on `native/magic/1/{lookup,classloader,class,invoke,linkcallsite}/obfuscator{Math.random()}`. `MethodHandler` special-cases them (lookup/classloader locals, reverse invoke).

`special` — Java-side native wiring (`<init>` has no processor → never transpiled)

- `DefaultSpecialMethodProcessor` — normal: set `ACC_NATIVE`, cpp name `native_{name}{index}`. Interface: cannot be native; hidden static proxy + Java stub that calls it; strip `ACC_NATIVE` later.
- `ClInitSpecialMethodProcessor` — Java `<clinit>` becomes: `Loader.registerNativesForClass(classIndex, Class)` then hidden native `__ngen_special_clinit_*`.

`instructions` — one handler per ASM node type; most extend `GenericInstructionHandler` (try-catch snippet + stackindex props + opcode snippet)

- `InstructionTypeHandler` — `accept`, `insnToString`, `getNewStackPointer`.
- `InstructionHandlerContainer` — typed dispatch.
- `GenericInstructionHandler` — builds `trycatchhandler`, `rettype`, `stackindex*`.
- `InsnHandler` — zero-operand ops (arith, return, array, monitor, …).
- `IntHandler` / `VarHandler` / `TypeHandler` / `IincHandler` / `MultiANewArrayHandler`.
- `FieldHandler` — lazy `cclasses[]` weak-global + `cfields[]`; snippet key `GETSTATIC_{sort}` etc.
- `MethodHandler` — `cmethods[]`; magic preprocessor nodes; interface/special invoke. With `-i` (default), `Object.getClass` and `String.equals`/`isEmpty`/`length` emit JNI (`GetObjectClass`, `GetStringLength`, `utils::string_equals`) instead of `Call*Method`.
- `InvokeDynamicHandler` — **always throws**; indy must be gone.
- `JumpHandler` / `TableSwitchHandler` / `LookupSwitchHandler` — labels from `LabelPool`.
- `LdcHandler` — MIN_VALUE / NaN / Inf C++ literals; strings via interned `cstrings`.
- `LabelHandler` — updates active try-catch set; emits `Ln:`.
- `FrameHandler` — maintains `context.locals`/`stack` from StackMap; no C++.
- `LineNumberHandler` — sets `context.line` only.

`source` — generated C++

- `ClassSourceBuilder` — `cpp/output/{escaped}_{classIndex}.cpp|hpp`, namespace `__ngen_{file}`, `__ngen_register_methods`.
- `MainSourceBuilder` — fills `native_jvm_output.cpp`: per-class register ptrs + DefineClass for hidden data.
- `CMakeFilesBuilder` — fills `CMakeLists.txt` (C++17, JNI, `SHARED` lib `native_library`, out `build/lib`).
- `StringPool` — modified UTF-8 concatenated pool; `get` returns `((char *)(string_pool + NLL))`. Max encoded length 65535.

`compiletime` — copied into output jar as `{nativeDir}/Loader`

- `LoaderUnpack` — extract `/{nativeDir}/{arch}-{os}.{ext}` to temp `System.load`. Arch: x64/arm64/arm32/x86. Names: `x64-windows.dll`, `x64-linux.so`, `x64-macos.dylib`, …
- `LoaderPlain` — `System.loadLibrary("%LIB_NAME%")` replaced at emit. Tests always use this (`native_library`).

`ru.gravit.launchserver.asm` (vendored)

- `ClassMetadataReader` — superclasses from extra jars + input (needed for COMPUTE_FRAMES).
- `SafeClassWriter` — `getCommonSuperClass` via reader, not `Class.forName`.

`by.radioegor146.nativeobfuscator` (`:annotations`)

- `@Native` — type or method, include when `-a`.
- `@NotNative` — method only, exclude inside `@Native` class. Lists beat annotations.

## Resources (`obfuscator/src/main/resources/sources/`)

Copied or templated into `<out>/cpp/`:

- `native_jvm.hpp/.cpp` — JNI helpers (`find_class_wo_static`, arrays, HotSpot ifdef).
- `native_jvm_output.hpp/.cpp` — `JNI_OnLoad` → `prepare_lib` → register Loader native.
- `string_pool.hpp/.cpp` — pool accessor; `.cpp` overwritten by `StringPool.build()`.
- `CMakeLists.txt` — tokens `$projectname $classfiles $mainfiles $definitions`.
- `cppsnippets.properties` — **the** opcode→C++ map. Changing a handler without the snippet (or vice versa) breaks codegen.

## Testing

`TestsGenerator` `@TestFactory`: recursive dirs under `obfuscator/test_data/tests` that contain `.java`. `ClassicTest`: javac → optional `krak2 asm` on `.j` → jar → run ideal (`-Dseed=1337`) → for **each** `Platform`: process (`-a`, plain lib `native_library`, debug jar) → cmake → copy `build/lib` next to jar → run with `-Djava.library.path=.` → stdout must match ideal.

`test_data` is a test-resource root (`sourceSets.test.resources`). Filter: `TestsGenerator.ALLOWED_TESTS` (null = all). Krakatau missing → `.j` tests fail/skip path; CI always installs it.

Unit: `StringPoolTest`, `ClassMethodListTest`. `ProcessHelper` wraps external procs + timeout.

Integration is slow (cmake × 3 platforms). Prefer unit tests + one `ALLOWED_TESTS` folder when changing codegen.

## Conventions / gotchas

- Do not transpile constructors. Do not leave `INVOKEDYNAMIC` for C++.
- Interface methods: hidden proxy, never `ACC_NATIVE` on the interface method.
- `<clinit>` always registers natives first; empty clinit is injected if absent.
- Black/white list lines: internal names, `pkg/Cls#method!(I)Ljava/lang/String;`. README “`#descriptor`” is wrong; code uses `!`.
- Annotations live in `invisibleAnnotations` (CLASS retention).
- C++ stack model is interpreter-style `jvalue` slots, not a real JVM stack machine optimization.
- MSVC: `_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR` in `native_jvm.hpp` (VS mutex bug).
- GCC must be ≥ 6. CMake ≥ 3.8. C++17.
- `gradle.properties`: daemon off, parallel on.
- No comments in new Java unless asked; match existing style (public fields on `MethodContext`, ASM tree API, ASM9).
)
