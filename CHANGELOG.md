# Changelog

## jparsec：backtracking、longest 选择与错误位置的属性测试（新增，未改产品语义）

本次只新增测试与一个开发期变异校验脚本，**不修改任何 `src/main` 产品代码**。

- 新增 `jparsec/src/test/java/org/jparsec/BacktrackingPropertyTest.java`
  （6 个测试方法，位于 `org.jparsec` 包内，以便触达包私有的状态机）。
- 新增 `tools/mutation-check.py`：对三处产品保护点逐一注入故意变异并验证新测试必失败。

### 要验证的语义切面

围绕 `Parsers.or`、`Parsers.longest`、`Parser.atomic()`、`Parser.peek()`（lookahead）
在“共享前缀、可空分支、嵌套回溯、多个失败点”下的四类可观察契约：

1. 选择结果：`or` 是有序首胜（失败即回溯，无视部分匹配）；`longest` 选消费最多的分支，
   等长时偏向第一个。
2. 位置回滚：`or`/`longest` 在尝试后续分支前恢复 `(at, step, result)`；
   `atomic()` 在失败时自行把物理/逻辑位置恢复到入口；`peek()` 在成功时回退位置，
   失败时不触碰位置。
3. 错误合并：`ParseContext.raise` 遵循“更远位置的错误覆盖更近的错误；同一位置的
   `MISSING/EXPECTING/DELIMITING` 错误合并 expected 集合”。
4. 双层位置一致：字符级与 token 级必须映射回同一个原始源位置；token 级通过
   `ParserState.toIndex`（token 自身 `index()`，越界折叠到固定 endIndex）把逻辑位置
   映射到物理索引，nested 返回后再由 `ParseContext.copyErrorFrom` 按物理索引回拷。

### 实现选择（为什么这样写）

- **独立参考解释器而不是“另一个 jparsec”**：`BacktrackingPropertyTest.Ref` 是一个手写的
  小型步进抽象机，直接编码上面四条规则（物理位置 furthest 错误槽、`or`/`longest` 的
  快照-恢复、`atomic` 的失败回滚与成功 step 压缩、`peek` 的成功回退）。同一套 AST
  （`Term/Empty/Seq/Or/Longest/Atomic/Peek`）既被参考机解释，也被编译成真实的
  jparsec `Parser`，两级各自跑一遍再逐字段比对：成功/失败、消费前沿、最远错误索引、
  expected 集合。这样属性不是“自证”，而是对照一份明确的可执行规约。
- **固定随机种子 + 结构化 shrink**：`generatedGrammarsMatchReferenceInterpreter` 用固定种子
  生成 1200 个深度受限的小 grammar/输入对（可复现，无随机 sleep/网络）。一旦不一致，
  `shrink` 先删输入尾部、再删单个符号、再把语法节点替换成 `eps`/子节点，产出最小可读反例，
  失败信息同时打印 char/token 两级的参考结果与真实结果，保留可诊断上下文。
- **边界矩阵（显式真值表）**：`boundaryMatrix` 手工枚举 17 行关键场景，每行都先与参考机
  交叉校验、再断言具体的成功/失败、索引与 expected 集合，避免只依赖随机生成。
- **分支置换不变性**：`swappingIrrelevantAlternativesDoesNotChangeTheOutcome` 验证
  （a）`longest` 在任意分支排列下结果不变；（b）`or` 在等长等价分支/全失败分支上，
  调换无关分支不改变消费前沿与错误（含 expected 集合）。
- **token 级直接构造状态**：`runTokens` 直接 `new ParserState(...)` 并在其上 `apply`，
  精确复现分词后进入的那台状态机，避开“手工 `constant(tokenList)` 词法分析器空消费、
  外层 char EOF 锚定在 0”这一与被测语义无关的陷阱；token 被故意放在奇数物理偏移上，
  证明 token 序号 ≠ 物理索引而错误仍指向正确源位置。
