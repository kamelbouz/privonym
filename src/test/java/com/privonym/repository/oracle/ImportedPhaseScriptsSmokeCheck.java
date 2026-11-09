package com.privonym.repository.oracle;

final class ImportedPhaseScriptsSmokeCheck {
    private ImportedPhaseScriptsSmokeCheck() { }
    static void verify() {
        String sql="ALTER TABLE &schema..T ADD C VARCHAR2(40) DEFAULT '&schema..literal';\n"
                +"UPDATE &schema..T SET C='value' WHERE ID IN (SELECT ID FROM &schema..P);\n"
                +"ALTER TABLE T SET UNUSED COLUMN C;\nUPDATE T SET X=0;";
        String bound=ImportedPhaseScripts.bindSchema(sql,"TARGET");
        if(!bound.contains("ALTER TABLE \"TARGET\".T ADD")||!bound.contains("UPDATE \"TARGET\".T SET")
                ||!bound.contains("FROM \"TARGET\".P")||!bound.contains("DEFAULT '&schema..literal'"))
            throw new AssertionError("Generated phases must bind schema references without rewriting literal contents");
        System.out.println("Imported phase schema binding passed: DDL, UPDATE, subqueries and literal preservation.");
    }
}
