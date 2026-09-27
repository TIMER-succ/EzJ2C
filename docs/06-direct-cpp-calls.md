# 6. 已转译的静态、special、private 方法直接调 C++

优先级第六。`invokestatic` 今天是 `CallStatic*Method`，被调方再跑一遍 JNI 入口。两边都在本库里时，这次往返可以变成 C++ 调用。虚调用和接口调用第一版不要做。

## 现状

`MethodHandler.java:179-208` 对静态和 `invokespecial` 解析 `cclasses[owner]` 与 `cmethods[id]`，片段是 `CallStatic*Method` / `CallNonvirtual*Method`（`cppsnippets.properties:407` 起、`INVOKESTATIC_5` 在 `:559`）。参数是变参形式，每个参数从 `cstack` 里单独取出（`INVOKE_ARG_*`，`:399` 附近）。

C++ 符号在 `MethodProcessor.java:120-123`：`__ngen_` + `SpecialMethodProcessor.preProcess` 的结果。普通方法是 `native_` + 方法名 + `methodIndex`（`DefaultSpecialMethodProcessor.java:35`）。接口方法不是 native，而是 Java 桩再 `invokestatic` hidden 类（`DefaultSpecialMethodProcessor.java:18-32, 39-62`）。`<init>` 从不转译（`MethodProcessor.java:95`）。

JNI 调用顺便做三件 C++ 直调不会做的事：

1. 触发目标类的 `<clinit>`
2. `ACC_SYNCHRONIZED` 的 monitor
3. 按描述符检查参数并在异常挂起时安全返回

直调必须自己保住这三件，或者干脆不直调。

## 计划

### 第一版允许直调的目标

同时满足：

- 操作码是 `INVOKESTATIC`，或 `INVOKESPECIAL` 且目标不是 `<init>`，或字节码里对 **private** 方法的调用（Java 8 上 private 实例方法就是 `invokespecial`）
- 目标类、方法名、描述符能在本次转译集合里找到唯一的 C++ 函数
- 目标没有 `ACC_SYNCHRONIZED`
- 目标不是接口方法，也不是 hidden 代理（`interfacestatic_*`、`__ngen_special_clinit_*`）
- 目标不是 `<init>`

以下保持 `Call*Method`：

- `invokevirtual`、`invokeinterface`（有覆盖就叫错函数）
- 任何没被转译的方法（JDK、第三方、黑名单、`@NotNative`、抽象方法）
- `synchronized` 方法
- `<init>` 以及 `MethodHandle.invoke` / `invokeExact` 的 hidden 转发（`MethodHandler.java:115-149`）

过滤键与黑白名单一致：`class#method!desc`（`MethodProcessor.java:317-318`）。不要只用名字。

### 函数拆成入口和函数体

直调如果打到现在的 JNI 入口，第 1 项拿掉前奏之前，每次仍会 `getClassLoader`。拆开：

- `__ngen_native_foo0`：JNI 入口，签名不变，`RegisterNatives` 仍指向它。里面只做前奏（或第 1 项之后的 loader/null 检查），再调 body。
- `__ngen_native_foo0_body`：现有的栈、局部、指令。签名与入口相同：`(JNIEnv *env, jclass or jobject, args...)`。

直调点调用 body，不调用入口。这样前奏只在从 Java 进来时跑一次。

`jclass` / `jobject` 仍传。静态调用传目标类的 `cclasses[owner]`（先走现有的类解析，保证类已初始化）。实例 `invokespecial` 传接收者对象。不要传调用方自己的 `clazz`。

### 类初始化

`CallStatic*Method` 会跑目标 `<clinit>`。直调不会。

在直调之前保留现在的类解析慢路径（`cclasses[owner]` 为空时 `loadClass`）。`ClassLoader.loadClass` 会触发 `<clinit>`。解析完成后再调 body。同一类的递归或互相调用：`<clinit>` 已在跑时 JVM 不会重入，`loadClass` 同样不会。不要自己再调一遍 `<clinit>` 体。

目标类就是当前类时，`<clinit>` 要么正在跑要么已经跑完，解析仍应保持幂等。

### 异常

Body 的约定与现在的 native 相同：出错时异常挂在 `JNIEnv` 上并 `return 0`。直调之后必须保留原来的 `$trycatchhandler`（`ExceptionCheck` 或 try 分发）。不要改成 C++ 异常。

返回值从 body 的返回值写入 `cstack`，与现在 `Call*Method` 写入的槽相同。

