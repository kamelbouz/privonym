package com.privonym.repository.oracle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Local Discovery heuristics only. No raw value or matched passage escapes this analyzer. */
final class DiscoveryTextAnalyzer {
    static final int MAX_CHARACTERS = 16384;
    private static final String WORD = "[\\p{Lu}][\\p{L}'’-]{1,40}";
    private static final List<Detector> DETECTORS = List.of(
        detector("EMAIL", "(?<![\\p{L}\\d_.%+\\-])[A-Za-z0-9_.%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}(?![A-Za-z])"),
        detector("IBAN_FR_CH", "(?i)(?<![A-Z0-9])(?:CH[0-9]{2}(?: ?[A-Z0-9]){17}|FR[0-9]{2}(?: ?[A-Z0-9]){23})(?![A-Z0-9])"),
        detector("PHONE_FR", "(?<![\\p{L}\\d])(?:\\+33|0033)[ .-]?[1-9](?:[ .-]?[0-9]{2}){4}(?![\\p{L}\\d])"),
        detector("PHONE_CH", "(?<![\\p{L}\\d])(?:\\+41|0041)[ .-]?[1-9][0-9][ .-]?[0-9]{3}(?:[ .-]?[0-9]{2}){2}(?![\\p{L}\\d])"),
        detector("PHONE_FR_CH", "(?<![\\p{L}\\d])0[ .-]?[1-9](?:[ .-]?[0-9]){8}(?![\\p{L}\\d])"),
        detector("PERSON_CONTEXT", "(?<![\\p{L}])(?:(?i:Mme|Madame|Monsieur|Mlle|M|Mr|Mrs|Dr|Herr|Frau)\\.?[ \\t]+)"+WORD+"(?:[ \\t]+"+WORD+"){0,2}"),
        detector("ADDRESS_CONTEXT", "(?iu)\\b(?:[0-9]{1,5}[ ,]+(?:rue|avenue|av\\.|boulevard|chemin|route|street|road|strasse)\\b|(?:rue|avenue|boulevard|chemin|route|strasse)[ \\t]+(?:de[ \\t]+|du[ \\t]+|des[ \\t]+)?[\\p{L}][\\p{L}'’-]{1,40})"),
        detector("ORGANIZATION_CONTEXT", "(?<![\\p{L}])(?i:société|entreprise|company|organisation|organization|firma)[ \\t]+"+WORD+"(?:[ \\t]+"+WORD+"){0,2}")
    );

    private DiscoveryTextAnalyzer() { }
    private static Detector detector(String entity,String regex) { return new Detector(entity,Pattern.compile(regex)); }

    static Analysis analyze(List<String> samples) {
        Set<String> entities=new LinkedHashSet<>();
        boolean embedded=false;
        int values=0;
        for(String sample:samples) {
            if(sample==null||sample.isBlank())continue;
            values++;
            String text=sample.substring(0,Math.min(sample.length(),MAX_CHARACTERS)).strip();
            List<Span> occupied=new ArrayList<>();
            for(Detector detector:DETECTORS) {
                var matcher=detector.pattern().matcher(text);
                int matches=0;
                while(matches++<100&&matcher.find()) {
                    int start=matcher.start(),end=matcher.end();
                    if(occupied.stream().anyMatch(span->start<span.end()&&end>span.start()))continue;
                    occupied.add(new Span(start,end));entities.add(detector.entity());
                    if(start!=0||end!=text.length())embedded=true;
                }
            }
        }
        boolean context=entities.stream().anyMatch(e->e.endsWith("_CONTEXT"));
        String algorithm="";
        if(context)algorithm="NULLIFY";
        else if(entities.size()>1||entities.contains("PHONE_FR_CH"))algorithm="TEXT_REDACT_COMMON";
        else if(!entities.isEmpty())algorithm=switch(entities.iterator().next()) {
            case "EMAIL" -> "TEXT_REDACT_EMAIL";
            case "PHONE_FR" -> "TEXT_REDACT_PHONE_FR";
            case "PHONE_CH" -> "TEXT_REDACT_PHONE_CH";
            case "IBAN_FR_CH" -> "TEXT_REDACT_IBAN_FR_CH";
            default -> "";
        };
        String reason=context
                ? "Context clues found. No current algorithm selectively removes these names/addresses/organizations; NULLIFY removes the entire value. Manual review required."
                : "Format evidence in sampled content; text redaction preserves surrounding text. Review detection coverage before use.";
        return new Analysis(List.copyOf(entities),context?75:95,algorithm,reason,values,embedded);
    }

    record Analysis(List<String> entities,int score,String algorithm,String reason,int sampledValues,boolean embedded) { }
    private record Detector(String entity,Pattern pattern) { }
    private record Span(int start,int end) { }
}
