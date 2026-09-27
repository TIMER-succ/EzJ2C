# 5. 基本类型数组不要一次 JNI 读写一个元素

优先级第五。数值循环若落在 `iaload`/`iastore` 上，每次元素访问都是 `Get/Set*ArrayRegion(..., length=1)`。这块比算术大一个数量级。对象数组（`aaload`/`aastore`）不在本项里改。

## 现状

`cppsnippets.properties`：

| 键 | 行为 |
|---|---|
| `IALOAD` `:42` | null 检查后 `GetIntArrayRegion(..., 1, &slot)` |
| `IASTORE` `:79` | `SetIntArrayRegion(..., 1, ...)` |
| `LALOAD`/`FALOAD`/`DALOAD`/`SALOAD`/`CALOAD` 及对应 `*ASTORE` | 同样是 length=1 的 Region |
| `BALOAD` `:62` | `utils::baload` |
| `BASTORE` `:99` | `utils::bastore` |

`baload`/`bastore`（`native_jvm.cpp:299-312`）先 `IsInstanceOf(array, boolean_array_class)`，再决定 `boolean` 还是 `byte` 的 Region。`boolean[]` 与 `byte[]` 在字节码里都是 `baload`，但 boolean 只保留 0/1。类型不明时这个判断必须留着。

越界和 null 今天由 Region 或前面的 `throw_re` 产生 `ArrayIndexOutOfBoundsException` / `NullPointerException`，然后 `$trycatchhandler`。手写下标必须保持这两种异常类型。

`Get*ArrayElements` 可能 pin 原数组，也可能拷贝。pin 住却不 `Release*`，堆会被钉住；拷贝了却不 `Release*`，写入丢失。异常路径也要 Release。

## 计划

分两步。第一步收益中等、风险低，应先落地。第二步才是循环 pin。

### 第一步：已知组件类型时去掉 boolean 判断

栈映射或 `NEWARRAY` 的操作数已经知道是 `byte[]`（`T_BYTE`）还是 `boolean[]`（`T_BOOLEAN`）时：

- `byte[]` 直接 `GetByteArrayRegion` / `SetByteArrayRegion`，值按 `jbyte` 符号扩展成 `jint`
- `boolean[]` 直接 `GetBooleanArrayRegion` / `SetBooleanArrayRegion`，写入前截成 `0/1`（`value & 1`）

类型来源：

- `NEWARRAY` 的操作数（`InsnHandler` / 片段 `NEWARRAY_4` 是 boolean，`NEWARRAY_8` 是 byte）
- 局部变量的帧类型是 `[B` 或 `[Z`
- 字段描述符是 `[B` 或 `[Z`，且 `getfield` 的结果只喂给紧随的 `baload`

类型是 `Object`、`Cloneable`、或从 `aaload` 出来的引用时，仍走 `utils::baload` / `utils::bastore`。不要猜测。

这一步仍是每次一个元素的 Region，只是去掉 `IsInstanceOf`。

### 第二步：循环 pin

只对同时满足下面条件的循环做。任一条件失败就退回 Region，不要生成半套 pin。

1. 数组是基本类型：`int[]` `long[]` `float[]` `double[]` `short[]` `char[]` `byte[]` `boolean[]`。不是 `Object[]`。
2. 数组引用在整个循环里停在同一个局部槽，循环内不对这个槽 `astore`。
3. 循环体内没有 `invoke*`、`invokedynamic`、`monitorenter`、`new`、`athrow`，也没有把这个数组传给任何指令以外的 JNI。
4. 循环内对该数组只有 `*aload` / `*astore` / `arraylength`，加上与数组无关的算术和分支。
5. 所有出口（正常跳出、`return`、会抛的指令、catch 边）都能数清楚。

生成形态（以 `int[]` 为例）：

```cpp
jint len = env->GetArrayLength((jintArray) clocalA.l);
jint *data = env->GetIntArrayElements((jintArray) clocalA.l, nullptr);
if (data == nullptr) { /* OOM 已挂起 */ $trycatchhandler }
// 循环内：
if (clocalA.l == nullptr) throw NPE;
if ((jint) index < 0 || (jint) index >= len) throw AIOOBE;
sum += data[index];
// 每一条出口，含 throw 之前：
env->ReleaseIntArrayElements((jintArray) clocalA.l, data, 0);
```

规则：

