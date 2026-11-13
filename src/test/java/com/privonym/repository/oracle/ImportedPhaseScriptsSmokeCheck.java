package com.privonym.repository.oracle;

final class ImportedPhaseScriptsSmokeCheck {
    private ImportedPhaseScriptsSmokeCheck() { }
    static void verify() {
        String sql="ALTER TABLE &schema..T ADD C VARCHAR2(40) DEFAULT '&schema..literal';\n"
                +"UPDATE &schema..T SET C='value' WHERE ID IN (SELECT ID FROM &schema..P);\n"
                +"ALTER TABLE T SET UNUSED COLUMN C;\nUPDATE T SET X=0;";
        String bound=com.privonym.application.service.OracleSqlInventoryPreviewService.stripSchemaPlaceholder(sql);
        if(!bound.contains("ALTER TABLE T ADD")||!bound.contains("UPDATE T SET")
                ||!bound.contains("FROM P")||!bound.contains("DEFAULT '&schema..literal'"))
            throw new AssertionError("Generated phases must bind schema references without rewriting literal contents");
        System.out.println("Imported schema placeholder removal passed: DDL, UPDATE, subqueries and literal preservation.");
    }
}