### 链接

每个类 cpp 目前只 include 自己的 hpp（`ClassSourceBuilder.java:41-43`）。跨类直调需要声明。

新增生成文件 `native_jvm_direct.hpp`，列出所有 body 的原型，每个类 cpp include 它。A 调 B、B 调 A 时只有声明、定义在各自 cpp 里，避免 include 成环。

符号必须是现有的 C++ 名加 `_body` 后缀，放在原来的命名空间 `native_jvm::classes::__ngen_{file}`。调用点写全限定名。

### 同步方法

第一版不直调 `ACC_SYNCHRONIZED`。若以后要做，必须在调用点按 JVM 规则加 `MonitorEnter`/`MonitorExit`（静态锁 Class 对象，实例锁接收者），并在异常路径 `MonitorExit`。不要在没测试这个之前生成。

## 语义风险

- 符号或描述符配对错误：参数错位，表现为坏返回值或崩溃，而不是 `NoSuchMethodError`。映射必须用 `owner + name + desc`，并且只接受本次真正发出去的 body。
- 漏了类解析就直调：静态状态还没初始化，与 ideal 的打印顺序不一致。
- 直调了 `invokevirtual`：子类覆盖被跳过。第一版禁止。
- 直调了 `synchronized`：锁不再互斥。第一版禁止。
- 异常没检查：调用方继续跑，随后的 JNI 未定义。`$trycatchhandler` 必须留在调用点。

## 测试

### 正确性

目录 `obfuscator/test_data/tests/perf/06-direct-call/`。两个类都要被转译（不要把被调方放进黑名单）。

| 用例 | 目的 | 期望 |
|---|---|---|
| `StaticSameClass` | 同类 `invokestatic` 返回 `a+b` | stdout 是和 |
| `StaticCrossClass` | 另一个类的静态方法 | stdout 一致 |
| `Recurse` | 静态递归算阶乘，深度 20 | 值一致，无爆栈以外的差异；深度不要用 100000 |
| `SpecialSuper` | `super.f()` 走 `invokespecial`，子类有覆盖 | 打印 `super`，不是 `sub` |
| `VirtualStay` | `invokevirtual` 打到子类覆盖 | 打印 `sub`。生成代码仍是 `CallIntMethod`，不是 body 调用 |
| `Private` | private 实例方法 | 返回值一致，且是直调 |
| `ClinitOrder` | 类 B 的静态块打印 `init`，A 的方法第一次调用 B | stdout 先 `init` 再 `run`，`init` 一次 |
| `Throw` | 被调方抛 NPE，调用方 try/catch | 打印 `npe` |
| `Synchronized` | 被调方是 `synchronized` 静态方法 | 结果对，且生成代码仍是 `CallStatic*Method` |
| `Init` | `invokespecial <init>` | 对象字段正确，生成代码仍是 `CallNonvirtual*Method` |
| `Untranslated` | 调 `java/lang/Math.max` 或黑名单里的方法 | 结果对，且是 `Call*Method` |

`VirtualStay` 和 `Synchronized` 是反向测试：行为对还不够，生成代码必须没有直调。

### 生成代码检查

`StaticSameClass` 的调用点：

- 有 `__ngen_native_..._body(`
- 没有 `CallStaticIntMethod`
- 调用之后仍有 `ExceptionCheck` 或 `goto L_CATCH_`
- 调用之前有 `cclasses[` 的空指针解析（保证 `<clinit>`）

`native_jvm_direct.hpp` 里有对应声明。两个 cpp 都 include 它。

`VirtualStay`、`Synchronized`、`Init`、`Untranslated` 的调用点没有 `_body(`。

### 性能

`StaticSameClass`：循环 5_000_000 次调用一个返回 `x+1` 的静态方法。校验和进 stdout，耗时进 stderr。

期望：相对 `CallStatic*Method` 加被调方前奏，耗时明显下降。被调方若仍是解释器式 `jvalue` 栈，剩余时间在栈拷贝，不在本项继续抠。

### 失败时看什么

- `init` 打印两次或一次都没有：类初始化屏障错了。
- `super` 用例打印 `sub`：把 `invokespecial` 发成了虚调用，或相反。
- catch 不到：直调后没有 `ExceptionCheck`，异常挂着返回了 0，Java 侧看到的是返回值而不是异常。
- 链接错误：body 声明没进公共头文件，或命名空间写错。