- `GetArrayLength` 一次，不要在循环里再调。
- 下标用 `jint`。负数必须先按有符号比较，不能转成 `size_t` 再比。
- null 检查保持在 `throw_re` + `$trycatchhandler`，与现在的片段一致。
- 有 `*astore` 时 Release 的 mode 用 `0`（写回）。只有 `*aload` 时可以用 `JNI_ABORT`。不确定就用 `0`。
- 异常路径在 `throw_re` 或 JNI 失败之后、离开循环之前 Release。漏一条出口就是 bug。
- 循环内若解析失败要进 catch，catch 在方法末尾的 `L_CATCH_N`（`MethodProcessor.java:267`）。pin 的指针是 C++ 局部变量，跳到 `L_CATCH` 时不会自动 Release。要么在每个可能抛的点先 Release 再进入原来的 handler，要么禁止在“体内仍可能抛”的循环上 pin。第一版采用后者：循环里除了本项自己插入的 NPE/AIOOBE 之外还有别的会抛指令，就不 pin。
- 不要用 `GetPrimitiveArrayCritical`。它禁 GC，循环里一旦不小心调了 JNI 就是未定义行为。`Get*ArrayElements` 足够。
- `boolean[]` pin 之后写入仍要 `& 1`。`byte[]` 不要做这个截断。
- 多维数组只 pin 最内层被循环访问的那一层。外层 `aaload` 仍是 JNI。

实现位置建议：不要把 pin 逻辑塞进单条 `IALOAD` 片段。在 `MethodProcessor` 发指令之前扫循环（回边 label → 头 label），能 pin 就整段发 C++，这些指令的 handler 跳过。不能 pin 的指令仍走原来的片段。

### 不 pin 的情况

- 数组逃逸：`putfield`、`aastore`、作为 `invoke` 参数
- 循环里调用任何方法，包括别的转译方法（对方可能写同一数组）
- 两个线程同时写同一数组：Region 与 pin 都没有 volatile 语义，不额外承诺
- 对象数组

## 语义风险

- 漏 Release：写入丢失或数组被长期 pin，进程变慢、堆涨。
- 出口漏 Release 后再次 pin 同一数组：未定义。
- `boolean[]` 未截断：把 `2` 写进去，ideal 读回 `1`，native 读回 `2`。
- 下标负数没查：native 越界写 C++ 堆，而 ideal 抛 `ArrayIndexOutOfBoundsException`。
- pin 期间另一线程 `System.arraycopy` 同一数组：允许与今天一样的数据竞争，但不要在单线程测试里失败。
- 长度在 pin 之后被别的线程改掉：单线程不会发生。本项不处理。

## 测试

### 正确性

目录 `obfuscator/test_data/tests/perf/05-arrays/`。stdout 只打结果。

| 用例 | 目的 |
|---|---|
| `IntSum` | `int[]` 求和，循环无调用。校验和 |
| `IntFill` | `iastore` 填 0..n-1，再求和。证明 Release mode 0 把写入拷回 |
| `Bounds` | 下标 `-1` 和 `length`。打印 `aioobe`，类型不要用消息文本 |
| `NullArray` | 数组局部为 null。打印 `npe` |
| `BooleanTrunc` | `boolean[]` 写入 `2`，读回必须是 `1` |
| `ByteSign` | `byte[]` 写入 `-1`，读回 `-1`（不是 `255`，也不是 `1`） |
| `CharShort` | `char[]` 写入 `65535` 读回 `65535`；`short[]` 写入 `-1` 读回 `-1` |
| `LongFloatDouble` | 各一个小循环，打印若干精确值。float/double 用 `Float.floatToIntBits` / `Double.doubleToLongBits` 打成整数，避免格式差异 |
| `CallInLoop` | 循环里调用方法读数组。必须走 Region 回退，结果仍对。生成代码里这个循环不应出现 `GetIntArrayElements` |
| `Escape` | 循环里把数组存进字段。不 pin，结果仍对 |
| `TryCatch` | 循环外套 try，中途越界，catch 后数组前半段的 `iastore` 必须可见 | 
| `Multi` | `int[][]` 只对内层求和 |

三个 `Platform` 都跑。`TryCatch` 是 Release 漏路径的回归测试。

### 生成代码检查

`IntSum`：有 `GetIntArrayElements` 和配对的 `ReleaseIntArrayElements`，循环体内没有 `GetIntArrayRegion`。

`CallInLoop`：没有 `GetIntArrayElements`，仍是 `GetIntArrayRegion`。

`BooleanTrunc`：没有 `IsInstanceOf`；存储前有截断。若分析失败退回 `bastore`，测试仍须读回 `1`，但应视为第一步未完成，检查生成代码时算失败。

每条 `Get*ArrayElements` 用文本搜索配对 `Release*ArrayElements`，数量相等，且 `return` / `throw_re` 之前都有 Release。

### 性能

`IntSum`，长度 10_000，重复足够多次使总元素访问约 5e7。校验和进 stdout，耗时进 stderr。ideal 与 native 都跑。

期望：native 相对“每次 Region”下降一个数量级以内的大头（每次访问从一次 JNI 变成一次 C++ 下标）。不要要求超过 JIT 后的 ideal。

`CallInLoop` 的耗时不应被本项误伤：它必须仍能跑完，耗时可以和改前同一量级。

### 失败时看什么

- 和 ideal 的校验和不同：写回模式错了，或 boolean 截断错了。
- 越界没有异常而崩溃：下标检查被优化掉或比较用了无符号。
- 进程内存涨：某条出口没有 Release。把循环次数加大到 100_000 再看。
