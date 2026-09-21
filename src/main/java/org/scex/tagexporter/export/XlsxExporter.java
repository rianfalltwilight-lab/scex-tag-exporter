package org.scex.tagexporter.export;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A streaming OOXML writer requiring only the Java standard library. */
public final class XlsxExporter {
    static final int MAX_EXCEL_ROWS = 1_048_576;
    static final int MAX_CELL_CHARS = 32_767;
    static final String TRUNCATION_MARKER = "\n[已截断；完整值见配套 JSON；标签关系见「标签成员」]";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String SPREADSHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final Pattern OOXML_LITERAL_ESCAPE = Pattern.compile("_x(?=[0-9A-Fa-f]{4}_)");
    private static final String[] SHEET_NAMES = {"使用说明", "物品", "方块", "流体", "矿石", "标签索引", "标签成员", "模组"};
    private static final String[] ENTRY_HEADERS = {
            "注册表类型", "注册 ID", "中文名称 / 回退名称", "模组 ID（命名空间）", "模组名称",
            "标签（每行一个）", "标签数量", "翻译键", "关联 ID", "KubeJS 对象写法", "矿石标签判定", "矿石判定依据"
    };
    private static final double[] ENTRY_WIDTHS = {14, 42, 32, 24, 28, 56, 12, 48, 42, 58, 16, 50};

    private XlsxExporter() {}

