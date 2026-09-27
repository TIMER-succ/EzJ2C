# 7. 次要优化（合并）

这四项单独都带不来数量级差异，但改动面小，适合在前六项之后做。顺序：null 比较、`Call*MethodA`、Clang `-O2`、缓存 `throw_re` 的异常类。

正确性仍走 `ClassicTest`：stdout 必须与 ideal 一致，耗时打 stderr。单目录时把 `TestsGenerator.ALLOWED_TESTS` 设成目录名，再 `gradlew.bat :obfuscator:test --tests by.radioegor146.TestsGenerator`。生成代码用 `java -jar obfuscator/build/libs/obfuscator.jar in.jar out -p hotspot --plain-lib-name` 查看。

---

## 7.1 IFNULL / IFNONNULL 用指针比较

### 现状

`cppsnippets.properties:232-233`：

```text
IFNULL=if (env->IsSameObject(cstack$stackindexm1.l, nullptr)) goto $label;
IFNONNULL=if (!env->IsSameObject(cstack$stackindexm1.l, nullptr)) goto $label;
```

JNI 规定 null 引用就是 `nullptr`。和 null 比不需要 `IsSameObject`。

`IF_ACMPEQ` / `IF_ACMPNE`（`:201-202`）必须继续用 `IsSameObject`。两个局部引用可以指向同一对象但指针不相等（例如一个是 `NewLocalRef` 的结果）。把引用相等改成 `==` 会让 `==` 在 Java 里为真、在 native 里为假。

### 计划

只改这两行：

```text
IFNULL=if (cstack$stackindexm1.l == nullptr) goto $label;
IFNONNULL=if (cstack$stackindexm1.l != nullptr) goto $label;
```

不改 `IF_ACMPEQ`、`IF_ACMPNE`、`IFNULL` 以外的任何比较。`ACONST_NULL` 已经写成 `nullptr`（`:14`），与这个判断一致。

### 测试

目录 `obfuscator/test_data/tests/perf/07-minor/NullBranch`。

| 用例 | stdout |
|---|---|
| 引用为 null，走 `if (o == null)` | `null` |
| 引用非 null | `nn` |
| `if (o != null)` 两个分支 | 同上，标记不同 |
| 两个局部变量指向 `new Object()` 同一次分配，`o1 == o2` | `same` |
| 两个不同 `new Object()` | `diff` |

最后两个保护 `IF_ACMPEQ` 不被误改。生成代码里 `IFNULL` 行没有 `IsSameObject`，`IF_ACMPEQ` 行仍有。

性能：循环 5_000_000 次 `if (ref == null)`。校验标记进 stdout，耗时进 stderr。期望相对改前下降，幅度小于去掉 label poll。

---

## 7.2 Call*Method 改为 Call*MethodA

### 现状

调用片段是变参形式，例如 `INVOKEVIRTUAL_5`（`cppsnippets.properties:525`）：

```text
cstack$returnstackindex.i = env->CallIntMethod(cstack$objectstackindex.l, $methodid$args);
```

`$args` 来自 `MethodHandler.java:171-174`，展开成 `, cstackN.i` 这种逗号分隔列表（`INVOKE_ARG_5` 在 `:399`）。`jvalue` 栈槽是分开的局部变量，不是连续数组，不能直接把某个 `cstack` 的地址当成 `jvalue*` 传进去。`long`/`double` 在 JVM 栈上占两槽，但值放在低槽的 `.j` / `.d` 里，JNI 的 `jvalue` 数组里只占一个元素。

变参在参数多时要逐个装箱进 `va_list`。`Call*MethodA` 接收现成的 `jvalue` 数组。参数少时差异很小。

### 计划

在 `MethodHandler` 里，当参数个数大于 0 时，在 `Call*Method` 之前发出：

```cpp
jvalue __nargs[N];
__nargs[0] = cstack<lowSlot>;
__nargs[1] = cstack<lowSlot2>;
```

