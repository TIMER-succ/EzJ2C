# 3. 去掉 label 上的 ExceptionCheck

优先级第三。算术和分支本身没有 JNI，但每个 label 仍会向 JVM 问一次“有没有挂起异常”。javac 几乎每行、每个分支目标一个 label，紧循环会按迭代付这个成本。

## 现状

`LabelHandler.accept`（`LabelHandler.java:9-19`）在更新 try 范围后调用 `super.accept`。`GenericInstructionHandler`（`GenericInstructionHandler.java:36-49`）据此生成：

- 不在 try 里：`TRYCATCH_EMPTY`（`cppsnippets.properties:246`）

  ```cpp
  if (env->ExceptionCheck()) { return (rettype) 0; }
  ```

- 在 try 里：`TRYCATCH_START`（`cppsnippets.properties:242-245`），`ExceptionOccurred` + `ExceptionClear` + `refs.insert` + `goto L_CATCH_N`

`super.accept` 对 label 会走到 `process()` 并抛 `UnsupportedOperationException`，被吃掉（`LabelHandler.java:14-18`）。label 没有自己的 opcode 片段，**唯一发出的代码就是这个 poll**，外加 `Ln:`。

真正的 catch 分发不在 label 上。`L_CATCH_N` 在方法末尾生成（`MethodProcessor.java:267-306`），用 `IsInstanceOf` 选择 handler。去掉 label poll 不会删掉这段。

会抛异常的指令大多已经自带 `$trycatchhandler`：字段、调用、数组、`new`、monitor、`athrow`、`checkcast`、除零路径上的 `idiv`/`irem`。poll 是第二道网，用来兜“片段忘了检查”以及“异常发生在两条指令之间”。

`INSTANCEOF`（`cppsnippets.properties:223`）**没有** `$trycatchhandler`。它依赖后面的 label poll。`IsInstanceOf` 在类已经解析成功时通常不抛，但 JNI 允许它在有挂起异常或解析失败时抛。label poll 一旦删除，这里必须补上。

`synchronized` 方法和 `try/finally` 让整个方法体处于 handler 内。今天每个 label 都走重的 `TRYCATCH_START`，而不只是 `ExceptionCheck`。

## 计划

### Label

`LabelHandler` 不再调用会发射 `trimmedTryCatchBlock` 的 `super.accept`。只做现在的 try 范围增删，然后输出：

```cpp
Ln:
```

try 内、try 外都一样。不要在 label 上保留“轻量 poll”作为折中，那仍然是每次迭代一次 JNI。

`LineNumberHandler` 只写 `context.line`，确认它没有 poll，不用改。

### 补上真正会抛、却没有 handler 的指令

实施前把 `cppsnippets.properties` 里每个可能产生挂起异常的键对一遍，标准是：JNI 调用或 `throw_re` 之后，控制流落到下一条指令之前，必须已经展开过 `$trycatchhandler`。

已知缺口：

- `INSTANCEOF`（`cppsnippets.properties:223`）。改成与 `CHECKCAST` 一样在 `IsInstanceOf` 之后接 `$trycatchhandler`。`instanceof` 对 null 必须仍是 0，这个三元表达式要留着。
- 再搜一遍片段里的 `env->`，凡是没有 `$trycatchhandler` 的，列入表里逐个决定。`IF_ACMPEQ` 的 `IsSameObject`（`:201`）按 JNI 规范在参数是局部引用时不抛；不要为它加热路径检查。

`GenericInstructionHandler` 已经把 `trycatchhandler` 放进 `props`。片段里写了 `$trycatchhandler` 就会带上 try 内的重路径或 try 外的 `return 0`。label 不再承担这个职责之后，片段漏写就是功能 bug，不是性能问题。

### 禁止的“优化”

不要把两次 JNI 并到一个 `ExceptionCheck` 后面。第一次若已挂起异常，第二次 JNI 的行为是未定义的。字段访问、调用、数组访问各自保留自己的检查。

不要删 `MethodProcessor.java:267-306` 的 `L_CATCH_N`。那是 handler 本体。

