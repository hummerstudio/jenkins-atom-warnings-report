# jenkins-atom-warnings

Compiler warnings — port of **warnings-ng** (18,389) as an atom.

只做统计与门禁；“新增 vs 已修复”要上次构建的状态，属 runner。

## Build and smoke test

```bash
mvn verify    # packages dist/warnings/ and runs src/test/java/atom/WarningsReportIT.java
```