`N` 是参数个数，不是栈槽数。每个参数拷贝它的低槽整份 `jvalue`（`long`/`double` 不要拷高槽）。然后把片段换成 `CallIntMethodA(obj, methodID, __nargs)` 以及 void/静态/非虚的对应 `*MethodA`。

无参调用用 `CallIntMethodA(..., nullptr)`，不要发长度为 0 的数组，避免零长度数组在某些编译器上告警。

不要改解析 `cmethods[]` 的逻辑，也不要因此改成第 6 项的直调。本项只换 JNI 入口的调用形态。

对象返回值的 `refs.insert` 保持。`$trycatchhandler` 保持。

### 测试

目录 `perf/07-minor/CallA`。

| 用例 | 目的 |
|---|---|
| 无参 `int` 返回 | `nullptr` 参数指针，值正确 |
| 一个 `int`、一个 `long`、一个对象混排 | 槽位没有把 `long` 的高槽当成下一个参数 |
| `void`、静态、`invokespecial` 各一个 | 三种 JNI 形态都换成了 `*MethodA` |
| 返回对象 | Java 侧能读到字段，说明局部引用还在 |
| 被调方抛 NPE | 调用方 catch 到，打印 `npe` |

生成代码：有参调用出现 `CallIntMethodA` / `CallStaticIntMethodA` / `CallNonvirtualIntMethodA`，不再出现对应的变参 `CallIntMethod(`（注意 `A` 后缀，搜索时不要误伤）。`long` 参数的 `__nargs` 赋值来自低槽。

性能：10 个 `int` 参数的调用，循环 1_000_000 次。耗时进 stderr。期望有下降，但小于第 6 项的直调。若测量噪声盖过差异，以生成代码检查为准，不要为了这项调优化级别。

---

## 7.3 Clang / AppleClang 打开 -O2

### 现状

`obfuscator/src/main/resources/sources/CMakeLists.txt:9-26`：

- GNU：`-O2 -s -DNDEBUG`（`:13`）
- MSVC：不在这里写 `/O2`，测试用 `cmake --build . --config Release`（`ClassicTest.java:164-165`），Release 自带 `/O2`
- Clang（`:22`）和 AppleClang（`:23`）分支是空的

用 Clang 编出来的 native 库是 `-O0`。这会让前六项的 C++ 侧收益测不准，也会让 Clang 用户的运行速度明显差于 GCC。

### 计划

与 GNU 对齐优化，但不要无条件加 `-s`：

- `Clang`：`-O2 -DNDEBUG`。Linux 上可以再加 `-s`（交给 ld）。确认 CI 的 ubuntu + Clang 若存在再加；当前 CI 文档写的是默认编译器，不要为了 `-s` 改失败的链接器选项。
- `AppleClang`：`-O2 -DNDEBUG`。不要加 `-s`（AppleClang 里 `-s` 不是 strip-all）。需要剥离符号时再用 `-Wl,-dead_strip`，本项不加。
- 不改 MSVC 分支。Release 配置已经是 `/O2`。不要把 `/O2` 写进对 Debug 也生效的 `CMAKE_CXX_FLAGS`，否则和现有“只在 GNU 上无条件 -O2”不一致；Clang 与 GNU 保持同样的无条件 `-O2`，因为这个文件就是发给最终用户的 native 库，不是本仓库的调试构建。

改的是资源模板 `CMakeLists.txt`，不是某次输出目录里已经生成的那份。

### 测试

没有单独的 Java 行为用例。用任意一个已有小测试（例如空 `main`）在 Clang 下配置：

```text
cmake -DCMAKE_CXX_COMPILER=clang++ .
cmake --build . --config Release
```

通过标准：

- 配置日志或 `compile_commands` / 详细构建里能看到 `-O2` 和 `-DNDEBUG`
- 目标仍然是 `build/lib` 下的共享库，`ClassicTest` 能 `System.load` 并跑完
- AppleClang 的命令行里没有 `-s`
- GCC 路径的 `-O2 -s -DNDEBUG` 不变
- 现有一个 ClassicTest 目录在 Clang 下 stdout 与 ideal 一致

