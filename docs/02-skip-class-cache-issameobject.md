# 2. 类缓存命中后不再 IsSameObject

优先级第二。字段、调用、`new`、`checkcast`、`instanceof`、`ldc` Class 每次都先探测类缓存。缓存命中时探测本身仍是一次 JNI。

## 现状

下面五处是同一段复制代码，命中路径也要调用 `IsSameObject`：

| 位置 | 指令 |
|---|---|
| `FieldHandler.java:25` | `getfield` / `putfield` / `getstatic` / `putstatic` |
| `MethodHandler.java:185` | `invoke*` |
| `TypeHandler.java:16` | `new` / `anewarray` / `checkcast` / `instanceof` |
| `LdcHandler.java:62` | `ldc` Class |
| `MethodProcessor.java:196` | try-catch 异常类，方法入口预解析 |

生成代码形态：

```cpp
if (!cclasses[i] || env->IsSameObject(cclasses[i], NULL)) {
    cclasses_mtx[i].lock();
    if (!cclasses[i] || env->IsSameObject(cclasses[i], NULL)) {
        if (jclass clazz = /* find_class_wo_static 或 FindClass */) {
            cclasses[i] = (jclass) env->NewWeakGlobalRef(clazz);
            env->DeleteLocalRef(clazz);
        }
    }
    cclasses_mtx[i].unlock();
    /* ExceptionCheck */
}
if (!cfields[j]) {
    cfields[j] = env->GetFieldID(cclasses[i], ...);
}
```

`cclasses[]` 是 **weak** global（`ClassSourceBuilder.java:52-54` 只分配数组，填充在各 handler）。Weak ref 被 GC 清掉后变成 null，所以用 `IsSameObject(..., NULL)` 判断“还活着”。`!cclasses[i]` 在命中时为假，于是**每次**都落到 `IsSameObject`。

`jfieldID` / `jmethodID` 在类卸载前一直有效。类一旦卸载，继续用旧 id 是悬空。Weak ref 就是为了跟着卸载走。混淆后的应用几乎不卸载这些类，探测成本却打在每次访问上。

`GetFieldID` 只在 `cfields[j] == nullptr` 时做。类探测却在 id 填好之后继续做。实例字段和 `invokevirtual` 拿到 id 之后根本不再需要 `jclass`。

## 计划

### 改成强全局引用

五处统一改成 `NewGlobalRef`，命中条件只留指针：

```cpp
if (!cclasses[i]) {
    cclasses_mtx[i].lock();
    if (!cclasses[i]) {
        if (jclass clazz = /* getter */) {
            cclasses[i] = (jclass) env->NewGlobalRef(clazz);
            env->DeleteLocalRef(clazz);
        }
    }
    cclasses_mtx[i].unlock();
    /* 仅这段慢路径保留 ExceptionCheck */
}
```

抽一个发射函数，例如 `MethodProcessor.emitClassResolve(context, classId, desc)`，五处都调它。现在字符串复制了五份，之后改漏一处会行为不一致。

数组类（`desc.startsWith("[")`）仍走 `FindClass`（`getClassGetter`，`MethodProcessor.java:99-100`）。非数组仍走 `find_class_wo_static`。这一点不变。

### 哪些指令在 id 就绪后可以连类指针都不读

| 指令 | 命中后 |
|---|---|
| `getfield` / `putfield` | 只需要 `cfields[j]`。`cfields[j]` 非空则整段类解析跳过 |
| `invokevirtual` / `invokeinterface` | 只需要 `cmethods[j]`。同上 |
| `getstatic` / `putstatic` / `invokestatic` / `invokespecial` | JNI 要 `jclass`。类指针必须在，但不要 `IsSameObject` |
| `new` / `checkcast` / `instanceof` / `anewarray` / `ldc` Class | 同上，要用 `jclass` |
| try-catch 的 `IsInstanceOf` | 要用异常类的 `jclass` |

实例字段/虚调用的快路径变成：

