package com.privonym.repository.oracle;

import com.privonym.application.service.MaskingAlgorithmOption;
import java.util.List;

public final class DiscoveryTextSmokeCheck {
    private DiscoveryTextSmokeCheck() { }
    public static void main(String[] args) {
        expect("Contact alice@example.com pour le dossier.",List.of("EMAIL"),"TEXT_REDACT_EMAIL");
        expect("+33 6 12 34 56 78",List.of("PHONE_FR"),"TEXT_REDACT_PHONE_FR");
        expect("+41 79 123 45 67",List.of("PHONE_CH"),"TEXT_REDACT_PHONE_CH");
        expect("079 123 45 67",List.of("PHONE_FR_CH"),"TEXT_REDACT_COMMON");
        expect("CH93 0076 2011 6238 5295 7",List.of("IBAN_FR_CH"),"TEXT_REDACT_IBAN_FR_CH");
        expect("alice@example.com; +41 79 123 45 67",List.of("EMAIL","PHONE_CH"),"TEXT_REDACT_COMMON");
        expect("Mme Dupont habite au 12 rue des Lilas. Contact alice@example.com",
                List.of("EMAIL","PERSON_CONTEXT","ADDRESS_CONTEXT"),"NULLIFY");
        expect("La société Acme travaille ici.",List.of("ORGANIZATION_CONTEXT"),"NULLIFY");
        require(DiscoveryTextAnalyzer.analyze(List.of("Rapport Technique 2026-10-25", "Texte ordinaire.")).entities().isEmpty(),
                "ordinary capitalized text must not be guessed to be a name");
        require(DiscoveryTextAnalyzer.analyze(java.util.Arrays.asList(null,"", "   ")).entities().isEmpty(),"NULL/empty changed");
        require(DiscoveryTextAnalyzer.analyze(List.of("X".repeat(20000)+" alice@example.com")).entities().isEmpty(),
                "analysis must respect prefix bound");
        var analysis=DiscoveryTextAnalyzer.analyze(List.of("CONFIDENTIAL alice@example.com"));
        require(!analysis.toString().contains("alice@example.com")&&!analysis.toString().contains("CONFIDENTIAL"),
                "raw passages must not escape analysis");
        require(DiscoveryContentScan.choose(List.of(),"TEXT_REDACT_COMMON").isEmpty(),"unavailable algorithm must not be invented");
        var available=List.of(new MaskingAlgorithmOption("DATE_SHIFT_DETERMINISTIC","Date","GENERALIZATION","PUBLISHED",List.of()));
        require(DiscoveryContentScan.choose(available,"TEXT_REDACT_COMMON").isEmpty(),"must not fall back to arbitrary first algorithm");
        for(String technology:List.of("ORACLE","POSTGRESQL","SQLSERVER")) {
            String sql=DiscoveryContentScan.sampleSql(technology,"Odd]schema\"","Table]with\"quotes","Field]with\"quotes");
            require(sql.startsWith("SELECT ")&&!sql.contains("UPDATE ")&&sql.contains("16384"),"bounded source SELECT required");
            require(sql.contains(technology.equals("SQLSERVER")?"[Odd]]schema\"]":"\"Odd]schema\"\"\""),"identifiers must be escaped");
        }
        require(DiscoveryContentScan.textType("CLOB")&&DiscoveryContentScan.textType("text")
                &&DiscoveryContentScan.textType("nvarchar")&&!DiscoveryContentScan.textType("NUMBER")
                &&!DiscoveryContentScan.textType("BLOB"),"only supported textual types may be sampled");
        require(DiscoveryContentScan.metadataAlgorithm("EMAIL","NUMBER").equals("NULLIFY")
                &&DiscoveryContentScan.metadataAlgorithm("BIRTH_DATE","VARCHAR2").equals("NULLIFY")
                &&DiscoveryContentScan.metadataAlgorithm("BIRTH_DATE","DATE").equals("DATE_SHIFT_DETERMINISTIC"),
                "recommendations must respect native column types");
        System.out.println("Discovery content checks passed: embedded formats, local context clues, safe recommendations, privacy and bounded source SELECTs.");
    }
    private static void expect(String text,List<String> entities,String code) {
        var result=DiscoveryTextAnalyzer.analyze(List.of(text));
        require(result.entities().equals(entities)&&result.algorithm().equals(code),"unexpected analysis: "+result);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
