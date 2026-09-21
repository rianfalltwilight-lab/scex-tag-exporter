package org.scex.tagexporter.export;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** Standalone Java test runner; no Minecraft, JUnit, Excel, Python, or third-party dependency. */
public final class XlsxExporterTest {
    private static int assertions;
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final DocumentBuilderFactory XML_FACTORY = secureFactory();

    private XlsxExporterTest() {}

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "build/writer-test" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        testSnapshotImmutability();
        testCharacterHandling();
        testWorkbook(output);
        testEmptyWorkbook(output);
        testLongTagRelations(output);
        testRowLimitAndOutputPreservation(output);
        String result = "PASS: " + assertions + " assertions; native OOXML writer; output=" + output + System.lineSeparator();
        Files.writeString(output.resolve("result.txt"), result, StandardCharsets.UTF_8);
        System.out.print(result);
    }

    private static void testSnapshotImmutability() {
        List<String> entryTags = new ArrayList<>(List.of("c:ores/iron"));
        List<String> members = new ArrayList<>(List.of("minecraft:iron_ore"));
        ExportSnapshot.Entry entry = entry("item", "minecraft:iron_ore", "铁矿石", entryTags, "minecraft:iron_ore", true, "c:ores/iron");
        ExportSnapshot.Tag tag = new ExportSnapshot.Tag("item", "c:ores/iron", members);
        List<ExportSnapshot.Entry> entries = new ArrayList<>(List.of(entry));
        List<ExportSnapshot.Tag> tags = new ArrayList<>(List.of(tag));
        List<ExportSnapshot.Mod> mods = new ArrayList<>(List.of(new ExportSnapshot.Mod("minecraft", "Minecraft", "1.21.1")));
        Map<String, String> metadata = new LinkedHashMap<>(Map.of("test", "original"));
        ExportSnapshot snapshot = new ExportSnapshot("2026-09-21T00:00:00Z", "zh_cn", "fixture", metadata, entries, tags, mods);
        entryTags.clear();
        members.clear();
        entries.clear();
        tags.clear();
        mods.clear();
        metadata.put("test", "changed");
        equal(snapshot.entries().size(), 1, "entry list is copied");
        equal(snapshot.tags().size(), 1, "tag list is copied");
        equal(snapshot.mods().size(), 1, "mod list is copied");
        equal(entry.tags(), List.of("c:ores/iron"), "entry tags are copied");
        equal(tag.members(), List.of("minecraft:iron_ore"), "tag members are copied");
        equal(snapshot.metadata().get("test"), "original", "metadata is copied");
        expect(UnsupportedOperationException.class, () -> snapshot.entries().clear(), "entries cannot mutate");
        expect(UnsupportedOperationException.class, () -> snapshot.tags().clear(), "tags cannot mutate");
        expect(UnsupportedOperationException.class, () -> snapshot.mods().clear(), "mods cannot mutate");
        expect(UnsupportedOperationException.class, () -> entry.tags().clear(), "entry tags cannot mutate");
        expect(UnsupportedOperationException.class, () -> tag.members().clear(), "members cannot mutate");
        expect(UnsupportedOperationException.class, () -> snapshot.metadata().clear(), "metadata cannot mutate");
        expect(NullPointerException.class, () -> new ExportSnapshot.Tag("item", "c:test", null), "null members rejected");
        expect(NullPointerException.class, () -> new ExportSnapshot.Tag("item", "c:test", Collections.singletonList(null)), "null member rejected");
        expect(NullPointerException.class, () -> new ExportSnapshot.Mod("example", null, "1"), "null string rejected");
        Map<String, String> nullMetadata = new HashMap<>();
        nullMetadata.put("bad", null);
        expect(NullPointerException.class, () -> new ExportSnapshot("", "", "", nullMetadata, List.of(), List.of(), List.of()), "null metadata value rejected");
        expect(IllegalArgumentException.class, () -> new ExportSnapshot.Tag("biome", "c:test", List.of()), "unsupported kind rejected");
    }

    private static void testCharacterHandling() {
        equal(XlsxExporter.cellText("中文😀\t\n\r"), "中文😀\t\n\r", "Chinese emoji and XML whitespace preserved");
        equal(XlsxExporter.cellText("a\u0000\u0001\u000B\uD800z\uDC00\uFFFE\uFFFF"), "a����z���", "forbidden controls and lone surrogates replaced");
        equal(XlsxExporter.cellText("x".repeat(32_767)).length(), 32_767, "exact Excel cell limit preserved");
        String longText = "矿😀".repeat(20_000);
        String clipped = XlsxExporter.cellText(longText);
        check(clipped.length() <= 32_767, "long value fits Excel limit");
        check(clipped.endsWith(XlsxExporter.TRUNCATION_MARKER), "long value explicitly marked");
        for (int i = 0; i < clipped.length(); i++) {
            char c = clipped.charAt(i);
            if (Character.isHighSurrogate(c)) {
                check(i + 1 < clipped.length() && Character.isLowSurrogate(clipped.charAt(i + 1)), "truncation preserves complete surrogate pair");
                i++;
            } else check(!Character.isLowSurrogate(c), "no isolated low surrogate");
        }
    }

    private static void testWorkbook(Path output) throws Exception {
        String unusualName = "中文铁矿 & < > \" ' 😀\t\r\n_x000A_ _x005F_\u0001";
        String longName = "矿😀".repeat(20_000);
        List<ExportSnapshot.Entry> entries = List.of(
                entry("item", "minecraft:iron_ore", unusualName, List.of("c:ores/iron", "minecraft:iron_ores"), "minecraft:iron_ore", true, "c:ores/iron"),
                entry("block", "minecraft:iron_ore", "铁矿石", List.of("c:ores/iron"), "minecraft:iron_ore", true, "c:ores/iron"),
                entry("fluid", "minecraft:water", "水", List.of("minecraft:water"), "minecraft:water_bucket", false, ""),
                entry("item", "example:formula", "=HYPERLINK(\"https://example.invalid\")", List.of(), "", false, ""),
                entry("item", "example:plus", "+1+1", List.of(), "", false, ""),
                entry("item", "example:minus", "-1+1", List.of(), "", false, ""),
                entry("item", "example:at", "@SUM(A1)", List.of(), "", false, ""),
                entry("item", "example:long", longName, List.of(), "", false, ""),
                entry("item", "example:quote'\\\n", "转义", List.of(), "", false, ""));
        List<ExportSnapshot.Tag> tags = List.of(
                new ExportSnapshot.Tag("item", "c:ores/iron", List.of("minecraft:iron_ore")),
                new ExportSnapshot.Tag("item", "minecraft:iron_ores", List.of("minecraft:iron_ore")),
                new ExportSnapshot.Tag("block", "c:ores/iron", List.of("minecraft:iron_ore")),
                new ExportSnapshot.Tag("fluid", "minecraft:water", List.of("minecraft:water")),
                new ExportSnapshot.Tag("item", "example:empty", List.of()));
        ExportSnapshot snapshot = snapshot(entries, tags);
        Path file = output.resolve("sample.xlsx");
        XlsxExporter.write(file, snapshot);
        Map<String, Document> parts = parseArchive(file);
        equal(parts.size(), 15, "complete minimal OOXML package");
        Document workbook = parts.get("xl/workbook.xml");
        NodeList sheets = workbook.getElementsByTagNameNS(NS, "sheet");
        equal(sheets.getLength(), 8, "eight worksheets");
        String[] names = {"使用说明", "物品", "方块", "流体", "矿石", "标签索引", "标签成员", "模组"};
        for (int i = 0; i < names.length; i++) equal(((Element) sheets.item(i)).getAttribute("name"), names[i], "worksheet name " + i);

        Map<String, String> items = cells(parts.get("xl/worksheets/sheet2.xml"));
        equal(items.get("C2"), "中文铁矿 & < > \" ' 😀\t\r\n_x005F_x000A_ _x005F_x005F_�", "XML chars and OOXML literal escapes preserved");
        equal(items.get("F2"), "c:ores/iron\nminecraft:iron_ores", "tag list is multiline");
        equal(items.get("G2"), "2", "tag count");
        equal(items.get("I2"), "minecraft:iron_ore", "item links block");
        equal(items.get("J2"), "Item.of('minecraft:iron_ore')", "item KJS expression");
        equal(items.get("C3"), "=HYPERLINK(\"https://example.invalid\")", "formula prefix kept as inline string");
        equal(items.get("C4"), "+1+1", "plus prefix kept as inline string");
        equal(items.get("C5"), "-1+1", "minus prefix kept as inline string");
        equal(items.get("C6"), "@SUM(A1)", "at prefix kept as inline string");
        check(items.get("C7").endsWith(XlsxExporter.TRUNCATION_MARKER), "long spreadsheet value marked");
        check(items.get("C7").length() <= 32_767, "long spreadsheet value bounded");
        equal(snapshot.entries().get(7).name(), longName, "original snapshot long value retained for JSON");
        equal(items.get("J8"), "Item.of('example:quote\\'\\\\\\n')", "JS quotes backslash newline escaped");

        Map<String, String> blocks = cells(parts.get("xl/worksheets/sheet3.xml"));
        equal(blocks.get("I2"), "minecraft:iron_ore", "block links item");
        equal(blocks.get("J2"), "'minecraft:iron_ore'", "block uses only ID string");
        Map<String, String> fluids = cells(parts.get("xl/worksheets/sheet4.xml"));
        equal(fluids.get("I2"), "minecraft:water_bucket", "fluid links bucket");
        equal(fluids.get("J2"), "Fluid.of('minecraft:water', 1000)", "fluid KJS expression");
        equal(rowCount(parts.get("xl/worksheets/sheet5.xml")), 3, "ores contains flagged item and block only");

        Map<String, String> index = cells(parts.get("xl/worksheets/sheet6.xml"));
        equal(index.get("A2"), "item", "item tag registry retained");
        equal(index.get("A4"), "block", "same named block tag distinguished");
        equal(index.get("E2"), "Ingredient.of('#c:ores/iron')", "item tag KJS expression");
        equal(index.get("E4"), "'#c:ores/iron'", "block tag not an item ingredient");
        equal(index.get("E5"), "Fluid.ingredientOf('#minecraft:water')", "fluid tag KJS expression");
        equal(index.get("B6"), "example:empty", "empty tag retained in index");
        equal(index.get("D6"), "0", "empty tag count zero");
        equal(rowCount(parts.get("xl/worksheets/sheet7.xml")), 5, "exactly one row per tag-member relation");
        Map<String, String> instructions = cells(parts.get("xl/worksheets/sheet1.xml"));
        check(instructions.values().stream().anyMatch(v -> v.contains("不是世界矿脉分布")), "ore interpretation explained");
        check(instructions.values().stream().anyMatch(v -> v.contains("ServerEvents.tags('fluid'")), "mutation examples available");
        check(instructions.values().stream().anyMatch(v -> v.contains("JSON")), "JSON preservation explained");
        for (int i = 1; i <= 8; i++) {
            Document sheet = parts.get("xl/worksheets/sheet" + i + ".xml");
            NodeList panes = sheet.getElementsByTagNameNS(NS, "pane");
            equal(panes.getLength(), 1, "freeze pane exists " + i);
            equal(((Element) panes.item(0)).getAttribute("ySplit"), "1", "first row frozen " + i);
            equal(sheet.getElementsByTagNameNS(NS, "autoFilter").getLength(), 1, "filter exists " + i);
            check(sheet.getElementsByTagNameNS(NS, "col").getLength() > 0, "column widths exist " + i);
            equal(sheet.getElementsByTagNameNS(NS, "f").getLength(), 0, "no executable formulas " + i);
            for (Element cell : elements(sheet, "c")) {
                String reference = cell.getAttribute("r");
                boolean numericCount = i >= 2 && i <= 5 && reference.startsWith("G") && !reference.equals("G1")
                        || i == 6 && reference.startsWith("D") && !reference.equals("D1");
                equal(cell.getAttribute("t"), numericCount ? "n" : "inlineStr", "counts numeric and external data text " + reference);
            }
        }
        Element introLongRow = elements(parts.get("xl/worksheets/sheet1.xml"), "row").get(21);
        check(Double.parseDouble(introLongRow.getAttribute("ht")) > 30, "long instructions get wrapped row height");
    }

    private static void testEmptyWorkbook(Path output) throws Exception {
        Path file = output.resolve("empty.xlsx");
        XlsxExporter.write(file, snapshot(List.of(), List.of()));
        Map<String, Document> parts = parseArchive(file);
        for (int i = 2; i <= 7; i++) equal(rowCount(parts.get("xl/worksheets/sheet" + i + ".xml")), 1, "empty data worksheet retains header " + i);
    }

    private static void testLongTagRelations(Path output) throws Exception {
        List<String> entryTags = new ArrayList<>();
        List<ExportSnapshot.Tag> tags = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            String tag = "example:long_relation_" + i;
            entryTags.add(tag);
            tags.add(new ExportSnapshot.Tag("item", tag, List.of("example:tagged_item")));
        }
        ExportSnapshot snapshot = snapshot(List.of(entry("item", "example:tagged_item", "多标签物品", entryTags, "", false, "")), tags);
        Path file = output.resolve("long-tags.xlsx");
        XlsxExporter.write(file, snapshot);
        Map<String, Document> parts = parseArchive(file);
        String joined = cells(parts.get("xl/worksheets/sheet2.xml")).get("F2");
        check(joined.endsWith(XlsxExporter.TRUNCATION_MARKER), "joined long tag list explicitly clipped");
        equal(rowCount(parts.get("xl/worksheets/sheet6.xml")), 3001, "every long-list tag retained in index");
        equal(rowCount(parts.get("xl/worksheets/sheet7.xml")), 3001, "every long-list relationship retained");
        equal(cells(parts.get("xl/worksheets/sheet7.xml")).get("B3001"), "example:long_relation_2999", "last relationship preserved");
        equal(snapshot.entries().getFirst().tags().size(), 3000, "snapshot preserves full tags for JSON");
    }

    private static void testRowLimitAndOutputPreservation(Path output) throws Exception {
        XlsxExporter.checkRowCount("limit", XlsxExporter.MAX_EXCEL_ROWS - 1L);
        assertions++;
        expect(IOException.class, () -> XlsxExporter.checkRowCount("limit", XlsxExporter.MAX_EXCEL_ROWS), "one row beyond Excel capacity rejected");
        ExportSnapshot.Entry repeated = entry("item", "minecraft:stone", "石头", List.of(), "minecraft:stone", false, "");
        ExportSnapshot tooMany = snapshot(Collections.nCopies(XlsxExporter.MAX_EXCEL_ROWS, repeated), List.of());
        Path file = output.resolve("row-limit-existing.txt");
        Files.writeString(file, "existing output", StandardCharsets.UTF_8);
        try {
            XlsxExporter.write(file, tooMany);
            throw new AssertionError("row-limit integration export must fail");
        } catch (IOException expected) {
            check(expected.getMessage().contains("超过 Excel"), "Excel overflow error identifies limit");
        }
        equal(Files.readString(file, StandardCharsets.UTF_8), "existing output", "row-limit failure preserves existing target");
        try (var files = Files.list(output)) {
            check(files.noneMatch(p -> p.getFileName().toString().endsWith(".xlsx.tmp")), "temporary output cleaned");
        }
    }

    private static ExportSnapshot snapshot(List<ExportSnapshot.Entry> entries, List<ExportSnapshot.Tag> tags) {
        return new ExportSnapshot("2026-09-21T00:00:00Z", "zh_cn", "standalone Java test fixture",
                Map.of("test_metadata", "中文 & Unicode 😀"), entries, tags,
                List.of(new ExportSnapshot.Mod("minecraft", "Minecraft", "1.21.1"), new ExportSnapshot.Mod("example", "测试模组", "1.0.0")));
    }

    private static ExportSnapshot.Entry entry(String kind, String id, String name, List<String> tags, String linked, boolean ore, String evidence) {
        return new ExportSnapshot.Entry(kind, id, id.substring(0, id.indexOf(':')), "测试模组", name, kind + "." + id.replace(':', '.'), tags, linked, ore, evidence);
    }

    private static Map<String, Document> parseArchive(Path path) throws Exception {
        Map<String, Document> parts = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                check(!entry.isDirectory(), "ZIP contains only file parts");
                check(entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels"), "ZIP part is expected XML");
                try (var input = zip.getInputStream(entry)) {
                    Document document = XML_FACTORY.newDocumentBuilder().parse(input);
                    check(parts.put(entry.getName(), document) == null, "no duplicate ZIP part");
                }
            }
        }
        return parts;
    }

    private static DocumentBuilderFactory secureFactory() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Map<String, String> cells(Document document) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (Element cell : elements(document, "c")) {
            String type = cell.getAttribute("t");
            check(type.equals("inlineStr") || type.equals("n"), "cell has safe explicit type");
            String element = type.equals("n") ? "v" : "t";
            equal(cell.getElementsByTagNameNS(NS, element).getLength(), 1, "cell has one value");
            String value = cell.getElementsByTagNameNS(NS, element).item(0).getTextContent();
            if (type.equals("n")) check(value.matches("[0-9]+"), "numeric cells contain only nonnegative counts");
            cells.put(cell.getAttribute("r"), value);
        }
        return cells;
    }

    private static int rowCount(Document document) {
        return document.getElementsByTagNameNS(NS, "row").getLength();
    }

    private static List<Element> elements(Document document, String name) {
        NodeList nodes = document.getElementsByTagNameNS(NS, name);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) result.add((Element) nodes.item(i));
        return result;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void expect(Class<? extends Throwable> type, ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            check(type.isInstance(failure), message + ": got " + failure);
            return;
        }
        throw new AssertionError(message + ": no exception thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
