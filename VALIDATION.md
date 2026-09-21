# 公开版验证记录

版本：SCEX Tag Exporter 1.0.0；目标：Minecraft 1.21.1 + NeoForge。

## 结果

- PASS：gradlew.bat build --offline --no-daemon --write-locks
- PASS：纯 Java writerTest，52,917 个细粒度断言，覆盖 XML/中文/emoji/特殊字符、公式前缀文本化、数字计数、空表、长标签、Excel 行数上限和旧输出保护。
- PASS：JAR 静态审计：ZIP、模组元数据、Java 21 字节码、无客户端专用类依赖、无测试探针类、许可证、KubeJS 可选、依赖锁和 Gradle wrapper。
- PASS：隔离专用服务端最小组合：无 KubeJS 时 41 项断言、3 次导出、2,398 条目、707 标签；KubeJS 组合时 69 项断言、4 次导出、1,693 物品、1,205 方块、49 流体、78 个矿石页行、956 标签、7,955 条标签成员关系。
- PASS：导出的 XLSX 与 JSON 行数和标签关系一致；8 个工作表均有冻结和筛选，数量列为原生数值，未发现公式或错误单元格。

## 产物

- JAR：dist/scex-tag-exporter-1.21.1-1.0.0.jar
- SHA-256：03742a7c098bacdac1b7a540d702d621e07c1e91d800464e111fb77be1c1ce23
- 示例工作簿：examples/registry-tags-example.xlsx
- 示例快照：examples/registry-tags-example.json

## 限制

长标签列表在表格中使用预览显示，完整值仍在 JSON 和“标签成员”页；原版中文资源缺失时会回退到英文。未宣称完整 SCEX 整包、客户端界面、多人客户端连接、旧世界、所有 NeoForge 21.1.x 补丁版本或用户本机 Excel 桌面程序均已验证。测试日志中可能出现开发环境常规警告，不能据此宣称所有日志均无 ERROR。