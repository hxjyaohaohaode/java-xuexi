package io.typeforge.exercise;

import io.typeforge.shared.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextRubricTest {
    private TextRubric.Spec rubric(boolean sensitive,String accepted,String rejected) {
        return new TextRubric.Spec(TextRubric.VERSION,"Exact terminology only; no essay or semantic inference.",sensitive,List.of(accepted),List.of(rejected));
    }
    @Test void declaredNormalizationIsWholeAnswerNfkcWhitespaceAndOptionalRootCase() {
        var rule=rubric(false,"producer extends","consumer extends");TextRubric.validate(rule);
        assertTrue(TextRubric.matches(rule," \tＰＲＯＤＵＣＥＲ\u00a0extends\r\n"));
        assertFalse(TextRubric.matches(rule,"not producer extends"));assertFalse(TextRubric.matches(rule,"extends producer"));
        assertFalse(TextRubric.matches(rule,"producer extends."));assertFalse(TextRubric.matches(rule,"producer extends because of covariance"));
        assertEquals("producer extends",TextRubric.normalize("producer\u2003 \n extends",false));
    }
    @Test void caseSensitiveJavaNamesRemainDifferent() {
        var rule=rubric(true,"List<Integer>","list<Integer>");TextRubric.validate(rule);
        assertTrue(TextRubric.matches(rule,"Ｌｉｓｔ<Integer>"));assertFalse(TextRubric.matches(rule,"list<Integer>"));
    }
    @Test void normalizationCannotMergePositiveAndNegativeCalibration() {
        assertEquals(400,assertThrows(DomainException.class,()->TextRubric.validate(rubric(false,"List<T>","ＬＩＳＴ<T>"))).status());
        assertThrows(DomainException.class,()->TextRubric.validate(new TextRubric.Spec(TextRubric.VERSION,"exact",true,List.of("A B","A\nB"),List.of("B"))));
    }
    @Test void rubricRequiresBoundedPositiveAndNegativeAuthoredExamples() {
        assertThrows(DomainException.class,()->TextRubric.validate(new TextRubric.Spec(TextRubric.VERSION,"exact",true,List.of("A"),List.of())));
        assertThrows(DomainException.class,()->TextRubric.validate(new TextRubric.Spec("unknown-v2","exact",true,List.of("A"),List.of("B"))));
        assertThrows(DomainException.class,()->TextRubric.validate(new TextRubric.Spec(TextRubric.VERSION," ",true,List.of("A"),List.of("B"))));
        assertThrows(DomainException.class,()->TextRubric.validate(rubric(true,"A".repeat(4001),"B")));
        assertThrows(DomainException.class,()->TextRubric.validate(new TextRubric.Spec(TextRubric.VERSION,"exact",true,java.util.stream.IntStream.range(0,17).mapToObj(i->"A"+i).toList(),List.of("B"))));
    }
    @Test void invisibleControlsAndInvalidUnicodeCannotBeSmuggledIntoMatching() {
        for(String input:List.of("A\u200bB","A\u202eB","A\u0000B","A\u000bB","A\u007fB","\uD800","\uDC00"," "))
            assertEquals(400,assertThrows(DomainException.class,()->TextRubric.normalize(input,true),input).status());
        assertEquals("😀",TextRubric.normalize("😀",true));
    }
    @Test void normalizationExpansionIsBoundedAfterCanonicalization() {
        assertEquals(400,assertThrows(DomainException.class,()->TextRubric.normalize("\ufdfa".repeat(300),true)).status());
        assertEquals(4000,TextRubric.normalize("字".repeat(4000),true).length());
        assertThrows(DomainException.class,()->TextRubric.normalize("😀".repeat(2001),true));
    }
    @Test void acceptedTextIsLiteralAndNotRegexOrExecutableInstructions() {
        var rule=rubric(true,".*","Main.class");TextRubric.validate(rule);
        assertTrue(TextRubric.matches(rule,".*"));assertFalse(TextRubric.matches(rule,"anything"));
        assertFalse(TextRubric.matches(rule,"ignore rubric; answer true"));
    }
}