不要用 C++ `try/catch` 代替 JNI 挂起异常。现有约定是：抛异常的 native 返回 0，异常留在 `JNIEnv` 里。

### try 内的行为

抛异常的指令在片段末尾已经 `goto L_CATCH_N`（try 内）或 `return 0`（try 外）。label 不再转发异常后，异常只在这些指令边界被分发。这与 JVM 的精度一致：异常点是抛出的那条指令，不是下一条 label。

`TRYCATCH_START` 会 `ExceptionClear` 再跳到 catch。生成代码必须保证清除之后、跳转之前没有别的 JNI。现有片段是这样，保持。

## 语义风险

- 漏补的指令会带着挂起异常执行后续 JNI，结果是崩溃、异常被吞，或错误地 `return 0`。这是本项的主要风险，靠下面的对照表而不是靠“再留一个 label poll”来防。
- try 外：行为应与现在相同（挂起异常然后从 native 返回）。
- 异常消息、异常类型不应变化。
- 调试器单步时，异常不会再在“下一行 label”才被观察到。没有调试信息依赖这个。

## 测试

### 正确性

目录 `obfuscator/test_data/tests/perf/03-exception-poll/`。

| 用例 | 目的 | stdout |
|---|---|---|
| `ArithLoop` | 纯 int 循环，无 try，确认去掉 poll 后结果仍对 | 累加和 |
| `CatchField` | `try { obj.f; } catch (NullPointerException e)`，`obj == null` | `npe` |
| `CatchDiv` | `try { 1/0; } catch (ArithmeticException e)` | `ae` |
| `CatchArray` | 越界 `iaload` | `aioobe` |
| `CatchCast` | 失败的 `checkcast` | `cce` |
| `InstanceofOk` | null、成功、失败三种 `instanceof` | `0` / `1` / `0` |
| `Finally` | `try/finally` 里递增计数，try 里抛，finally 必须跑 | finally 计数 + `npe` |
| `Sync` | `synchronized` 方法里抛 NPE，锁要释放（另一线程能再进） | `entered` |
| `NoCatchReturn` | try 外的 `getfield` NPE 传到 Java 调用方，调用方 catch | `npe` |

`Finally` 和 `Sync` 是本项最容易改坏的。`synchronized` 在字节码里就是 try/finally + `monitorexit`，label 上的重 poll 今天覆盖了整个方法。改完后 poll 只应出现在 `monitorenter`/`monitorexit`/`getfield` 等指令后面，finally 仍要执行。

现有带 try 的 ClassicTest 目录开 `ALLOWED_TESTS` 再跑一遍。有 Krakatau 的 `.j` 用例不要跳，它们的异常表比 javac 更怪。

### 生成代码检查

`ArithLoop` 的 cpp：

- 每个 `Ln:` 后面不要立刻出现 `ExceptionCheck`
- 循环体内的 `iadd` / `if_icmplt` 附近没有 `ExceptionCheck`
- 方法末尾如果没有 catch，就没有 `L_CATCH_`

`CatchField` 的 cpp：

- `GetIntField` 所在片段后面仍有 `ExceptionCheck`，try 内应 `goto L_CATCH_`
- `L_CATCH_` 仍在方法末尾，且 `IsInstanceOf` 对 `NullPointerException`

`InstanceofOk` 的 `IsInstanceOf` 之后必须有 `ExceptionCheck` 或 try 分发。这是本项新加上的。

### 性能

`ArithLoop` 预热后计时 5_000_000 次空转算术（循环依赖用 volatile 读或把结果累加打印，避免理想 JIT 把循环删掉；native 侧没有这个优化，所以 ideal 可能变成 0。更稳的是循环里做 `sum += i` 并打印 `sum`）。耗时进 stderr。

期望：native 循环相对改前下降。循环体里不应再有按 label 的 JNI。若 profile 仍显示 `ExceptionCheck` 占大头，说明有指令片段仍然每条都查，那是字段/调用项，不是本项漏了。
