# 1. 缓存定义类与 ClassLoader，去掉每次调用的前奏

优先级最高。短方法（getter、简单调用）的时间几乎全耗在进入 native 之后、字节码还没跑之前的三次 Java 上调。

## 现状

`MethodProcessor.processMethod` 给每个转译方法生成同一段前奏（`obfuscator/src/main/java/by/radioegor146/MethodProcessor.java:163-181`）：

实例方法：

1. `utils::get_class_from_object` → `Object.getClass()`（`native_jvm.cpp:315-320`）
2. `utils::get_classloader_from_class` → `Class.getClassLoader()`（`native_jvm.cpp:323-328`）
3. loader 为 `nullptr` 时 `FatalError`
4. `DeleteLocalRef` 掉运行时类，再用 `utils::find_class_wo_static` 按**声明类名** `loadClass` 一次（`native_jvm.cpp:250-255`）

静态方法跳过 1 和 4，仍做 2 和 3。`clazz` 形参是 JNI 传入的声明类，但 loader 仍每次现查。

`getClassGetter`（`MethodProcessor.java:98-106`）之后所有 `FindClass` / `loadClass` 都用这个局部变量 `classloader`。类缓存、字段、调用、`NEW`、`checkcast` 都依赖它。

隐藏代理（接口方法、`<clinit>`）还会在前奏之前 `DeleteLocalRef(ignored_hidden)`（`MethodProcessor.java:159-161`）。那是一次本地引用删除，不是 Java 上调，本项不处理。

热路径 getter 因此固定付出 3 次 JNI 上调 + 若干 `ExceptionCheck`，然后才是一次 `Get*Field`。

## 为什么可以拿掉

`RegisterNatives` 发生在 `<clinit>` 里，且在真正的 `<clinit>` 体之前：

1. Java `<clinit>` 调 `Loader.registerNativesForClass`
2. 进入 `ClassSourceBuilder.registerMethods` 生成的 `__ngen_register_methods(JNIEnv *env, jclass clazz)`（`ClassSourceBuilder.java:84-105`）
3. 之后才跑隐藏的 `__ngen_special_clinit_*`

此时 `clazz` 就是正在初始化的声明类。JVM 保证 `<clinit>` happens-before 该类任何方法被其它线程使用，所以在 `registerMethods` 里写入的全局引用，方法体可以无锁读。

接口方法本身不是 native。真正的 native 挂在 hidden 类上，`RegisterNatives` 收到的是 hidden 类（`ClassSourceBuilder.java:115-119`）。hidden 类与接口在同一个输出 jar、同一个 loader，缓存 hidden 类的 loader 等价。

## 计划

### 生成的缓存

在 `ClassSourceBuilder.addHeader`（`ClassSourceBuilder.java:49-60`）里，与 `cclasses[]` 并列增加每个编译单元一份：

```cpp
jobject cclassloader = nullptr;
```

在 `__ngen_register_methods` 末尾（`RegisterNatives` 成功之后）写一次：

```cpp
if (jobject loader = utils::get_classloader_from_class(env, clazz)) {
    cclassloader = env->NewGlobalRef(loader);
    env->DeleteLocalRef(loader);
}
```

不要在注册时 `FatalError`。注册失败会让 `<clinit>` 失败，比“进方法再失败”更难查。空 loader 留到方法入口，行为与现在一致。

每个类 cpp 各自一份 `cclassloader`。方法只使用本编译单元的那份，也就是**声明类的定义 loader**，不是接收者的运行时类 loader。

### 方法前奏

删掉 `MethodProcessor.java:163-181` 的 `get_class_from_object` / `get_classloader_from_class` / `find_class_wo_static` 三段。换成：

```cpp
jobject classloader = cclassloader;
if (classloader == nullptr) {
    env->FatalError(...);
    return (rettype) 0;
}
if (obj == nullptr) {
    utils::throw_re(env, "java/lang/NullPointerException", "null receiver", 0);
    return (rettype) 0;
}
```

要点：

- 保留局部名 `classloader`。`getClassGetter` 不用改。
- 静态方法没有 `obj`，不要生成 null 检查。
- **必须保留实例方法的 null 检查。** JNI 对 null 接收者不会自动抛 NPE。现在的 NPE 来自 `Object.getClass()`。拿掉之后，不碰 `this` 的实例方法（空方法、只读参数）会从“抛 NPE”变成“正常返回”。检查用指针比较，不要再调 `getClass`。
- NPE 的文案会从 JVM 的 `getClass` 消息变成 `throw_re` 的 `"\"null receiver\" on 0"`。异常类型仍是 `NullPointerException`。现有 ClassicTest 只比 stdout；若有用例把 `getMessage()` 打到 stdout，会失败，需要改用例或接受新文案。类型不能变。
- `clazz` 形参留着。JNI 方法签名不能少它。前奏不再使用它。
- loader 为 null 时继续 `FatalError`，字符串仍走 `StringPool`（现在是 `"classloader == null"`）。不要改成对 bootstrap 类静默 `FindClass`，那是行为变化，单独开项。

