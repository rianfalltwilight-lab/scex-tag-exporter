# SCEX Tag Exporter

面向 Minecraft 1.21.1 + NeoForge 整合包作者的标签查询与 Excel 导出模组。它读取进入世界后实际生效的物品、方块、流体注册表及 tags，方便编写 KubeJS；没有 KubeJS 也可以使用。

## 安装

从 dist/ 下载 scex-tag-exporter-1.21.1-1.0.0.jar，放入测试整合包的 mods 目录。进入单人世界，或安装到专用服务端后启动；需要 OP 2 级或控制台权限。

执行 /scextags export，完成后在服务端游戏目录的 exports/scex-tags/<UTC时间-唯一编号>/ 找到：

- registry-tags.xlsx：8 个工作表，含物品、方块、流体、矿石、标签索引、标签成员、模组和 KubeJS 示例。
- snapshot.json：完整机器可读快照。
- COMPLETE.txt：写入完成标志。

多人服的导出文件位于服务端，不会自动下载到玩家电脑。

## 命令

    /scextags export                       导出全部，优先中文名
    /scextags export thermal                仅导出 thermal 命名空间
    /scextags export * en_us                全量导出，优先英文名
    /scextags export minecraft zh_cn        仅导出原版条目，优先中文名
    /scextags hand                          查看主手物品标签
    /scextags item minecraft:iron_ingot     查看指定物品标签
    /scextags block minecraft:iron_ore      查看指定方块标签
    /scextags fluid minecraft:water         查看指定流体标签
    /scextags tag item c:ingots/iron        反查物品标签成员
    /scextags tag block c:ores              反查方块标签成员
    /scextags tag fluid minecraft:water     反查流体标签成员

命名空间和注册 ID 支持补全。反查聊天最多显示前 50 个成员，Excel/JSON 保留全部。修改 kubejs/server_scripts 或数据包后，等待 /reload 成功再导出；修改 startup_scripts 通常需要完整重启。

## Excel 内容

| 工作表 | 用途 |
| --- | --- |
| 使用说明 | 导出时间、版本、语言、数据来源和 KubeJS 示例 |
| 物品 | 注册 ID、名称、模组、标签及 Item.of 写法 |
| 方块 | 方块标签及对应物品 ID |
| 流体 | 源流体/流动流体、标签、桶 ID 及 Fluid.of 写法 |
| 矿石 | 从实际 ores、ores/*、*_ores 标签筛出的物品/方块 |
| 标签索引 | 注册表、标签、成员数和 KubeJS 匹配表达式 |
| 标签成员 | 一行一条“注册表 + 标签 + 成员”关系 |
| 模组 | 已加载模组 ID、名称、版本 |

现代“矿辞”对应 tags，例如 c:ingots/iron、c:ores/copper。工具会保留实际存在的 c:、forge:、minecraft: 和模组自有标签，不会修改或统一它们。矿石页只依据标签分类，不扫描世界，也不表示矿脉生成、高度或储量。

## KubeJS 示例

    Item.of('minecraft:iron_ingot')
    Ingredient.of('#c:ingots/iron')

    Fluid.of('minecraft:water', 1000)
    Fluid.ingredientOf('#minecraft:water')
    Fluid.sizedIngredientOf('#minecraft:water', 1000)

    ServerEvents.tags('item', event => {
      event.add('c:ingots/iron', 'examplemod:iron_ingot')
    })

## 名称和边界

优先读取已安装模组的 assets/*/lang/zh_cn.json，缺少翻译时回退到 en_us、运行时名称或翻译键。也可以在 config/scex-tag-exporter/lang/zh_cn.json 放置自有覆盖。名称仅辅助检索，注册 ID 才是稳定依据。

每个已注册对象导出一行；不会枚举附魔、NBT/数据组件、药水等所有物品堆变体，也不会枚举自定义流体容器。表格冻结首行并启用筛选，长标签在单元格中显示预览，完整关系保留在 JSON 和“标签成员”页。

## 构建

要求 Java 21。项目锁定 Gradle 8.14.3、ModDevGradle 2.0.144、NeoForge 21.1.248，依赖锁定在 gradle.lockfile。

    .\gradlew.bat build --offline --no-daemon

首次构建没有缓存时移除 --offline。writerTest 是纯 Java 导出格式回归测试，不启动 Minecraft。

仓库中的 examples/ 是 Thermal + KubeJS 测试夹具示例，不是任何用户整合包的当前清单；在目标实例执行命令才能获取实际结果。

## 来源

按用户需求原创实现，功能方向参考 https://github.com/xkball/LetMeSeeSee，不包含其源代码、资源或反编译器。实现来源和锁定版本见 NOTICE.md。验证范围见 VALIDATION.md。