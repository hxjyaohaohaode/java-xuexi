package io.typeforge.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Deterministic, inert exports. No arbitrary HTML, scripts, remote assets, browser or shell. */
@Component
public final class TextArtifactRenderer {
    public static final String VERSION="inert-text-renderer-v1";
    public record Rendered(String mimeType,String extension,String content,List<String> limitations) {}
    public boolean supports(String kind,String format) {
        return switch(kind) {case "DOCUMENT"->Set.of("MARKDOWN","HTML").contains(format);case "SLIDES","NOTEBOOK","INTERACTION"->format.equals("HTML");case "DIAGRAM"->format.equals("SVG");default->false;};
    }
    public Rendered render(ArtifactService.Artifact artifact,ArtifactService.Version version,String format) {
        if(!supports(artifact.kind(),format)) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"This artifact format has no configured renderer");
        JsonNode dsl=version.content();String output,mime,extension;
        if(format.equals("MARKDOWN")) {output=markdown(dsl,version);mime="text/markdown";extension="md";}
        else if(format.equals("SVG")) {output=svg(dsl,artifact.title(),version);mime="image/svg+xml";extension="svg";}
        else {output=html(artifact,dsl,version);mime="text/html";extension="html";}
        if(format.equals("SVG") && output.codePoints().anyMatch(point->!(point==9 || point==10 || point==13 || point>=32 && point<=0xd7ff || point>=0xe000 && point<=0xfffd || point>=0x10000 && point<=0x10ffff))) {
            throw new DomainException("INVALID_XML_TEXT",422,"SVG source contains characters forbidden by XML 1.0; edit the source text before exporting");
        }
        int bytes=output.getBytes(StandardCharsets.UTF_8).length;
        if(bytes<1 || bytes>1048576) throw new DomainException("EXPORT_TOO_LARGE",422,"Text export exceeds the 1 MiB renderer limit");
        var limitations=new java.util.ArrayList<String>();
        limitations.add("这是固定 revision 的源作品渲染，不代表教学事实已通过人工核验；源稿状态："+version.reviewStatus());
        if(Set.of("NOTEBOOK","INTERACTION").contains(artifact.kind())) limitations.add("下载版为静态阅读材料。提交答案与复练请返回在线工作空间；离线文件不记录成绩。");
        if(artifact.kind().equals("SLIDES")) limitations.add("这是可打印的 HTML 幻灯片，不是 PPTX；图像与外部素材未配置。");
        if(artifact.kind().equals("DIAGRAM")) limitations.add("关系与 verdict 来自用户源稿；图形渲染不构成 Java 语义核验。");
        return new Rendered(mime,extension,output,List.copyOf(limitations));
    }
    private static String html(ArtifactService.Artifact artifact,JsonNode dsl,ArtifactService.Version version) {
        var body=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'\"><title>");
        body.append(escape(artifact.title())).append("</title><style>body{font:17px/1.8 system-ui,sans-serif;max-width:960px;margin:40px auto;padding:24px;color:#172a2a;background:#faf9f6}h1,h2{line-height:1.3}pre,blockquote{white-space:pre-wrap;overflow-wrap:anywhere}pre{background:#e9eeed;padding:20px;font:15px/1.6 monospace}.notice,footer{color:#526362;font-size:13px}.slide{position:relative;background:white;margin:24px 0;overflow:hidden;break-after:page}.element{position:absolute;overflow:hidden;white-space:pre-wrap;box-sizing:border-box}.slide h2{position:absolute;left:4%;top:1%;z-index:1}.notes{white-space:pre-wrap}.block{margin:32px 0}svg{max-width:100%}@media print{body{padding:0;margin:0;max-width:none}.slide{margin:0}}</style></head><body><h1>").append(escape(artifact.title())).append("</h1><p class=\"notice\">固定源稿版本 ").append(version.revision()).append(" · ").append(escape(version.reviewStatus())).append(" · 教学事实未经此渲染器核验。</p>");
        switch(artifact.kind()) {
            case "DOCUMENT"->{for(JsonNode block:dsl.path("blocks")) {
                String tag=switch(block.path("kind").asText()) {case "HEADING"->"h2";case "CODE"->"pre";case "QUOTE"->"blockquote";default->"p";};
                body.append('<').append(tag).append('>').append(escape(block.path("text").asText())).append("</").append(tag).append('>');citations(body,block.path("evidenceRefs"));
            }}
            case "SLIDES"->{int height=dsl.path("aspect").asText().equals("4:3")?720:540;
                for(JsonNode slide:dsl.path("slides")) {
                    body.append("<section class=\"slide\" style=\"height:").append(height).append("px\"><h2>").append(escape(slide.path("title").asText())).append("</h2>");
                    for(JsonNode el:slide.path("elements")) {
                        if(el.hasNonNull("assetId") || Set.of("IMAGE","DIAGRAM").contains(el.path("kind").asText())) throw new DomainException("CAPABILITY_UNAVAILABLE",503,"Slide image and diagram elements need an admitted asset renderer; export refuses to silently omit them");
                        body.append("<div class=\"element\" style=\"left:").append(percent(el,"x")).append("%;top:").append(percent(el,"y")).append("%;width:").append(percent(el,"width")).append("%;height:").append(percent(el,"height")).append("%\">");
                        boolean code=el.path("kind").asText().equals("CODE");if(code) body.append("<pre>");
                        body.append(escape(el.path("text").asText()));if(code) body.append("</pre>");body.append("</div>");
                    }
                    body.append("</section><p class=\"notes\">").append(escape(slide.path("notes").asText())).append("</p>");citations(body,slide.path("evidenceRefs"));
                }
            }
            case "NOTEBOOK","INTERACTION"->{body.append("<p class=\"notice\">静态阅读导出。真实提交、提示与复练在在线工作空间进行，此文件不执行脚本、不记录成绩。</p>");
                for(JsonNode block:dsl.path("blocks")) {
                    body.append("<section class=\"block\"><h2>").append(escape(block.path("kind").asText())).append("</h2><p>").append(escape(block.path("prompt").asText())).append("</p><pre>").append(escape(block.path("config").toPrettyString())).append("</pre>");citations(body,block.path("evidenceRefs"));body.append("</section>");
                }
            }
            default->throw DomainException.invalid("Unsupported HTML artifact");
        }
        body.append("<footer>Source SHA-256: ").append(escape(version.contentHash())).append(" · ").append(VERSION).append("</footer></body></html>");return body.toString();
    }
    private static String percent(JsonNode node,String key) {return String.format(Locale.ROOT,"%.3f",node.path(key).asDouble()*100);}
    private static void citations(StringBuilder output,JsonNode citations) {
        if(citations.isEmpty()) return;output.append("<p class=\"notice\">知识引用：");
        for(JsonNode ref:citations) output.append(escape(ref.path("id").asText())).append(" @ ").append(ref.path("revision").asInt()).append("; ");
        output.append("</p>");
    }
    private static String markdown(JsonNode dsl,ArtifactService.Version version) {
        var text=new StringBuilder("# ").append(markdownText(dsl.path("title").asText())).append("\n\n> 固定源稿 revision ").append(version.revision()).append("；状态 ").append(version.reviewStatus()).append("；渲染不代表事实核验。\n\n");
        for(JsonNode block:dsl.path("blocks")) {
            String value=block.path("text").asText();
            switch(block.path("kind").asText()) {
                case "CODE"->{int max=0,run=0;for(char ch:value.toCharArray()) {run=ch=='`'?run+1:0;max=Math.max(max,run);}String fence="`".repeat(Math.max(3,max+1));text.append(fence).append("java\n").append(value).append('\n').append(fence).append("\n\n");}
                case "HEADING"->text.append("## ").append(markdownText(value).replace("\n"," ")).append("\n\n");
                case "QUOTE"->text.append("> ").append(markdownText(value).replace("\n","\n> ")).append("\n\n");
                default->text.append(markdownText(value)).append("\n\n");
            }
            for(JsonNode ref:block.path("evidenceRefs")) text.append("知识引用：").append(ref.path("id").asText()).append(" @ ").append(ref.path("revision").asInt()).append("\n\n");
        }
        return text.append("Source SHA-256: ").append(version.contentHash()).append('\n').toString();
    }
    private static String markdownText(String value) {
        String safe=escape(value);for(String character:List.of("\\","`","*","_","[","]","(",")","#","!","|","+","-",".")) safe=safe.replace(character,"\\"+character);return safe;
    }
    private static String svg(JsonNode dsl,String title,ArtifactService.Version version) {
        int count=dsl.path("nodes").size(),height=Math.max(240,count*110+150);
        var b=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1000\" height=\"").append(height).append("\" viewBox=\"0 0 1000 ").append(height).append("\" role=\"img\"><title>").append(escape(title)).append("</title><desc>用户源稿关系图；verdict 未经渲染器语义核验。Source ").append(version.contentHash()).append("</desc><rect width=\"1000\" height=\"").append(height).append("\" fill=\"#faf9f6\"/><text x=\"24\" y=\"32\" font-family=\"sans-serif\" font-size=\"20\" fill=\"#172a2a\">").append(escape(title)).append("</text><text x=\"24\" y=\"58\" font-family=\"sans-serif\" font-size=\"12\">源稿关系；不是编译裁决。完整标签保存在各节点 title 中。</text>");
        var positions=new HashMap<String,Integer>();int index=0;
        for(JsonNode node:dsl.path("nodes")) positions.put(node.path("id").asText(),100+index++*110);
        index=0;
        for(JsonNode edge:dsl.path("edges")) {
            Integer from=positions.get(edge.path("from").asText()),to=positions.get(edge.path("to").asText());if(from==null || to==null) throw DomainException.invalid("Diagram references a missing node");
            int lane=420+(index%9)*50;String color=edge.path("verdict").asText().equals("ALLOWED")?"#12645c":edge.path("verdict").asText().equals("DENIED")?"#a13535":"#687374";
            b.append("<path d=\"M 390 ").append(from+22).append(" H ").append(lane).append(" V ").append(to+34).append(" H 390\" fill=\"none\" stroke=\"").append(color).append("\"><title>").append(escape(edge.path("from").asText()+" → "+edge.path("to").asText()+" "+edge.path("relation").asText()+" "+edge.path("verdict").asText())).append("</title></path>");
            b.append("<text x=\"").append(lane+5).append("\" y=\"").append(from+18+(index%3)*12).append("\" font-family=\"sans-serif\" font-size=\"10\" fill=\"").append(color).append("\">").append(escape(edge.path("relation").asText()+" "+edge.path("verdict").asText())).append("</text>");index++;
        }
        for(JsonNode node:dsl.path("nodes")) {
            int y=positions.get(node.path("id").asText());String label=node.path("label").asText(),type=node.path("typeExpression").asText();
            b.append("<g><title>").append(escape(label+" · "+type)).append("</title><rect x=\"24\" y=\"").append(y).append("\" width=\"366\" height=\"76\" rx=\"8\" fill=\"#e1ece8\"/><text x=\"40\" y=\"").append(y+28).append("\" font-family=\"sans-serif\" font-size=\"16\">").append(escape(abbreviate(label,26))).append("</text><text x=\"40\" y=\"").append(y+54).append("\" font-family=\"monospace\" font-size=\"13\">").append(escape(abbreviate(type,42))).append("</text></g>");
        }
        return b.append("</svg>").toString();
    }
    private static String abbreviate(String value,int max) {int points=value.codePointCount(0,value.length());return points<=max?value:value.substring(0,value.offsetByCodePoints(0,max-1))+"…";}
    private static String escape(String value) {return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
