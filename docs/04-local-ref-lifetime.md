# 4. 局部引用不要进 unordered_set

优先级第四。对象多的循环里，`refs.insert` / `refs.erase` / `GetObjectRefType` 比字段读取本身贵。这套结构是为了不撑破 JNI 局部引用表（默认 512），不是运算的一部分。

## 现状

每个方法开头（`MethodProcessor.java:233`）：

```cpp
std::unordered_set<jobject> refs;
```

插入点（`cppsnippets.properties`）：

- 引用参数：`LOCAL_LOAD_ARG_9/10/11`（`:9-11`），含 `this`
- `ALOAD`（`:41`）：`refs.insert(cstack.l)`，每次从局部变量取引用都插
- `GETFIELD` 对象结果（`:292` 附近）
- `INVOKE*` 对象返回值（`:541` 等）
- `AALOAD`、`NEW`、`ANEWARRAY`、`NEWARRAY_*`

帧边界 `FrameHandler.java:68-91`：把当前栈和局部里仍存活的引用从 set 里 `erase`，然后：

```cpp
utils::clear_refs(env, refs);
```

`clear_refs`（`native_jvm.cpp:339-343`）对剩余元素调用 `GetObjectRefType`，只 `DeleteLocalRef` 局部引用，然后 `clear`。

要查类型是因为 set 里混进了全局引用：`LDC` 字符串是 `cstrings[]` 里的 `NewGlobalRef`（`ClassSourceBuilder.java:88-91`），随后 `ASTORE`/`ALOAD` 会把这个全局引用再 insert 进来。盲目 `DeleteLocalRef` 会破坏全局引用。

返回指令不清理 `refs`（`IRETURN`/`ARETURN`）。这是对的：native 返回时 JVM 丢掉整个局部引用帧；删掉返回值会变成悬空引用。set 只服务于方法**中途**不溢出。

一个只做 `aload_0; getfield` 的循环，每次迭代都把 `this` 哈希插入，下一个栈映射再 erase。`this` 从参数进来时已经 insert 过一次。

## 计划

### 原则

只记录**本 native 新创建的局部引用**。已有的引用再拷贝一次不会多占一个 JNI 局部引用槽。

不要 insert：

- `ALOAD` / `ASTORE`（纯拷贝）
- `LOCAL_LOAD_ARG_*`（参数和 `this` 属于调用方帧，删掉还会把调用方的引用弄丢）
- `LDC_STRING` / `LDC_CLASS`（全局引用或已缓存的类）
- `IFNULL` 一类不产生新引用的指令

要 insert（这些 JNI 会新建局部引用）：

- `NewObject` / `New*Array` / `NewObjectArray`
- `GetObjectField` / `GetStaticObjectField`
- `Call*ObjectMethod` / `CallNonvirtual*ObjectMethod` / `CallStatic*ObjectMethod`
- `GetObjectArrayElement`
- `ExceptionOccurred` 得到的 `jthrowable`（`TRYCATCH_START` 里已有 insert，保留）

`DeleteLocalRef` 只作用于这个集合。集合里不再有全局引用，`clear_refs` 可以去掉 `GetObjectRefType`，直接 `DeleteLocalRef`。

### 容器

`unordered_set` 换成 `std::vector<jobject>`，成员改名避免和“去重”混淆，例如 `local_refs`。新引用只 push，不查重。同一引用被 insert 两次再 `DeleteLocalRef` 两次是未定义行为，所以拷贝路径必须不再 insert。

帧边界（`FrameHandler.java:68-91`）：

1. 仍然把**存活**的栈槽和局部槽从待删除列表里去掉（它们还要被后面的字节码使用）。
2. 对其余元素 `DeleteLocalRef`。
3. `clear`。

存活判断继续用 `MethodContext.stack` / `locals` 里 `0` 表示引用（`FrameHandler.java:68-90`）。不要改这个宽度模型。

没有栈映射的方法（`computeFrames` 失败或空帧）今天仍会在每个显式 `FrameNode` 上清理。javac 输出的帧够用。不要在每条指令后清理。

### 另一种做法（本项不采用）