    /**
     * Writes a complete workbook atomically where supported. Excel's row limit is checked before
     * creating output. The caller must also retain the complete snapshot as JSON: Excel has a
     * 32,767-character cell limit and XML cannot represent every possible Java string character.
     */
    public static void write(Path path, ExportSnapshot snapshot) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(snapshot, "snapshot");
        validateRowLimits(snapshot);
        Path target = path.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".scex-tags-", ".xlsx.tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
                writePart(zip, "[Content_Types].xml", XlsxExporter::writeContentTypes);
                writePart(zip, "_rels/.rels", out -> out.write(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                        + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
                        + "</Relationships>"));
                writePart(zip, "docProps/core.xml", out -> out.write(XML
                        + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                        + "<dc:title>SCEX 注册表与标签导出</dc:title><dc:creator>SCEX Tag Exporter</dc:creator>"
                        + "<dc:description>导出时间：" + xmlText(snapshot.exportedAt()) + "；数据来源：" + xmlText(snapshot.source())
                        + "</dc:description></cp:coreProperties>"));
                writePart(zip, "docProps/app.xml", out -> out.write(XML
                        + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>SCEX Tag Exporter</Application></Properties>"));
                writePart(zip, "xl/workbook.xml", XlsxExporter::writeWorkbook);
                writePart(zip, "xl/_rels/workbook.xml.rels", XlsxExporter::writeWorkbookRelationships);
                writePart(zip, "xl/styles.xml", XlsxExporter::writeStyles);
                writePart(zip, "xl/worksheets/sheet1.xml", out -> writeInstructions(out, snapshot));
                writePart(zip, "xl/worksheets/sheet2.xml", out -> writeEntries(out, snapshot, "item", false));
                writePart(zip, "xl/worksheets/sheet3.xml", out -> writeEntries(out, snapshot, "block", false));
                writePart(zip, "xl/worksheets/sheet4.xml", out -> writeEntries(out, snapshot, "fluid", false));
                writePart(zip, "xl/worksheets/sheet5.xml", out -> writeEntries(out, snapshot, "", true));
                writePart(zip, "xl/worksheets/sheet6.xml", out -> writeTagIndex(out, snapshot));
                writePart(zip, "xl/worksheets/sheet7.xml", out -> writeTagMembers(out, snapshot));
                writePart(zip, "xl/worksheets/sheet8.xml", out -> writeMods(out, snapshot));
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateRowLimits(ExportSnapshot snapshot) throws IOException {
        for (String kind : List.of("item", "block", "fluid")) {
            checkRowCount(kind, snapshot.entries().stream().filter(entry -> entry.kind().equals(kind)).count());
        }
        checkRowCount("矿石", snapshot.entries().stream().filter(ExportSnapshot.Entry::ore).count());
        checkRowCount("标签索引", snapshot.tags().size());
        checkRowCount("标签成员", snapshot.tags().stream().mapToLong(tag -> tag.members().size()).sum());
        checkRowCount("模组", snapshot.mods().size());
        checkRowCount("使用说明", 22L + snapshot.metadata().size());
    }

    static void checkRowCount(String sheet, long dataRows) throws IOException {
        if (dataRows < 0 || dataRows >= MAX_EXCEL_ROWS) {
            throw new IOException("工作表「" + sheet + "」需要 " + dataRows + " 条数据，超过 Excel 单表上限 "
                    + (MAX_EXCEL_ROWS - 1) + "（另含 1 行表头）；本次 XLSX 未写入，请使用完整 JSON。");
        }
    }

    private static void writePart(ZipOutputStream zip, String name, PartWriter action) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        Writer out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        action.write(out);
        out.flush();
        zip.closeEntry();
    }

    private static void writeContentTypes(Writer out) throws IOException {
        out.write(XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for (int i = 1; i <= SHEET_NAMES.length; i++) {
            out.write("<Override PartName=\"/xl/worksheets/sheet" + i + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        out.write("</Types>");
    }

    private static void writeWorkbook(Writer out) throws IOException {
        out.write(XML + "<workbook xmlns=\"" + SPREADSHEET_NS + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView/></bookViews><sheets>");
        for (int i = 0; i < SHEET_NAMES.length; i++) {
            out.write("<sheet name=\"" + SHEET_NAMES[i] + "\" sheetId=\"" + (i + 1) + "\" r:id=\"rId" + (i + 1) + "\"/>");
        }
        out.write("</sheets></workbook>");
    }

    private static void writeWorkbookRelationships(Writer out) throws IOException {
        out.write(XML + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= SHEET_NAMES.length; i++) {
            out.write("<Relationship Id=\"rId" + i + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet" + i + ".xml\"/>");
        }
        out.write("<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
    }

    private static void writeStyles(Writer out) throws IOException {
        out.write(XML + "<styleSheet xmlns=\"" + SPREADSHEET_NS + "\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font></fonts>"
                + "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF17365D\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF0F5FA\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"6\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"3\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
    }

    private static void writeInstructions(Writer out, ExportSnapshot snapshot) throws IOException {
        Sheet sheet = new Sheet(out, new String[]{"项目", "说明"}, new double[]{28, 120}, 12);
        sheet.row("用途", "查看当前已加载物品、方块、流体及其真实标签，供编写 KubeJS 脚本检索与复制。");
        sheet.row("导出时间", snapshot.exportedAt());
        sheet.row("名称语言", snapshot.language());
        sheet.row("数据来源", snapshot.source());
        sheet.row("现代矿辞", "Minecraft 1.21.1 使用 tags（标签）表达旧版矿辞用途。item / block / fluid 是独立注册表；同名标签不能跨注册表互换。");
        sheet.row("矿石页含义", "仅列出采集器根据实际 ores、ores/* 或 *_ores 标签判定的条目，并保留证据。不是世界矿脉分布、生成高度、储量或所有可能矿石的识别结果。");
        sheet.row("名称回退", "优先使用指定语言资源；缺失翻译时可能显示回退语言、翻译键或注册 ID。名称是检索辅助，脚本以注册 ID 为准。");
        sheet.row("关联 ID", "物品关联其方块 ID；方块关联其物品 ID；流体关联可用桶物品 ID。空白表示采集时无此对应项；不代表所有自定义容器关系。");
        sheet.row("标签索引", "未按命名空间筛选时保留已加载的空标签，成员数为 0 也有索引行；namespaceFilter 不为 * 时只包含有匹配成员的标签。名称相同但类型不同的标签分别记录。");
        sheet.row("标签成员", "一行是一条「注册表类型 + 标签 ID + 成员 ID」关系；完整标签关系在这里逐行列出。");
        sheet.row("物品对象", "Item.of('minecraft:iron_ingot')");
        sheet.row("物品标签匹配", "Ingredient.of('#c:ingots/iron')");
        sheet.row("流体对象", "Fluid.of('minecraft:water', 1000) // 数量单位 mB，按配方修改");
        sheet.row("流体标签匹配", "Fluid.ingredientOf('#minecraft:water') // 具体配方封装仍以所安装的 KubeJS 版本为准");
        sheet.row("方块 ID / 标签", "'minecraft:iron_ore' / '#minecraft:iron_ores'；方块标签不是物品 Ingredient。");
        sheet.row("添加物品标签示例", "ServerEvents.tags('item', event => { event.add('c:ingots/iron', 'minecraft:iron_ingot') })");
        sheet.row("添加方块标签示例", "ServerEvents.tags('block', event => { event.add('c:ores/iron', 'minecraft:iron_ore') })");
        sheet.row("添加流体标签示例", "ServerEvents.tags('fluid', event => { event.add('minecraft:water', 'minecraft:water') })");
        sheet.row("脚本位置与刷新", "标签脚本放入 kubejs/server_scripts；修改并完成资源/数据重载后重新导出。工作簿只反映此次采集时的状态。");
        sheet.row("长文本与原始数据", "Excel 单元格上限 32767 字符。超长值尾部显式标记「已截断」；完整值由同次导出的 JSON 保存，标签关系同时在「标签成员」逐行保全。XLSX 不是无损原始数据格式。");
        sheet.row("文本安全与限制", "名称、ID、代码等外部文本均为纯文本，不执行以 =、+、-、@ 开头的内容；标签数量和成员数量为数值，便于排序。XML 禁止字符显示为替换符。每张表最多 1048575 条数据加表头，超过时拒绝写入 XLSX，并报告错误。");
        sheet.row("条目统计", "注册条目 " + snapshot.entries().size() + "；标签 " + snapshot.tags().size() + "；模组 " + snapshot.mods().size());
        for (var metadata : snapshot.metadata().entrySet()) {
            sheet.row(metadata.getKey(), metadata.getValue());
        }
        sheet.finish();
    }

    private static void writeEntries(Writer out, ExportSnapshot snapshot, String kind, boolean oresOnly) throws IOException {
        Sheet sheet = new Sheet(out, ENTRY_HEADERS, ENTRY_WIDTHS);
        for (ExportSnapshot.Entry entry : snapshot.entries()) {
            if (oresOnly ? !entry.ore() : !entry.kind().equals(kind)) continue;
            sheet.row(entry.kind(), entry.id(), entry.name(), entry.namespace(), entry.modName(),
                    String.join("\n", entry.tags()), entry.tags().size(), entry.translationKey(),
                    entry.linkedId(), objectExpression(entry.kind(), entry.id()), entry.ore() ? "是" : "否", entry.oreEvidence());
        }
        sheet.finish();
    }

    private static void writeTagIndex(Writer out, ExportSnapshot snapshot) throws IOException {
        Sheet sheet = new Sheet(out, new String[]{"注册表类型", "标签 ID", "标签写法", "成员数量", "KubeJS 匹配写法"}, new double[]{14, 52, 53, 14, 72});
        for (ExportSnapshot.Tag tag : snapshot.tags()) {
            sheet.row(tag.kind(), tag.id(), "#" + tag.id(), tag.members().size(), tagExpression(tag.kind(), tag.id()));
        }
        sheet.finish();
    }

    private static void writeTagMembers(Writer out, ExportSnapshot snapshot) throws IOException {
        Sheet sheet = new Sheet(out, new String[]{"注册表类型", "标签 ID", "成员 ID", "KubeJS 标签匹配写法", "KubeJS 对象写法"}, new double[]{14, 52, 52, 72, 68});
        for (ExportSnapshot.Tag tag : snapshot.tags()) {
            for (String member : tag.members()) {
                sheet.row(tag.kind(), tag.id(), member, tagExpression(tag.kind(), tag.id()), objectExpression(tag.kind(), member));
            }
        }
        sheet.finish();
    }

    private static void writeMods(Writer out, ExportSnapshot snapshot) throws IOException {
        Sheet sheet = new Sheet(out, new String[]{"模组 ID", "模组名称", "模组版本"}, new double[]{36, 64, 36});
        for (ExportSnapshot.Mod mod : snapshot.mods()) sheet.row(mod.id(), mod.name(), mod.version());
        sheet.finish();
    }

    private static String objectExpression(String kind, String id) {
        return switch (kind) {
            case "item" -> "Item.of(" + jsString(id) + ")";
            case "fluid" -> "Fluid.of(" + jsString(id) + ", 1000)";
            case "block" -> jsString(id);
            default -> throw new IllegalArgumentException("Unsupported registry kind: " + kind);
        };
    }

    private static String tagExpression(String kind, String id) {
        return switch (kind) {
            case "item" -> "Ingredient.of(" + jsString("#" + id) + ")";
            case "fluid" -> "Fluid.ingredientOf(" + jsString("#" + id) + ")";
            case "block" -> jsString("#" + id);
            default -> throw new IllegalArgumentException("Unsupported registry kind: " + kind);
        };
    }

    private static String jsString(String input) {
        StringBuilder result = new StringBuilder("'");
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '\'' -> result.append("\\'");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('\'').toString();
    }

    /** Sanitize XML 1.0, then respect Excel's limit without splitting a supplementary character. */
    static String cellText(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint == 9 || codePoint == 10 || codePoint == 13 || codePoint >= 0x20 && codePoint <= 0xD7FF
                    || codePoint >= 0xE000 && codePoint <= 0xFFFD || codePoint >= 0x10000 && codePoint <= 0x10FFFF) {
                sanitized.appendCodePoint(codePoint);
            } else sanitized.append('\uFFFD');
        }
        if (sanitized.length() <= MAX_CELL_CHARS) return sanitized.toString();
        int end = MAX_CELL_CHARS - TRUNCATION_MARKER.length();
        if (Character.isHighSurrogate(sanitized.charAt(end - 1))) end--;
        return sanitized.substring(0, end) + TRUNCATION_MARKER;
    }

    /** Escape literal OOXML _xHHHH_ tokens before XML escaping, preserving displayed text. */
    private static String spreadsheetText(String value) {
        return xmlText(OOXML_LITERAL_ESCAPE.matcher(cellText(value)).replaceAll("_x005F_x"));
    }

    private static String xmlText(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            switch (codePoint) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                case '\r' -> escaped.append("&#13;");
                default -> {
                    if (codePoint == 9 || codePoint == 10 || codePoint >= 0x20 && codePoint <= 0xD7FF
                            || codePoint >= 0xE000 && codePoint <= 0xFFFD || codePoint >= 0x10000 && codePoint <= 0x10FFFF) {
                        escaped.appendCodePoint(codePoint);
                    } else escaped.append('\uFFFD');
                }
            }
        }
        return escaped.toString();
    }

    private static String columnName(int zeroBased) {
        StringBuilder name = new StringBuilder();
        for (int remaining = zeroBased + 1; remaining > 0; remaining = (remaining - 1) / 26) {
            name.append((char) ('A' + (remaining - 1) % 26));
        }
        return name.reverse().toString();
    }

    private static final class Sheet {
        private final Writer out;
        private final int columns;
        private final double[] widths;
        private final int maximumDisplayLines;
        private int rowNumber;

        Sheet(Writer out, String[] headers, double[] widths) throws IOException {
            this(out, headers, widths, 8);
        }

        Sheet(Writer out, String[] headers, double[] widths, int maximumDisplayLines) throws IOException {
            this.out = out;
            this.columns = headers.length;
            this.widths = widths.clone();
            this.maximumDisplayLines = maximumDisplayLines;
            if (headers.length != widths.length) throw new IllegalArgumentException("Column widths must match headers");
            out.write(XML + "<worksheet xmlns=\"" + SPREADSHEET_NS + "\"><sheetViews><sheetView workbookViewId=\"0\">"
                    + "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                    + "<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>"
                    + "<sheetFormatPr defaultRowHeight=\"30\"/><cols>");
            for (int i = 0; i < widths.length; i++) {
                out.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + widths[i] + "\" customWidth=\"1\"/>");
            }
            out.write("</cols><sheetData>");
            row((Object[]) headers);
        }

        void row(Object... values) throws IOException {
            if (values.length != columns) throw new IllegalArgumentException("Cell count must match headers");
            if (rowNumber >= MAX_EXCEL_ROWS) throw new IOException("Excel row limit exceeded");
            rowNumber++;
            int maxLines = 1;
            for (int i = 0; i < values.length; i++) {
                if (!(values[i] instanceof String) && !(values[i] instanceof Integer)) {
                    throw new IllegalArgumentException("Cell must be String or explicit integer count");
                }
                maxLines = Math.max(maxLines, displayLines(values[i].toString(), widths[i]));
            }
            int height = Math.max(rowNumber == 1 ? 32 : 30, maxLines * 17 + 5);
            out.write("<row r=\"" + rowNumber + "\" ht=\"" + height + "\" customHeight=\"1\">");
            int style = rowNumber == 1 ? 1 : rowNumber % 2 == 0 ? 2 : 3;
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof Integer number) {
                    int numberStyle = rowNumber % 2 == 0 ? 4 : 5;
                    out.write("<c r=\"" + columnName(i) + rowNumber + "\" s=\"" + numberStyle + "\" t=\"n\"><v>" + number + "</v></c>");
                } else {
                    out.write("<c r=\"" + columnName(i) + rowNumber + "\" s=\"" + style + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">");
                    out.write(spreadsheetText((String) values[i]));
                    out.write("</t></is></c>");
                }
            }
            out.write("</row>");
        }

        private int displayLines(String value, double width) {
            // Excel column widths approximate Latin glyph counts. Allow padding/font variance,
            // then treat non-Latin glyphs and emoji as double-width. Huge values remain capped.
            int available = Math.max(4, (int) (width * 0.88) - 2);
            int lines = 1;
            int lineWidth = 0;
            for (int i = 0; i < value.length() && lines < maximumDisplayLines;) {
                int codePoint = value.codePointAt(i);
                i += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    lines++;
                    lineWidth = 0;
                } else if (codePoint != '\r') {
                    int glyphWidth = codePoint == '\t' ? 4 : codePoint < 0x100 ? 1 : 2;
                    if (lineWidth + glyphWidth > available) {
                        lines++;
                        lineWidth = glyphWidth;
                    } else lineWidth += glyphWidth;
                }
            }
            return lines;
        }

        void finish() throws IOException {
            out.write("</sheetData><autoFilter ref=\"A1:" + columnName(columns - 1) + rowNumber
                    + "\"/><pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/></worksheet>");
        }
    }

    @FunctionalInterface
    private interface PartWriter {
        void write(Writer out) throws IOException;
    }
}