- **位置回滚的黑盒可观察化**：产品内建的 `or`/`longest` 自己会恢复位置，会掩盖
  `atomic` 的回滚。`atomicRollsBackThePhysicalFrontierForANonRestoringFollower`
  使用包内可见的 `ScannerState`，构造“尝试后不自行恢复位置、继续读取当前前沿”的
  测试组合子，直接读出 `atomic` 失败后前沿是 0（无 `atomic` 时为 1），并验证只有
  `atomic` 能让后续解析重新读到开头符号。
- **empty-parser guard**：`manyTerminatesOnAnEmptySuccess` 断言“空成功立即终止重复”
  （带 `@Test(timeout=3000)`），同时覆盖真正消费型重复的正常迭代。

### 原覆盖的空白

既有测试（如 `ParserErrorHandlingTest`、`ParserTest`）以点样例为主，缺少：

- 针对 `or/longest/atomic/peek` 交互的**成体系、可复现的生成式性质**与参考真值对照；
- 对“失败后剩余前沿/最远错误索引/expected 合并集合”三者**同时**逐字段断言的用例，
  尤其跨字符级与 token 级的位置映射；
- “调换无关分支结果不变”的置换不变性用例；
- 让 `atomic` 的**物理位置回滚**（而非错误合并）本身可观察的用例；
- 用变异证明“位置回滚 / 最远错误合并 / 空 parser 守卫”三处实现确实被测试盯住。

### 相邻语义的退化保护

- 错误类型优先级（UNEXPECTED < MISSING < EXPECTING < FAILURE）保持原样，仅用
  mergeable 的 MISSING 级终端错误构造属性，避免把不相关的错误类型策略卷入。
- 未引入新依赖、未改 pom/编译器选项；测试只用 JUnit 4 与包内 API。
- 全部测试保留明确的索引、expected 集合、encountered 等诊断信息，失败即可定位。

### 最危险的反例与对应回归

最危险的反例是：**在共享前缀的有序选择里，一个分支先消费了公共前缀后失败，
若丢掉失败分支的位置回滚，后续分支就会从错误的前沿开始解析——不仅选错结果，
还会污染最远错误与重复构造（`many` 等）导致错位甚至发散。**

- 现象：`or(('a' 'z'), ('a' 'b'))` 解析 `"ax"` 时，第一分支消费了 `'a'` 后在位置 1 失败；
  若不回滚，第二分支会在位置 1 上尝试匹配 `'a'`，整体要么错误失败、要么错误成功，
  且错误索引/expected 集合全部错位。
- 直接回归：`boundaryMatrix` 中 “atomic rewinds partial match before a later branch
  is tried”“non-atomic failure still contributes its furthest error”“nested or merges
  three failure points”，以及生成式属性与
  `atomicRollsBackThePhysicalFrontierForANonRestoringFollower`。

### 故意变异证据（至少一处，实测三处全部被杀死）

运行（仓库根目录，需 JDK 8；脚本每次变异后都会还原产品源码）：

```
python3 tools/mutation-check.py
```

实测结果（日志在 `target/mutation-check/`）：

| 变异 | 位置 | 后果 | 结果 |
| --- | --- | --- | --- |
| 删除 `or` 失败分支的 `ctxt.set(step, at, result)` 回滚 | `Parsers.java` | 选择/位置/错误错位并触发超时 | KILLED（2 Failures + 1 Error） |
| 删除 `raise` 中 `if (at < currentErrorAt) return` 的最远错误优先 | `ParseContext.java` | 生成式对照在超时内失败 | KILLED（1 Error/timeout） |
| 删除 `many` 中 `if (physical == at2) return true` 的空 parser 守卫 | `RepeatAtLeastParser.java` | 空成功导致重复不终止 | KILLED（1 Error/timeout） |

每个变异都让 `BacktrackingPropertyTest` 稳定失败；还原产品代码后全绿。

### 构建与测试

仓库根目录：

```
mvn -q -DskipTests package
mvn -q test
```

单独定位新用例：

```
mvn -q -pl jparsec surefire:test -Dtest=BacktrackingPropertyTest
```

注：本仓库的根 pom 固定了 Error Prone 2.0.15（仅能在 JDK 8 上加载），因此上述命令
需在 `JAVA_HOME` 指向 JDK 8 时执行；这是既有构建约束，本次未改动。