### 不在本项做的事

- 不改 `cclasses[]` 的 weak global / `IsSameObject`（见 `02`）。
- 不把 hidden 的 `DeleteLocalRef(ignored_hidden)` 算进收益。
- 不缓存“接收者运行时类”。虚调用解析仍由后面的 `Call*Method` 完成。

## 语义风险

定义 loader 与接收者 loader 不同时，方法体内的 `loadClass` 会走父 loader，而不是子 loader。典型场景：`Base` 由父 loader 加载并被转译，`Sub extends Base` 由子 loader 加载，`Base` 的 native 方法里 `NEW` 一个只在子 loader 可见的类。现在用 `Sub.class.getClassLoader()`，改完用 `Base` 的 loader，会抛 `NoClassDefFoundError`。

普通单 loader 应用（一个 jar、一个 `URLClassLoader` 或 AppClassLoader）没有这个差异。

null 接收者仍抛 NPE，但消息文本变了。空 loader 仍是 `FatalError`。

## 测试

### 怎么跑

正确性走现有 `ClassicTest`：`javac` → jar → ideal `java -jar` → 每个 `Platform` 转译、cmake、再跑，**只比较 stdout**（`ClassicTest.java:186`）。耗时打到 **stderr**，否则 ideal 与 native 的纳秒数不同，测试必红。

单目录：

1. 在 `TestsGenerator.java:20` 把 `ALLOWED_TESTS` 设成该目录名（`null` 表示全量，全量要 cmake × 3 个平台，慢）。
2. `gradlew.bat :obfuscator:test --tests by.radioegor146.TestsGenerator`

手工看生成代码：

```text
java -jar obfuscator/build/libs/obfuscator.jar in.jar out -p hotspot --plain-lib-name
```

看 `out/cpp/output/*.cpp` 里方法体。

### 正确性用例

放在 `obfuscator/test_data/tests/perf/01-loader/`。每个目录一个 `main`，stdout 只打结果。

| 用例 | 行为 | 通过标准 |
|---|---|---|
| `Getter` | 实例字段读、写、再读 | stdout 与 ideal 一致；三个平台都过 |
| `StaticCall` | 静态方法读静态字段 | 同上 |
| `NullReceiver` | `Foo f = null; try { f.get(); } catch (NullPointerException e) { print "npe"; }` | stdout 是 `npe`。不要打印 `getMessage()` |
| `ClinitThenCall` | 静态块打印 `init`，`main` 再调静态方法打印 `run` | 顺序是 `init` 然后 `run`，且 `init` 只出现一次 |
| `InterfaceProxy` | 接口默认/静态方法若现有用例已覆盖则复用；否则一个接口实例方法打印接收者字段 | stdout 一致，证明 hidden 类的 `cclassloader` 可用 |
| 现有全量 | `ALLOWED_TESTS = null` 跑一遍 | 无回归 |

`NullReceiver` 若 ideal 的 NPE 消息被打印，native 会对不上。断言只打印固定标记。

### 生成代码检查

`Getter` 的 native 函数体内不应再出现：

- `get_class_from_object`
- `get_classloader_from_class`
- `find_class_wo_static`（方法前奏里；其它类的缓存未命中路径仍可以有）

`__ngen_register_methods` 里应有且仅有一处 `get_classloader_from_class` 和 `NewGlobalRef`。

### 性能

`Bench`：实例 getter，预热 100_000 次，再计时 5_000_000 次，结果累加打印到 stdout（防止被优化掉），耗时 `System.nanoTime()` 打到 stderr。ideal 与 native 各跑一次。

期望：native 相对改前的耗时明显下降（短 getter 的 JNI 次数从大约 4–6 次降到 1 次 `Get*Field` + 异常检查）。不要把“超过 ideal”当通过条件，ideal 有 JIT，native 仍有 JNI。

### 不进 CI 的手工项

两个 `URLClassLoader`，父加载 `Base`，子加载 `Sub` 和仅子可见的 `OnlyChild`。`Base.m()` 里 `new OnlyChild()`。改前应成功，改后应 `NoClassDefFoundError`。记录这个差异，不把它当回归修掉，除非以后改回“按接收者取 loader”。
