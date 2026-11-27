package io.typeforge.tutor;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import io.typeforge.shared.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CoursewareSlides {
    private CoursewareSlides() {}

    static JsonNode render(JsonCodec json,CoursewareSlideSource.Edition edition,JsonNode expertSlides,TutorService.Mode mode) {
        var pages=new ArrayList<JsonNode>();
        for (var card:edition.cards()) {
            var evidence=card.evidence()==null ? List.of() : List.of(Map.of("id",card.evidence().id(),"revision",card.evidence().revision()));
            var parts=pages(card.body());
            for (int i=0;i<parts.size();i++) {
                String id=card.key()+"-"+(i+1);
                var elements=new ArrayList<Map<String,Object>>();
                elements.add(Map.of("id",id+"-body","kind",card.kind().equals("EXAMPLE")?"CODE":"TEXT",
                    "x",0.05,"y",0.14,"width",0.90,"height",0.63,"text",parts.get(i),"assetId",json.tree("null"),"alt",""));
                if (card.prompt()!=null && i==parts.size()-1) elements.add(Map.of("id",id+"-task","kind","TEXT",
                    "x",0.05,"y",0.81,"width",0.90,"height",0.16,"text",card.prompt(),"assetId",json.tree("null"),"alt",""));
                pages.add(json.tree(json.write(Map.of("id",id,"title",card.title()+(parts.size()>1?" · "+(i+1)+"/"+parts.size():""),
                    "elements",elements,"notes","","evidenceRefs",evidence))));
            }
        }
        int supplement=0;
        boolean reviewedActivity=mode==TutorService.Mode.MODEL && ReviewedApplication.from(edition).isPresent();
        for (var expert:expertSlides.path("slides")) {
            var page=(com.fasterxml.jackson.databind.node.ObjectNode)expert.deepCopy();
            supplement++;
            if(reviewedActivity) page.put("title","教学活动 · "+expert.path("title").asText());
            else page.put("title",(mode==TutorService.Mode.MODEL?"模型补充讲解 · 待核对 ":"课程证据讲解 ")+supplement);
            pages.add(page);
        }
        if (pages.size()>80) throw new DomainException("STUDY_SLIDE_LIMIT",422,"This lesson exceeds the editable slide limit");
        return json.tree(json.write(Map.of("kind","SLIDES","schemaVersion","1.0","aspect","16:9","slides",pages)));
    }

    /** Preserve code whitespace and split long paragraphs at a readable line or sentence boundary. */
    private static List<String> pages(String body) {
        var result=new ArrayList<String>();
        int start=0;
        while (start<body.length()) {
            int cursor=start,lines=0,chars=0,lastBreak=start;
            while (cursor<body.length() && lines<10 && chars<320) {
                int cp=body.codePointAt(cursor);
                cursor+=Character.charCount(cp); chars++;
                if (cp=='\n') lines++;
                if (cp=='\n' || cp=='。' || cp=='！' || cp=='？') lastBreak=cursor;
            }
            int end=cursor<body.length() && lastBreak>start ? lastBreak : cursor;
            result.add(body.substring(start,end)); start=end;
        }
        return result.isEmpty()?List.of(""):result;
    }
}