`PushLocalFrame` / `PopLocalFrame` 在帧边界一次性丢掉本帧新建的引用，再把存活槽 `NewLocalRef` 提升出去。能行，但提升漏一个槽就会悬空，而且和现在的“按 FrameNode 删除”相比改动更大。本项保持显式列表，只改“谁入列”和“怎么删”。

### 容量

默认局部引用表是 512。长方法里不经过 `FrameNode` 的新建引用仍会累积。本项不改变“只在帧边界删除”的频率。若某个生成方法在两个帧之间新建超过约 500 个局部引用，仍会溢出。那种方法今天也会溢出。不要为了本项在每条 `invoke` 后都 `clear_refs`，那会把收益还回去。

返回路径保持不删除。对象返回值必须仍在局部引用表里活到 return。

## 语义风险

- 把仍在 `cstack`/`clocal` 里的引用 `DeleteLocalRef` 掉：后续 `getfield`/`invoke` 用悬空局部引用，崩溃或错误结果。
- 把全局引用（intern 字符串、缓存的 `jclass`）放进列表再 `DeleteLocalRef`：字符串池损坏，后面的 `ldc` 读到坏引用。
- 参数上的 `DeleteLocalRef`：调用方还能看到这个参数时行为未定义。所以参数禁止入列。
- `ExceptionOccurred` 的引用若被删掉又被 `Throw`，catch 会看到坏异常。`TRYCATCH_START` 的 insert 与 catch 路径的生命周期要保持现在的样子。

## 测试

### 正确性

目录 `obfuscator/test_data/tests/perf/04-local-refs/`。

| 用例 | 目的 | stdout |
|---|---|---|
| `AloadLoop` | 循环 `aload_0` + 读字段，帧很多 | 累加和 |
| `StringLdc` | 循环 `ldc` 同一字符串并 `equals` | `true` |
| `NewInLoop` | 循环 `new` 对象，字段写入后立刻不用（下一个帧应删掉） | 最后一次的字段值 |
| `ReturnObject` | native 方法返回新建对象，Java 侧读字段 | 字段值 |
| `ReturnArg` | 返回传入的参数，Java 侧仍能用 | 字段值 |
| `CatchException` | catch 里读 `e.getMessage()` 或 `e.getClass().getSimpleName()` | 类型名，例如 `NullPointerException`（类型名稳定，消息不稳定的不要打） |
| `ManyNew` | 一个方法里连续 `new` 超过 600 个对象并丢掉，中间有循环帧 | 不抛 `local reference table overflow`，打印 `ok` |
| `ArrayOfObjects` | `aaload` / `aastore` 往返 | 读回的值 |

`ReturnArg` 专门防“参数被 insert 然后在第一个帧上删掉”。

`StringLdc` 专门防“全局引用被 DeleteLocalRef”。循环次数要大（例如 100_000），一次删掉不一定立刻爆。

`ManyNew` 专门防容量回退。对象要真正被 JNI `NewObject` 创建（转译后的 `new`），不能是 Java 侧创建再传进来。

现有使用字符串、异常、返回对象的 ClassicTest 全跑一遍。

### 生成代码检查

`AloadLoop`：

- `ALOAD` 对应行没有 `refs.insert` / `local_refs.push_back`
- 参数加载没有 insert
- `GetIntField` 若返回的是 int，不应 push 引用
- 帧边界仍有清理调用

`NewInLoop`：

- `NewObject` 之后有 push
- 清理函数里没有 `GetObjectRefType`

`ReturnObject`：

- return 之前没有对返回槽的 `DeleteLocalRef`

### 性能

`AloadLoop` 预热后计时。stdout 是校验和，stderr 是耗时。

期望：对象循环相对改前下降，profile 里 `unordered_set` / `GetObjectRefType` 不应再出现在热路径。若 `GetObjectField` 仍主导，说明本项已生效，剩余成本在字段 JNI（第 2 项）。

### 失败时看什么

- `JNI ERROR: local reference table overflow`：两个帧之间新建太多，或帧边界没删。
- 段错误 / `JNI ERROR: accessed stale`：删了还在用的引用，或删了全局引用。
- 返回值变 null：返回路径误删。
