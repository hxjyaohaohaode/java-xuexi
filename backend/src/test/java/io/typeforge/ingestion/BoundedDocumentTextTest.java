package io.typeforge.ingestion;

import io.typeforge.shared.DomainException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedDocumentTextTest {
    private static final String TYPES="<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
    private static final String START="<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>";
    private static final String END="</w:body></w:document>";
    private static byte[] utf8(String value) {return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] zip(Map<String,byte[]> parts) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            for(var part:parts.entrySet()) {zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue());zip.closeEntry();}
        }
        return bytes.toByteArray();
    }
    private static byte[] docx(String body) throws Exception {return zip(Map.of("[Content_Types].xml",utf8(TYPES),"word/document.xml",utf8(START+body+END)));}
    private static String rejected(String name,byte[] bytes) {return assertThrows(DomainException.class,()->BoundedDocumentText.extract(name,bytes)).code();}

    @Test void utf8NormalizationPreservesCodeIndentationAndSupplementaryCharacters() {
        String original="\uFEFFJava泛型🙂\r\n```java\r\n    List<? extends Number> values;\r\n```\r";
        var result=BoundedDocumentText.extract("LESSON.MD",utf8(original));
        assertEquals("Java泛型🙂\n```java\n    List<? extends Number> values;\n```\n",result.text());assertEquals("text/markdown",result.mediaType());
    }
    @Test void actualWordDocumentExtractsParagraphsTablesAndLiteralJavaSymbols() throws Exception {
        byte[] bytes;
        try(var word=new XWPFDocument();var output=new ByteArrayOutputStream()) {
            var run=word.createParagraph().createRun();run.setText("    List<T> & Map<K,V>");run.addBreak();run.setText("界限🙂");
            var table=word.createTable(1,2);table.getRow(0).getCell(0).setText("输入");table.getRow(0).getCell(1).setText("输出");
            word.write(output);bytes=output.toByteArray();
        }
        var text=BoundedDocumentText.extract("真实文档.docx",bytes).text();assertTrue(text.contains("    List<T> & Map<K,V>\n界限🙂"),text);assertTrue(text.contains("输入\n\t输出\n\t"),text);
    }
    @Test void strictWordNamespaceAndTabsRemainReadable() throws Exception {
        String xml=START.replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main","http://purl.oclc.org/ooxml/wordprocessingml/main")+"<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t></w:r></w:p>"+END;
        var text=BoundedDocumentText.extract("strict.docx",zip(Map.of("[Content_Types].xml",utf8(TYPES),"word/document.xml",utf8(xml)))).text();assertEquals("A\tB\n",text);
    }
    @Test void unsupportedFormatsAndFalseExtensionsNeverCreateFakeText() {
        assertEquals("IMPORT_FORMAT_UNSUPPORTED",rejected("scan.pdf",utf8("%PDF-1.7")));assertEquals("IMPORT_FORMAT_UNSUPPORTED",rejected("macro.docm",utf8("data")));
        assertEquals("IMPORT_PACKAGE_INVALID",rejected("renamed.docx",utf8("plain text")));
    }
    @Test void emptyBinaryAndInvalidUtf8AreRejectedWithoutReplacementCharacters() {
        assertEquals("EMPTY_IMPORT_FILE",rejected("empty.txt",new byte[0]));assertEquals("IMPORT_TEXT_EMPTY",rejected("blank.txt",utf8(" \n\t")));
        assertEquals("IMPORT_ENCODING_UNSUPPORTED",rejected("broken.txt",new byte[]{(byte)0xc3,0x28}));assertEquals("IMPORT_TEXT_INVALID",rejected("nul.md",utf8("code\u0000text")));
    }
    @Test void limitsRejectInsteadOfSilentlyTruncating() {
        assertEquals("IMPORT_FILE_TOO_LARGE",rejected("large.txt",new byte[BoundedDocumentText.MAX_FILE_BYTES+1]));
        assertEquals(32000,BoundedDocumentText.extract("max.txt",utf8("中".repeat(32000))).text().length());assertEquals("IMPORT_TEXT_TOO_LONG",rejected("long.md",utf8("x".repeat(32001))));
        assertThrows(DomainException.class,()->BoundedDocumentText.normalize("x\uD800"));
    }
    @Test void xmlEntitiesCannotReadFilesOrContactExternalEndpoints() throws Exception {
        String xml="<!DOCTYPE document [<!ENTITY leak SYSTEM \"file:///not-a-real-file/private-marker\">]>"+START+"<w:p><w:r><w:t>&leak;</w:t></w:r></w:p>"+END;
        String code=rejected("entity.docx",zip(Map.of("[Content_Types].xml",utf8(TYPES),"word/document.xml",utf8(xml))));assertTrue(code.equals("IMPORT_XML_UNSAFE") || code.equals("IMPORT_XML_INVALID"),code);
    }
    @Test void contentTypesMustBeStructuredAndDeclareTheActualWordPart() throws Exception {
        var xml=utf8(START+"<w:p><w:r><w:t>text</w:t></w:r></w:p>"+END);
        assertEquals("IMPORT_PACKAGE_INVALID",rejected("spoof.docx",zip(Map.of("[Content_Types].xml",utf8("<fake>application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml</fake>"),"word/document.xml",xml))));
        assertEquals("IMPORT_PACKAGE_INVALID",rejected("macro.docx",zip(Map.of("[Content_Types].xml",utf8(TYPES.replace("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml","application/vnd.ms-word.document.macroEnabled.main+xml")),"word/document.xml",xml))));
    }
    @Test void embeddedProgramsAndArchiveTraversalAreRejected() throws Exception {
        var base=new LinkedHashMap<String,byte[]>();base.put("[Content_Types].xml",utf8(TYPES));base.put("word/document.xml",utf8(START+"<w:p><w:r><w:t>text</w:t></w:r></w:p>"+END));
        base.put("word/vbaProject.bin",new byte[]{1});assertEquals("IMPORT_ACTIVE_CONTENT",rejected("macro.docx",zip(base)));base.remove("word/vbaProject.bin");
        base.put("../escape.txt",new byte[]{1});assertEquals("IMPORT_PACKAGE_INVALID",rejected("escape.docx",zip(base)));
    }
    @Test void expansionAndXmlDepthAreBounded() throws Exception {
        assertEquals("IMPORT_EXPANSION_LIMIT",rejected("bomb.docx",zip(Map.of("padding",new byte[2_097_153]))));
        String nested="<w:p>".repeat(66)+"<w:r><w:t>x</w:t></w:r>"+"</w:p>".repeat(66);
        assertEquals("IMPORT_XML_LIMIT",rejected("deep.docx",docx(nested)));
    }
    @Test void overpopulatedArchivesAndMalformedXmlAreRejected() throws Exception {
        var entries=new LinkedHashMap<String,byte[]>();for(int i=0;i<129;i++) entries.put("part-"+i,new byte[]{1});
        assertEquals("IMPORT_PACKAGE_INVALID",rejected("parts.docx",zip(entries)));
        assertEquals("IMPORT_XML_INVALID",rejected("broken.docx",docx("<w:p>")));
    }
    @Test void alternateCompatibilityBranchesAreNeverImportedTwiceAsBodyText() throws Exception {
        String body="<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><mc:Choice Requires=\"w\"><w:p><w:r><w:t>modern</w:t></w:r></w:p></mc:Choice><mc:Fallback><w:p><w:r><w:t>fallback</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>";
        assertEquals("IMPORT_ALTERNATE_CONTENT",rejected("branches.docx",docx(body)));
    }
    @Test void trackedMovesAndUnresolvedEditsCannotSilentlyDuplicateOrReinterpretTheSource() throws Exception {
        String moved="<w:p><w:moveFrom><w:r><w:t>moved statement</w:t></w:r></w:moveFrom></w:p><w:p><w:moveTo><w:r><w:t>moved statement</w:t></w:r></w:moveTo></w:p>";
        assertEquals("IMPORT_TRACKED_CHANGES",rejected("tracked-move.docx",docx(moved)));
        assertEquals("IMPORT_TRACKED_CHANGES",rejected("tracked-insert.docx",docx("<w:p><w:ins><w:r><w:t>unresolved text</w:t></w:r></w:ins></w:p>")));
    }
    @Test void filenamesCannotCarryHostPathsControlsOrUnlimitedMetadata() {
        assertEquals("lesson.docx",BoundedDocumentText.fileName("C:\\private\\lesson.docx"));assertEquals("notes.md",BoundedDocumentText.fileName("/tmp/\u202Enotes.md"));
        assertEquals("未命名文件",BoundedDocumentText.fileName(null));assertThrows(DomainException.class,()->BoundedDocumentText.fileName("x".repeat(129)));
    }
}
