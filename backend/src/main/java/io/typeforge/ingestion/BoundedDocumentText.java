package io.typeforge.ingestion;

import io.typeforge.shared.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Bounded, text-only extraction: no external relationships, macros, images, filesystem or network. */
public final class BoundedDocumentText {
    public static final int MAX_FILE_BYTES=1_048_576;
    public static final int MAX_TEXT_CHARS=32_000;
    private static final int MAX_EXPANDED_BYTES=2_097_152;
    private static final Set<String> WORD_NAMESPACES=Set.of(
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
        "http://purl.oclc.org/ooxml/wordprocessingml/main");
    private static final Set<String> TRACKED_CHANGES=Set.of("ins","del","delText","moveFrom","moveTo",
        "moveFromRangeStart","moveFromRangeEnd","moveToRangeStart","moveToRangeEnd",
        "customXmlInsRangeStart","customXmlDelRangeStart","customXmlMoveFromRangeStart","customXmlMoveToRangeStart");
    public record Extracted(String mediaType,String parser,String text,String message) {}
    private BoundedDocumentText() {}

    public static String fileName(String supplied) {
        String name=supplied==null?"":supplied.replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}\\p{Cf}]","").strip();
        if(name.isEmpty()) name="未命名文件";
        if(name.length()>128) throw failure("INVALID_FILE_NAME","文件名不能超过128个字符。");
        return name;
    }
    public static Extracted extract(String fileName,byte[] bytes) {
        if(bytes.length==0) throw failure("EMPTY_IMPORT_FILE","文件为空，请选择包含正文的文件。");
        if(bytes.length>MAX_FILE_BYTES) throw failure("IMPORT_FILE_TOO_LARGE","单个导入文件上限为1 MiB。");
        String lower=fileName.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".txt") || lower.endsWith(".md")) {
            return new Extracted(lower.endsWith(".md")?"text/markdown":"text/plain","utf8-newlines-v1",normalize(decode(bytes)),
                "已读取UTF-8正文并保留缩进；请检查清洗稿及来源后再提交。");
        }
        if(lower.endsWith(".docx")) return new Extracted("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "docx-body-text-v1",normalize(docx(bytes)),"仅提取正文与表格文字；图片、批注、脚注、样式和外部链接内容不导入，请人工核对。");
        throw failure("IMPORT_FORMAT_UNSUPPORTED","当前支持UTF-8 TXT、Markdown和DOCX正文；PDF、扫描件、旧DOC及宏文档不在本解析器范围内。");
    }
    public static String normalize(String value) {
        String text=value.replace("\r\n","\n").replace('\r','\n');
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        if(text.isBlank()) throw failure("IMPORT_TEXT_EMPTY","文件未包含可导入的正文文字。");
        if(text.length()>MAX_TEXT_CHARS) throw failure("IMPORT_TEXT_TOO_LONG","提取正文超过32000字符，请按章节拆分；内容未被截断。");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if((c<32 && c!='\n' && c!='\t') || c==127) throw failure("IMPORT_TEXT_INVALID","正文含不支持的控制字符。");
            if(Character.isHighSurrogate(c)) {
                if(i+1>=text.length() || !Character.isLowSurrogate(text.charAt(++i))) throw failure("IMPORT_TEXT_INVALID","正文含无效Unicode字符。");
            } else if(Character.isLowSurrogate(c)) throw failure("IMPORT_TEXT_INVALID","正文含无效Unicode字符。");
        }
        return text;
    }
    private static String decode(byte[] bytes) {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid) {throw failure("IMPORT_ENCODING_UNSUPPORTED","文本编码不是有效UTF-8，请转换编码后重试。");}
    }
    private static String docx(byte[] bytes) {
        long deadline=System.nanoTime()+2_000_000_000L;
        byte[] document=null,types=null;
        int expanded=0;
        Set<String> names=new HashSet<>();
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                budget(deadline);
                String name=entry.getName();
                if(names.size()>=128 || !names.add(name) || name.startsWith("/") || name.contains("\\") || name.contains("../") || name.length()>255)
                    throw failure("IMPORT_PACKAGE_INVALID","DOCX压缩包包含无效、重复或过多的部件。");
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.contains("vbaproject") || lower.startsWith("word/embeddings/") || lower.startsWith("word/activex/"))
                    throw failure("IMPORT_ACTIVE_CONTENT","不导入包含宏、ActiveX或嵌入程序的文档。");
                boolean keep=name.equals("word/document.xml") || name.equals("[Content_Types].xml");
                ByteArrayOutputStream part=keep?new ByteArrayOutputStream():null;
                int count;
                while((count=zip.read(buffer))!=-1) {
                    budget(deadline);expanded+=count;
                    if(expanded>MAX_EXPANDED_BYTES || expanded>(long)bytes.length*100)
                        throw failure("IMPORT_EXPANSION_LIMIT","DOCX解压数据或压缩比例超过限制，请拆分并另存为普通文档。");
                    if(keep) part.write(buffer,0,count);
                }
                if(name.equals("word/document.xml")) document=part.toByteArray();
                if(name.equals("[Content_Types].xml")) types=part.toByteArray();
                zip.closeEntry();
            }
        } catch(IOException | IllegalArgumentException invalid) {throw failure("IMPORT_PACKAGE_INVALID","DOCX压缩包损坏或不是可读取的Word文档。");}
        if(document==null || types==null || !validContentTypes(types,deadline))
            throw failure("IMPORT_PACKAGE_INVALID","未找到有效DOCX正文及内容类型，不能仅通过修改扩展名导入。");
        var text=new StringBuilder();
        try {
            var reader=xmlFactory().createXMLStreamReader(new ByteArrayInputStream(document));
            try {
                int depth=0,elements=0,textDepth=-1,bodyDepth=-1;
                boolean root=false,body=false;
                while(reader.hasNext()) {
                    budget(deadline);int event=reader.next();
                    if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE) throw failure("IMPORT_XML_UNSAFE","不允许DTD或实体引用；文档未读取任何外部内容。");
                    String namespace=(event==XMLStreamConstants.START_ELEMENT || event==XMLStreamConstants.END_ELEMENT)?reader.getNamespaceURI():null;
                    boolean word=namespace!=null && WORD_NAMESPACES.contains(namespace);
                    if(event==XMLStreamConstants.START_ELEMENT) {
                        depth++;elements++;
                        if("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(namespace) && "AlternateContent".equals(reader.getLocalName()))
                            throw failure("IMPORT_ALTERNATE_CONTENT","文档包含多分支兼容内容；为避免正文重复，请将所需内容另存为UTF-8文本后导入。");
                        if(word && (TRACKED_CHANGES.contains(reader.getLocalName()) || reader.getLocalName().endsWith("PrChange") || reader.getLocalName().equals("tblGridChange")))
                            throw failure("IMPORT_TRACKED_CHANGES","文档仍有未处理的修订记录；请先接受或拒绝修订并另存，避免同时导入移动前后的正文。");
                        if(depth>64 || elements>100_000) throw failure("IMPORT_XML_LIMIT","DOCX结构过深或节点过多。");
                        String name=reader.getLocalName();
                        if(depth==1) {root=word && name.equals("document");if(!root) throw failure("IMPORT_PACKAGE_INVALID","DOCX正文根节点无效。");}
                        if(word && name.equals("body") && depth==2) {body=true;bodyDepth=depth;}
                        if(bodyDepth>0 && word) {
                            if(name.equals("t")) textDepth=depth;
                            else if(name.equals("tab")) append(text,"\t");
                            else if(name.equals("br") || name.equals("cr")) append(text,"\n");
                        }
                    } else if(event==XMLStreamConstants.CHARACTERS || event==XMLStreamConstants.CDATA) {
                        if(textDepth>0) append(text,reader.getText());
                    } else if(event==XMLStreamConstants.END_ELEMENT) {
                        if(bodyDepth>0 && word && (reader.getLocalName().equals("p") || reader.getLocalName().equals("tr"))) append(text,"\n");
                        if(bodyDepth>0 && word && reader.getLocalName().equals("tc")) append(text,"\t");
                        if(depth==textDepth) textDepth=-1;
                        if(depth==bodyDepth) bodyDepth=-1;
                        depth--;
                    }
                }
                if(!root || !body) throw failure("IMPORT_PACKAGE_INVALID","DOCX缺少正文区域。");
            } finally {reader.close();}
        } catch(XMLStreamException invalid) {throw failure("IMPORT_XML_INVALID","DOCX正文XML损坏或使用了不允许的外部内容。");}
        return text.toString();
    }
    private static XMLInputFactory xmlFactory() {
        XMLInputFactory factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External references are forbidden");});
        return factory;
    }
    private static boolean validContentTypes(byte[] bytes,long deadline) {
        String ns="http://schemas.openxmlformats.org/package/2006/content-types";
        boolean found=false;int depth=0,elements=0;
        try {
            var reader=xmlFactory().createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                while(reader.hasNext()) {
                    budget(deadline);int event=reader.next();
                    if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE) throw failure("IMPORT_XML_UNSAFE","不允许DTD或实体引用；文档未读取任何外部内容。");
                    if(event==XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        if(depth>8 || ++elements>1024) throw failure("IMPORT_XML_LIMIT","DOCX内容类型结构超过限制。");
                        if(depth==1 && !(ns.equals(reader.getNamespaceURI()) && "Types".equals(reader.getLocalName()))) return false;
                        if(depth==2 && ns.equals(reader.getNamespaceURI()) && "Override".equals(reader.getLocalName()) && "/word/document.xml".equals(reader.getAttributeValue(null,"PartName"))) {
                            if(found || !"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(reader.getAttributeValue(null,"ContentType"))) return false;
                            found=true;
                        }
                    } else if(event==XMLStreamConstants.END_ELEMENT) depth--;
                }
            } finally {reader.close();}
        } catch(XMLStreamException invalid) {throw failure("IMPORT_XML_INVALID","DOCX内容类型XML无效。");}
        return found;
    }
    private static void append(StringBuilder text,String value) {
        if((long)text.length()+value.length()>MAX_TEXT_CHARS) throw failure("IMPORT_TEXT_TOO_LONG","提取正文超过32000字符，请按章节拆分；内容未被截断。");
        text.append(value);
    }
    private static void budget(long deadline) {
        if(System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw failure("IMPORT_PARSER_DEADLINE","文档解析超过限制，请拆分后重试。");
    }
    private static DomainException failure(String code,String message) {return new DomainException(code,422,message);}
}
