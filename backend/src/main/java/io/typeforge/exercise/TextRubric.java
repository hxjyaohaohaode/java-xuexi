package io.typeforge.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import io.typeforge.shared.DomainException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bounded exact short-answer matching. It never infers meaning or evaluates an arbitrary essay. */
public final class TextRubric {
    public static final String VERSION="text-exact-nfkc-v1";
    public static final String NORMALIZATION="NFKC_WHITESPACE";
    public static final int MAX_CHARACTERS=4000;
    public static final int MAX_UTF8_BYTES=16000;
    private static final Set<String> FIELDS=Set.of("version","scope","caseSensitive","acceptedAnswers","rejectedAnswers");
    private TextRubric() {}

    /** Editorial-only. Accepted answers and calibration negatives must never enter learner DTOs. */
    public record Spec(String version,String scope,boolean caseSensitive,List<String> acceptedAnswers,List<String> rejectedAnswers) {
        public Spec {
            acceptedAnswers=acceptedAnswers==null?null:List.copyOf(acceptedAnswers);
            rejectedAnswers=rejectedAnswers==null?null:List.copyOf(rejectedAnswers);
        }
    }
    public record Policy(String version,String normalization,boolean caseSensitive,int maxCharacters,int maxUtf8Bytes,String scope) {}
    public static boolean configured(JsonNode answer) {return answer!=null && answer.hasNonNull("textRubric");}
    public static boolean supports(String kind) {return Set.of("READING","DIAGNOSIS").contains(kind);}
    public static Policy policy(Spec spec) {
        return new Policy(VERSION,NORMALIZATION,spec.caseSensitive(),MAX_CHARACTERS,MAX_UTF8_BYTES,spec.scope());
    }
    public static void validate(Spec spec) {
        if(spec==null || !VERSION.equals(spec.version()) || spec.scope()==null || spec.scope().isBlank() || spec.scope().length()>800)
            throw DomainException.invalid("A versioned exact short-answer rubric needs an explicit scope of at most 800 characters");
        Set<String> accepted=normalized(spec.acceptedAnswers(),spec.caseSensitive());
        Set<String> rejected=normalized(spec.rejectedAnswers(),spec.caseSensitive());
        if(!java.util.Collections.disjoint(accepted,rejected)) throw DomainException.invalid("Accepted and rejected examples must remain distinct after the declared normalization");
    }
    private static Set<String> normalized(List<String> examples,boolean caseSensitive) {
        if(examples==null || examples.isEmpty() || examples.size()>16) throw DomainException.invalid("Supply 1..16 exact accepted answers and 1..16 calibration negatives");
        var values=new HashSet<String>();
        for(String example:examples) if(!values.add(normalize(example,caseSensitive))) throw DomainException.invalid("Rubric examples must be distinct after normalization");
        return values;
    }
    public static Spec parse(JsonNode answer) {
        try {
            JsonNode value=answer.path("textRubric");var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
            if(!value.isObject() || !fields.equals(FIELDS) || !value.path("version").isTextual() || !value.path("scope").isTextual()
                || !value.path("caseSensitive").isBoolean()) throw DomainException.invalid("Malformed exact short-answer rubric");
            Spec spec=new Spec(value.path("version").asText(),value.path("scope").asText(),value.path("caseSensitive").asBoolean(),
                strings(value.path("acceptedAnswers")),strings(value.path("rejectedAnswers")));
            validate(spec);return spec;
        } catch(DomainException | IllegalArgumentException invalid) {
            throw new DomainException("INVALID_PUBLISHED_EXERCISE",503,"The exact short-answer rubric needs editorial repair; no learning result was recorded");
        }
    }
    private static List<String> strings(JsonNode array) {
        if(!array.isArray()) throw DomainException.invalid("Expected bounded rubric examples");
        var result=new ArrayList<String>();for(JsonNode item:array){if(!item.isTextual())throw DomainException.invalid("Rubric examples must be text");result.add(item.asText());}return result;
    }
    public static boolean matches(Spec spec,String answer) {
        String actual=normalize(answer,spec.caseSensitive());
        return spec.acceptedAnswers().stream().anyMatch(expected->normalize(expected,spec.caseSensitive()).equals(actual));
    }
    public static String normalize(String text,boolean caseSensitive) {
        if(text==null || text.length()>MAX_CHARACTERS || text.getBytes(StandardCharsets.UTF_8).length>MAX_UTF8_BYTES)
            throw DomainException.invalid("Short answers are limited to 4000 UTF-16 characters and 16000 UTF-8 bytes");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=text.length() || !Character.isLowSurrogate(text.charAt(i)))throw DomainException.invalid("Unpaired Unicode surrogate");}
            else if(Character.isLowSurrogate(c))throw DomainException.invalid("Unpaired Unicode surrogate");
        }
        var normalized=new StringBuilder();boolean space=false;
        String canonical=Normalizer.normalize(text,Normalizer.Form.NFKC);
        for(int i=0;i<canonical.length();) {
            int cp=canonical.codePointAt(i);i+=Character.charCount(cp);
            if(Character.getType(cp)==Character.FORMAT || Character.isISOControl(cp) && cp!='\t' && cp!='\r' && cp!='\n')
                throw DomainException.invalid("Invisible format and control characters are not valid short-answer content");
            if(Character.isWhitespace(cp) || Character.isSpaceChar(cp)){space=normalized.length()>0;continue;}
            if(space){normalized.append(' ');space=false;}normalized.appendCodePoint(cp);
        }
        String value=caseSensitive?normalized.toString():normalized.toString().toLowerCase(Locale.ROOT);
        if(value.isEmpty() || value.length()>MAX_CHARACTERS || value.getBytes(StandardCharsets.UTF_8).length>MAX_UTF8_BYTES)
            throw DomainException.invalid("Normalized short answer is empty or exceeds its bounded size");
        return value;
    }
}