```cpp
if (!cfields[j]) {
    /* 慢路径：解析 cclasses[i]，再 GetFieldID */
}
env->GetIntField(obj, cfields[j]);
```

静态访问的快路径变成 `if (!cclasses[i]) { 慢路径 }`，然后直接用 `cclasses[i]`。

### 发布

现在的快路径已经在无锁下读 `cclasses[i]`。改成只判断空指针，竞态级别与现在相同。慢路径保持双检锁。x86 上对齐指针写是原子的；不要为了本项引入 `std::atomic`，除非要同时修现在的数据竞争。JNI 调用仍必须发生在持有该线程 `JNIEnv` 的线程上，这一点现有锁已经保证（锁内才调 JNI）。

### 明确放弃的行为

强全局引用会钉住这些类，类卸载不再发生。这是本项的取舍，不要再用 weak ref 并在命中路径调用 `IsSameObject`。

不要在 `cfields[j]` 已非空时仍去刷新 `jclass`。类卸载后旧 `jfieldID` 会悬空；强引用就是用来阻止卸载的。

## 语义风险

- 被转译代码引用过的类活到进程结束。依赖“丢弃 ClassLoader 后类被卸载、静态状态重置”的热部署会失效。
- 解析失败路径不变：`find_class_wo_static` 仍把失败包成 `NoClassDefFoundError`（`native_jvm.cpp:256-285`），慢路径仍 `ExceptionCheck`。
- 快路径不再调用 JNI，因此不会把“类已被卸载”变成可捕获异常，而是根本不卸载。

## 测试

### 正确性

目录 `obfuscator/test_data/tests/perf/02-class-cache/`。`ALLOWED_TESTS` 只开这个目录，再跑一次相关的现有字段/调用/类型用例。

| 用例 | 覆盖 | stdout |
|---|---|---|
| `GetField` | 同一字段读多次，确认慢路径只填一次、快路径值正确 | 字段值 |
| `GetStatic` | 静态字段，确认仍持有 `jclass` | 字段值 |
| `CallVirtual` / `CallStatic` / `CallSpecial` | 三种调用各返回常量 | 三个返回值 |
| `NewCheckcast` | `new`、`instanceof`、成功的 `checkcast`、失败的 `checkcast` 打印 `cce` | 固定标记，不打印异常消息 |
| `LdcClass` | `Foo.class.getName()` | 类名 |
| `MissingClass` | 反射或手写字节码引用不存在的类（没有 Krakatau 时可用一个只在运行时才 `Class.forName` 的路径，或 `.j`） | 打印 `ncde`，进程不要崩 |
| `TwoThreads` | 两线程同时第一次打同一个 `getfield` | 值一致，无崩溃 |

`TwoThreads` 的理想 JVM 与 native 都要能跑完。不要把线程调度顺序打进 stdout。

### 生成代码检查

对 `GetField` 的热指令（缓存判断之后的那次 `GetIntField`）：

- 快路径片段里没有 `IsSameObject`
- 没有 `NewWeakGlobalRef`，慢路径是 `NewGlobalRef`
- `cfields[j]` 非空时不再调用 `GetFieldID`

`GetStatic` 的快路径可以读 `cclasses[i]`，但不能有 `IsSameObject`。

五处发射点都要抽查一个指令，防止漏改 `LdcHandler` 或 try-catch 预解析。

### 性能

`Bench`：循环 `obj.field`（int）5_000_000 次。预热另算。累加和进 stdout，耗时进 stderr。

期望：相对改前，每次访问少一次 `IsSameObject`。与第 1 项叠在一起时，getter 的稳定态应只剩 `Get*Field` 和紧随的 `ExceptionCheck`。

### 不进 CI

一次性 ClassLoader 加载转译类，调用一次，丢弃 loader，强制 GC。改前 weak ref 可能被清；改后类仍可达（可用堆直方图或 `jcmd GC.class_histogram` 看类还在）。这是预期，不是失败。
