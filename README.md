# EzJ2C

Java `.class` 转 `.cpp`（JNI）工具：把 Java 方法体转译为原生 C++，而非传统意义上的混淆。

> 本项目是 [radioegor146/native-obfuscator](https://github.com/radioegor146/native-obfuscator) 的 fork，当前工具版本 `3.5.4r`。
> 定位：`native-obfuscator` 的中文维护分支，持续同步上游并做本地化文档与易用性改进。

## 目录

- [特性与限制](#特性与限制)
- [与上游的关系](#与上游的关系)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [用法详解](#用法详解)
- [自行构建本工具](#自行构建本工具)
- [测试](#测试)
- [许可证](#许可证)
- [反馈与致谢](#反馈与致谢)

## 特性与限制

- 完整支持 Java 8；Java 9+ 与 Android 为实验性支持。
- 转译后输出 jar 包体版本强制为 52（Java 8）。
- 构造器 `<init>` 不转译；`INVOKEDYNAMIC` 会在预处理阶段改写，不会残留到 C++ 生成阶段。
- 接口方法不能直接标 `native`，工具会自动生成隐藏代理类。
- `<clinit>` 会优先执行原生方法注册；缺失时会自动注入空 `<clinit>`。
- 性能警告：全量转译会显著拖慢运行速度，建议配合黑白名单只转译关键类/方法（如不要直接转译整个 Minecraft `.jar`）。
- 它不是混淆器，只是把字节码搬到 native 层；如需防护建议再叠加 VMProtect / Themida / obfuscator-llvm（clang）等方案。

## 与上游的关系

- 上游：`radioegor146/native-obfuscator`（GPL-3.0）。
- 本分支：`J3y0r/EzJ2C`，保留原有流水线（预处理 → `MethodProcessor` 生成 C++ → 输出 jar + `cpp/` 工程），主要工作为中文文档、默认行为说明补齐与后续功能迭代。
- 上游更新可通过 merge/rebase 同步；提交 PR 时请注明基于的上游 commit。

## 环境要求

运行本工具需要：

1. JDK 8
   - Windows：建议 Oracle JDK 8（官网下载，可能需要登录）。
   - Linux / macOS：按发行版自行安装 JDK 8（`apt/yum/brew install`）。
2. CMake（≥ 3.8）
   - Windows：从 [CMake 官网](https://cmake.org/download/) 下载最新版。
   - Linux / macOS：按发行版安装（`apt/yum/brew install cmake`）。
3. C/C++ 编译器工具链（需支持 C++17，GCC ≥ 6）
   - Windows：免费版 Visual Studio 并勾选 Visual C++，或自行配置 mingw。
   - Linux / macOS：按发行版安装 `g++`。

## 快速开始

1. 转译：
   ```bash
   java -jar obfuscator.jar <input jar> <output directory>
   ```
2. 进入产物 `cpp` 目录执行 `cmake .` 生成工程。
3. 按需修改生成的 `.cpp`。
4. 编译原生库：
   ```bash
   cmake --build . --config Release
   ```
   产物在 `cpp/build/lib/`。
5. 把 `.dll` / `.so` / `.dylib` 按工具 `stdout` 打印的目录（如默认 `native0/`）放入对应位置，文件命名形如：
   ```
   x64-windows.dll
   x64-linux.so
   x86-windows.dll
   x64-macos.dylib
   arm64-linux.so
   arm64-windows.dll
   ```
6. 运行产物 jar：
   ```bash
   java -jar <output jar>
   ```

## 用法详解

完整 CLI：

```
Usage: native-obfuscator [-ahiV] [--debug] [-b=<blackListFile>]
                         [--custom-lib-dir=<customLibraryDirectory>]
                         [-l=<librariesDirectory>] [-p=<platform>]
                         [--plain-lib-name=<libraryName>] [-w=<whiteListFile>]
                         <jarFile> <outputDirectory>
Transpiles .jar file into .cpp files and generates output .jar file
      <jarFile>           待转译的输入 jar
      <outputDirectory>   输出目录（C++ 与新 jar 生成位置，禁止与输入 jar 父目录相同）
  -a, --annotations       启用注解过滤
  -b, --black-list=<blackListFile>
                          黑名单文件
      --custom-lib-dir=<customLibraryDirectory>
                          LoaderUnpack 的自定义库目录
      --debug             生成调试用（不可执行）jar
  -h, --help              显示帮助并退出
  -i, --[no-]inline       将 Object.getClass 与 String.equals/isEmpty/length 内联为 JNI（默认开）
  -l, --libraries=<librariesDirectory>
                          依赖库目录（可选但推荐）
  -p, --platform=<platform>
                          目标平台：hotspot / std_java / android
      --plain-lib-name=<libraryName>
                          LoaderPlain 用的原生库名
  -V, --version           显示版本并退出
  -w, --white-list=<whiteListFile>
                          白名单文件
```

### 目标平台（`-p`）

| 取值 | 说明 |
|---|---|
| `hotspot`（默认） | 使用 HotSpot JVM 内部机制，与大多数混淆器/堆栈检查兼容性最好 |
| `std_java` | 只用各 JVM 共有的少量内部机制，可移植性更好 |
| `android` | 面向 Android：不用 JVM 内部机制，不用 `DefineClass` 隐藏方法，部分依赖堆栈的字符串/名称混淆会失效 |

### 注解过滤（`-a`）

1. 在业务工程引入注解库（另需配置 [JitPack](https://jitpack.io)）：
   ```
   com.github.radioegor146.native-obfuscator:annotations:master-SNAPSHOT
   ```
2. 类/方法标 `@Native` 表示纳入转译；在已标 `@Native` 的类内，方法标 `@NotNative` 表示排除。
3. 优先级：黑名单 > 白名单 > 注解。

### 黑白名单（`-w` / `-b`）

每行一条，使用内部类名与方法描述符，方法名与描述符之间用 `!` 分隔：

```
mypackage/myotherpackage/Class1
mypackage/myotherpackage/Class1#doSomething!()V
mypackage/myotherpackage/Class1$SubClass#doOther!(I)V
```

支持通配符：

```
mypackage/myotherpackage/*
mypackage/myotherpackagewithnested/**
mypackage/myotherpackage/*/Class1
mypackage/myotherpackagewithnested/**/Class1
mypackage/myotherpackage/Class*
```

- `*`：匹配单层包/类名片段（以 `/` 分隔的一段）。
- `**`：匹配多层包/类名片段。

### 原生库装配

- `--plain-lib-name`：jar 与原生库分开发布、或 Android 场景下，指定运行时 `System.loadLibrary` 的库名。
- `--custom-lib-dir`：指定 jar 内存放原生库的自定义目录。
- 默认（ traditions LoaderUnpack）：转译完成后按 `stdout` 打印的 `nativeN/` 目录，把各平台产物放进去再分发。

## 自行构建本工具

```bash
./gradlew assemble
```

Windows：

```bash
gradlew.bat assemble
```

说明：`assemble` 会跳过测试直接打胖 jar，产物为 `obfuscator/build/libs/obfuscator.jar`。源码/目标兼容 Java 8，Gradle Wrapper 为 9.3.1。

## 测试

部分用例依赖 [Krakatau](https://github.com/Storyyeller/Krakatau) 的 `krak2`（用于 `.j` 用例），需先加入 `PATH`。

```bash
./gradlew build
```

- 完整套件：`javac` → 可选 `krak2 asm` → 打 jar → 先跑理想输出（`-Dseed=1337`）→ 对每种 `Platform` 转译 → cmake 编译 → 对比运行输出。
- 用例来源：[huzpsb/JavaObfuscatorTest](https://github.com/huzpsb/JavaObfuscatorTest)。
- 单测示例：
  ```bash
  ./gradlew :obfuscator:test --tests by.radioegor146.source.StringPoolTest
  ```
- 只跑单个 `ClassicTest` 目录：先在 `TestsGenerator` 中设置 `ALLOWED_TESTS`，再执行 `./gradlew :obfuscator:test`。

## 许可证

本工具自身遵循 [GPL-3.0](LICENSE)。

**输出例外（Output Exception）：** 工具向你的输出目录生成的运行时文件 —— `native_jvm.*`、`string_pool.hpp`、生成的 Loader 类、`cppsnippets.properties` 代码片段 —— 可按你选择的任意条款链接、嵌入、编译与分发，不受 GPL-3.0 第 5(c) 条限制。完整条款见 [LICENSE](LICENSE) 尾部。

## 反馈与致谢

- 上游原作者：[radioegor146](https://re146.dev)，原项目问题可先查阅上游 issue。
- 本分支问题：请在 `J3y0r/EzJ2C` 提 issue，并附上输入 jar 结构、复现命令、JDK/CMake/编译器版本与完整报错。
- 感谢上游 `native-obfuscator` 与 `huzpsb/JavaObfuscatorTest` 测试集。

---

### Stargazers over time

[![Stargazers over time](https://starchart.cc/J3y0r/EzJ2C.svg?variant=adaptive)](https://starchart.cc/J3y0r/EzJ2C)
