package io.typeforge.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Base64;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Native, editable Office files and searchable, embedded-font PDFs from bounded inert DSL. */
@Component
public class ArtifactFileRenderer {
    public static final String VERSION="native-artifact-renderer-v2";
    public static final int MAX_BYTES=8*1024*1024;
    public record Rendered(String mimeType,String extension,byte[] bytes) {
        public Rendered { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    private final TextArtifactRenderer text;
    private final AssetService assets;
    public ArtifactFileRenderer(TextArtifactRenderer text,AssetService assets){this.text=text;this.assets=assets;}
    private record ImageValue(byte[] bytes,String mimeType,int width,int height) {}
    public boolean supports(String kind,String format) {
        return text.supports(kind,format) || kind.equals("DOCUMENT") && Set.of("DOCX","PDF").contains(format)
            || kind.equals("SLIDES") && Set.of("PPTX","PDF").contains(format);
    }
    public Rendered render(UUID owner,ArtifactService.Artifact artifact,ArtifactService.Version version,String format) {
        if(artifact.kind().equals("DOCUMENT"))artifact=new ArtifactService.Artifact(artifact.id(),artifact.workspaceId(),artifact.kind(),version.content().path("title").asText(),artifact.currentRevision());
        if(!supports(artifact.kind(),format)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"No configured renderer supports this artifact and format");
        try {
            var images=images(owner,artifact,version);
            long characters=version.content().toString().length();
            if(characters>262144)throw new DomainException("EXPORT_SOURCE_TOO_LARGE",422,"Export source exceeds the bounded document size");
            long htmlUpperBound=characters*6+65536;
            if(format.equals("HTML"))for(JsonNode slide:version.content().path("slides"))for(JsonNode element:slide.path("elements"))if(element.hasNonNull("assetId"))htmlUpperBound+=4L*((images.get(element.path("assetId").asText()).bytes().length+2)/3)+256;
            if(format.equals("HTML") && htmlUpperBound>MAX_BYTES)throw new DomainException("EXPORT_TOO_LARGE",422,"Repeated slide images exceed the embedded HTML limit; export PPTX or reduce image size");
            Rendered result;
            if(text.supports(artifact.kind(),format)) {
                var urls=new LinkedHashMap<String,String>();images.forEach((id,value)->urls.put(id,"data:"+value.mimeType()+";base64,"+Base64.getEncoder().encodeToString(value.bytes())));
                var value=text.render(artifact,version,format,urls);
                result=new Rendered(value.mimeType(),value.extension(),value.content().getBytes(StandardCharsets.UTF_8));
            } else {
                var output=new BoundedBytes();
                switch(format) {
                    case "DOCX"->word(artifact,version,output);
                    case "PPTX"->slides(artifact,version,output,images);
                    case "PDF"->pdf(artifact,version,output,images);
                    default->throw new IllegalStateException("Unregistered renderer");
                }
                result=new Rendered(switch(format) {
                    case "DOCX"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    case "PPTX"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    default->"application/pdf";
                },format.toLowerCase(java.util.Locale.ROOT),output.toByteArray());
            }
            if(result.bytes().length==0 || result.bytes().length>MAX_BYTES) throw new DomainException("EXPORT_TOO_LARGE",422,"Export exceeds the 8 MiB file limit; split the source into smaller artifacts");
            return result;
        } catch(IOException exception) {
            throw new DomainException("EXPORT_RENDER_FAILED",503,"File renderer could not finish; the source is preserved");
        }
    }
    private Map<String,ImageValue> images(UUID owner,ArtifactService.Artifact artifact,ArtifactService.Version version) {
        var images=new LinkedHashMap<String,ImageValue>();long total=0,pixels=0;
        if(!artifact.kind().equals("SLIDES"))return images;
        for(JsonNode slide:version.content().path("slides"))for(JsonNode element:slide.path("elements"))if(element.hasNonNull("assetId")) {
            String id=element.path("assetId").asText();if(images.containsKey(id))continue;
            var download=assets.download(owner,UUID.fromString(id));var asset=download.asset();
            if(!asset.workspaceId().equals(artifact.workspaceId()) || !Set.of("image/png","image/jpeg").contains(asset.mimeType()))throw DomainException.invalid("Only admitted images in this workspace can be embedded in slides");
            if(version.inputRefs().stream().noneMatch(ref->ref.kind().equals("ASSET") && ref.id().equals(asset.id()) && ref.revision()==asset.revision() && ref.contentHash().equals(asset.contentHash())))throw DomainException.conflict("Slide asset source has changed");
            pixels+=(long)asset.width()*asset.height();if(pixels>33_554_432L)throw new DomainException("EXPORT_IMAGE_CAPACITY",422,"The total slide image resolution exceeds 32 megapixels; resize images before exporting");
            total+=asset.byteSize();if(total>MAX_BYTES)throw new DomainException("EXPORT_TOO_LARGE",422,"Slide image inputs exceed 8 MiB; choose smaller images");
            images.put(id,new ImageValue(download.bytes(),asset.mimeType(),asset.width(),asset.height()));
        }return Map.copyOf(images);
    }
    private static Rectangle2D contain(double x,double y,double width,double height,ImageValue image) {
        double scale=Math.min(width/image.width(),height/image.height()),w=image.width()*scale,h=image.height()*scale;
        return new Rectangle2D.Double(x+(width-w)/2,y+(height-h)/2,w,h);
    }
    private static void word(ArtifactService.Artifact artifact,ArtifactService.Version version,ByteArrayOutputStream output) throws IOException {
        try(var document=new XWPFDocument()) {
            document.getProperties().getCoreProperties().setTitle(artifact.title());
            document.getProperties().getCoreProperties().setCreator("TypeForge");
            document.getProperties().getCoreProperties().setDescription("Source revision "+version.revision()+"; SHA-256 "+version.contentHash()+"; "+version.reviewStatus());
            paragraph(document,artifact.title(),"TITLE");
            paragraph(document,"源稿版本 "+version.revision()+" · "+version.reviewStatus()+" · 请核对引用与教学结论。","META");
            for(JsonNode block:version.content().path("blocks")) {
                paragraph(document,block.path("text").asText(),block.path("kind").asText());
                if(!block.path("evidenceRefs").isEmpty()) paragraph(document,citations(block.path("evidenceRefs")),"META");
            }
            paragraph(document,"Source SHA-256: "+version.contentHash(),"META");
            document.write(output);
        }
    }
    private static void paragraph(XWPFDocument document,String text,String kind) {
        XWPFParagraph p=document.createParagraph();p.setSpacingAfter(kind.equals("HEADING")?140:100);
        p.setSpacingBetween(1.4);
        if(Set.of("TITLE","HEADING").contains(kind)) p.setKeepNext(true);
        if(kind.equals("QUOTE")) p.setIndentationLeft(360);
        XWPFRun run=p.createRun();run.setFontFamily(kind.equals("CODE")?"Consolas":"Noto Sans SC");
        run.setFontSize(kind.equals("TITLE")?24:kind.equals("HEADING")?16:kind.equals("META")?9:11);
        run.setBold(Set.of("TITLE","HEADING").contains(kind));run.setColor(kind.equals("META")?"59686A":"182E32");
        String[] lines=xmlSafe(text).split("\n",-1);
        for(int i=0;i<lines.length;i++){if(i>0)run.addBreak();run.setText(lines[i]);}
    }
    private static void slides(ArtifactService.Artifact artifact,ArtifactService.Version version,ByteArrayOutputStream output,Map<String,ImageValue> images) throws IOException {
        try(var presentation=new XMLSlideShow()) {
            int height=version.content().path("aspect").asText().equals("4:3")?540:405;
            presentation.setPageSize(new Dimension(720,height));
            presentation.getProperties().getCoreProperties().setTitle(artifact.title());
            presentation.getProperties().getCoreProperties().setCreator("TypeForge");
            for(JsonNode source:version.content().path("slides")) {
                var slide=presentation.createSlide();
                slide.getBackground().setFillColor(new Color(248,250,249));
                box(slide.createTextBox(),source.path("title").asText(),new Rectangle2D.Double(28,14,664,46),24,false);
                for(JsonNode element:source.path("elements")) {
                    validateElement(element);
                    double x=element.path("x").asDouble()*720,y=element.path("y").asDouble()*height;
                    double width=element.path("width").asDouble()*720,h=element.path("height").asDouble()*height;
                    if(element.hasNonNull("assetId")) {
                        var image=images.get(element.path("assetId").asText());
                        var picture=presentation.addPicture(image.bytes(),image.mimeType().equals("image/png")?PictureType.PNG:PictureType.JPEG);
                        slide.createPicture(picture).setAnchor(contain(x,y,width,h,image));continue;
                    }
                    String value=element.path("text").asText();boolean code=element.path("kind").asText().equals("CODE");
                    double font=fitSize(value,width-12,h-10,code?17:21);
                    if(font<9) throw new DomainException("SLIDE_TEXT_OVERFLOW",422,"A slide element has more text than its area can hold; enlarge it or split the content");
                    box(slide.createTextBox(),value,new Rectangle2D.Double(x,y,width,h),font,code);
                }
                var note=presentation.getNotesSlide(slide);
                var noteBox=note.createTextBox();
                noteBox.setText(xmlSafe(source.path("notes").asText()+"\n\n"+citations(source.path("evidenceRefs"))+"\nSource SHA-256: "+version.contentHash()));
                noteBox.setAnchor(new Rectangle2D.Double(30,30,600,400));
            }
            presentation.write(output);
        }
    }
    private static void box(XSLFTextBox box,String text,Rectangle2D anchor,double size,boolean code) {
        box.setAnchor(anchor);box.setLeftInset(6);box.setRightInset(6);box.setTopInset(4);box.setBottomInset(4);box.setWordWrap(true);
        if(code)box.setFillColor(new Color(229,239,236));
        box.clearText();
        for(String line:xmlSafe(text).split("\n",-1)) {
            var paragraph=box.addNewTextParagraph();paragraph.setTextAlign(TextAlign.LEFT);paragraph.setSpaceAfter(0.0);paragraph.setLineSpacing(115.0);
            var run=paragraph.addNewTextRun();run.setText(line);run.setFontFamily(code?"Consolas":"Noto Sans SC");run.setFontSize(size);run.setFontColor(new Color(24,46,50));
        }
    }
    private static double fitSize(String value,double width,double height,double preferred) {
        if(width<8 || height<8)return 0;
        for(double size=preferred;size>=9;size-=1) {
            int rows=0;for(String line:value.split("\n",-1)) {
                double units=line.codePoints().mapToDouble(c->c<=0x7f?0.58:1.0).sum();
                rows+=Math.max(1,(int)Math.ceil(units*size/width));
            }
            if(rows*size*1.2<=height)return size;
        }return 0;
    }
    private static void validateElement(JsonNode element) {
        if(!Set.of("TEXT","CODE","IMAGE","DIAGRAM").contains(element.path("kind").asText())) throw DomainException.invalid("Unsupported slide element");
        if(element.path("x").asDouble()+element.path("width").asDouble()>1.000001 || element.path("y").asDouble()+element.path("height").asDouble()>1.000001)
            throw new DomainException("SLIDE_OUTSIDE_CANVAS",422,"A slide element extends beyond its canvas; move or resize it before export");
    }
    private static void pdf(ArtifactService.Artifact artifact,ArtifactService.Version version,ByteArrayOutputStream output,Map<String,ImageValue> images) throws IOException {
        try(var document=new PDDocument();var fontStream=new ClassPathResource("fonts/TypeForgeCJK.ttf").getInputStream()) {
            PDType0Font font=PDType0Font.load(document,fontStream,true);
            var pictureObjects=new LinkedHashMap<String,PDImageXObject>();
            document.getDocumentInformation().setTitle(artifact.title());
            document.getDocumentInformation().setAuthor("TypeForge");
            document.getDocumentInformation().setSubject("Source SHA-256: "+version.contentHash());
            if(artifact.kind().equals("SLIDES")) {
                float height=version.content().path("aspect").asText().equals("4:3")?540:405;
                for(JsonNode source:version.content().path("slides")) {
                    PDPage page=new PDPage(new PDRectangle(720,height));document.addPage(page);
                    try(var stream=new PDPageContentStream(document,page)) {
                        drawLines(stream,font,wrap(font,source.path("title").asText(),24,660),28,height-36,24,29);
                        for(JsonNode element:source.path("elements")) {
                            validateElement(element);
                            if(element.hasNonNull("assetId")) {
                                var image=images.get(element.path("assetId").asText());
                                var bounds=contain(element.path("x").asDouble()*720,element.path("y").asDouble()*height,element.path("width").asDouble()*720,element.path("height").asDouble()*height,image);
                                String imageId=element.path("assetId").asText();
                                var picture=pictureObjects.get(imageId);if(picture==null){picture=PDImageXObject.createFromByteArray(document,image.bytes(),"private-image");pictureObjects.put(imageId,picture);}
                                stream.drawImage(picture,(float)bounds.getX(),height-(float)(bounds.getY()+bounds.getHeight()),(float)bounds.getWidth(),(float)bounds.getHeight());continue;
                            }
                            float w=(float)element.path("width").asDouble()*720-12,h=(float)element.path("height").asDouble()*height-10;
                            float size=(float)fitSize(element.path("text").asText(),w,h,element.path("kind").asText().equals("CODE")?17:21);
                            if(size<9)throw new DomainException("SLIDE_TEXT_OVERFLOW",422,"A slide element needs more space before PDF export");
                            var lines=wrap(font,element.path("text").asText(),size,w);
                            while(size>=9 && lines.size()*size*1.2>h){size--;lines=wrap(font,element.path("text").asText(),size,w);}
                            if(size<9)throw new DomainException("SLIDE_TEXT_OVERFLOW",422,"A slide element needs more space before PDF export");
                            drawLines(stream,font,lines,(float)element.path("x").asDouble()*720+6,height-(float)element.path("y").asDouble()*height-size-4,size,size*1.2f);
                        }
                    }
                }
            } else {
                try(var writer=new PdfPages(document,font)) {
                    writer.block(artifact.title(),24,32);
                    writer.block("源稿版本 "+version.revision()+" · "+version.reviewStatus()+" · 请核对引用与教学结论。",9,14);
                    for(JsonNode block:version.content().path("blocks")) {
                        String kind=block.path("kind").asText();float size=kind.equals("HEADING")?16:kind.equals("CODE")?10:11;
                        writer.block(block.path("text").asText(),size,size*1.6f);
                        if(!block.path("evidenceRefs").isEmpty())writer.block(citations(block.path("evidenceRefs")),8,12);
                    }
                    writer.block("Source SHA-256: "+version.contentHash(),8,12);
                }
            }
            document.save(output);
        }
    }
    private static List<String> wrap(PDType0Font font,String text,float size,float width) throws IOException {
        var lines=new ArrayList<String>();
        for(String paragraph:xmlSafe(text).replace("\t","    ").split("\n",-1)) {
            var line=new StringBuilder();float used=0;
            for(int point:paragraph.codePoints().toArray()) {
                String value=new String(Character.toChars(point));float advance;
                try{advance=font.getStringWidth(value)/1000*size;}catch(IllegalArgumentException unsupported){throw new DomainException("EXPORT_FONT_GLYPH_UNAVAILABLE",422,"The PDF font does not contain a character in the source; remove unsupported symbols or export DOCX instead");}
                if(line.length()>0 && used+advance>width){lines.add(line.toString());line.setLength(0);used=0;}
                line.append(value);used+=advance;
            }lines.add(line.toString());
        }return lines;
    }
    private static void drawLines(PDPageContentStream stream,PDType0Font font,List<String> lines,float x,float y,float size,float leading) throws IOException {
        stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(new Color(24,46,50));stream.newLineAtOffset(x,y);
        for(String line:lines){stream.showText(line);stream.newLineAtOffset(0,-leading);}stream.endText();
    }
    private static final class PdfPages implements AutoCloseable {
        private final PDDocument document;private final PDType0Font font;private PDPageContentStream stream;private float y;
        PdfPages(PDDocument document,PDType0Font font) throws IOException{this.document=document;this.font=font;next();}
        private void next() throws IOException {
            if(stream!=null)stream.close();if(document.getNumberOfPages()>=80)throw new DomainException("EXPORT_PAGE_LIMIT",422,"Document exceeds 80 pages; split the source");
            PDPage page=new PDPage(PDRectangle.A4);document.addPage(page);stream=new PDPageContentStream(document,page);y=PDRectangle.A4.getHeight()-52;
        }
        void block(String text,float size,float leading) throws IOException {
            for(String line:wrap(font,text,size,PDRectangle.A4.getWidth()-104)) {
                if(y-leading<52)next();drawLines(stream,font,List.of(line),52,y,size,leading);y-=leading;
            }y-=9;
        }
        @Override public void close() throws IOException{if(stream!=null)stream.close();}
    }
    private static String citations(JsonNode refs) {
        var value=new StringBuilder("知识引用：");for(JsonNode ref:refs)value.append(ref.path("id").asText()).append(" @ ").append(ref.path("revision").asInt()).append("；");return value.toString();
    }
    private static String xmlSafe(String text) {
        if(text.codePoints().anyMatch(c->!(c==9 || c==10 || c==13 || c>=32 && c<=0xd7ff || c>=0xe000 && c<=0xfffd || c>=0x10000 && c<=0x10ffff)))
            throw new DomainException("INVALID_XML_TEXT",422,"The source contains unsupported control characters");
        return text.replace("\r\n","\n").replace('\r','\n');
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        BoundedBytes(){super(8192);}
        private void admit(int size){if(size<0 || count>MAX_BYTES-size)throw new DomainException("EXPORT_TOO_LARGE",422,"Generated file exceeds the 8 MiB output limit");}
        @Override public synchronized void write(int value){admit(1);super.write(value);}
        @Override public synchronized void write(byte[] value,int offset,int length){admit(length);super.write(value,offset,length);}
    }
    public static String sha256(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