性能：同一 `ArithLoop`（第 3 项）用 Clang `-O0`（改前）和 `-O2`（改后）各计时一次，stderr 对比。期望 `-O2` 明显更快。这项测的是编译选项，不是 JNI 次数。

---

## 7.4 缓存 throw_re 用的异常类

### 现状

`utils::throw_re`（`native_jvm.cpp:290-296`）每次：

```cpp
jclass exception_class_ptr = env->FindClass(exception_class);
env->ThrowNew(exception_class_ptr, ("\"" + std::string(error) + "\" on " + std::to_string(line)).c_str());
env->DeleteLocalRef(exception_class_ptr);
```

`FindClass` 走 bootstrap loader。片段里的异常类都是 `java.lang` 的：`NullPointerException`、`ArithmeticException`、`NegativeArraySizeException`、`ClassCastException`，以及 `native_jvm.cpp` 里多维数组用的 `NegativeArraySizeException`。具体名单以片段里的 `*_S_CONST_` 和 `throw_re` 的字符串参数为准，实施时再搜一遍，不要漏 `ArrayIndexOutOfBoundsException`（若某条数组路径改成自己 `throw_re` 而不是靠 Region 抛出，第 5 项会用到）。

这是冷路径，除非用户用异常做控制流。热循环不该靠这项变快。

`init_utils`（`native_jvm.cpp:38`）已经用同样的方式缓存了 `boolean_array_class` 和 `NoClassDefFoundError`（`:83-87`）：`FindClass` + `NewGlobalRef`。

### 计划

在 `init_utils` 里为上述异常类各做一次 `NewGlobalRef`，存成文件内静态 `jclass`。`throw_re` 按类名选缓存：

- 已知类：直接 `ThrowNew`，不再 `FindClass`，也不要 `DeleteLocalRef` 这个全局引用
- 未知类：保持现在的 `FindClass` 路径，避免以后新异常被静默丢弃

`FindClass` 失败时现在的行为是 `ExceptionCheck` 后直接 return（`:292-294`），挂起异常留在 JNI 上。缓存失败（`JNI_OnLoad` 时类竟然不存在）应在 `init_utils` 里同样留下失败，不要在 `throw_re` 里对空 `jclass` 调 `ThrowNew`。

消息字符串仍每次 `std::string` 拼接。可以顺手改成栈上的小缓冲，但不要为了它改消息格式。ClassicTest 若有人把 `getMessage()` 打到 stdout，格式必须仍是 `"\"<desc>\" on <line>"`。

不要把这些全局引用换成应用 ClassLoader 找出来的类。现在就是 bootstrap 的 `java.lang` 异常，缓存后必须还是同一个类，这样 `catch (NullPointerException)` 仍然匹配。

### 测试

目录 `perf/07-minor/ThrowCache`。

| 用例 | 期望 |
|---|---|
| null 上 `getfield` | Java 侧 `catch (NullPointerException)` 打印 `npe` |
| `1/0` | `ArithmeticException`，打印 `ae` |
| `new int[-1]` | `NegativeArraySizeException`，打印 `nase` |
| 失败的 `checkcast` | `ClassCastException`，打印 `cce` |
| 消息 | 若打印 `getMessage()`，须与改前完全一致，包括引号和 `on <line>`。line 来自 `LineNumberHandler`，javac 行号变化会让 ideal 与 native 都变，但两边要一致 |

生成代码 / 运行时：在 `throw_re` 里对这四个类名不再调用 `FindClass`（调试器或临时计数器）。未知类名的回退仍调用。不要把计数器留在提交里；测试以异常类型为准。

性能：不设门槛。可选地循环抛 NPE 并 catch，stderr 计时，只作为“异常当控制流”时的参考。热路径基准不应把这项算进收益。
