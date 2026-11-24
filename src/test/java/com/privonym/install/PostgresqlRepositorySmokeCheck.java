package com.privonym.install;

import com.privonym.repository.PostgresqlRepositorySql;

/** Protect imported SQL/catalog text while adapting only repository statement syntax. */
public final class PostgresqlRepositorySmokeCheck {
    private PostgresqlRepositorySmokeCheck() { }
    public static void main(String[] args) {
        String literal = "'SYSTIMESTAMP TO_CHAR(x) job_script_seq.NEXTVAL target.x=1 ''quoted'' __PRIVONYM_PROTECTED_1__'";
        String sql = "MERGE INTO algorithm_implementation target USING(SELECT ? id FROM dual) source "
                + "ON(target.implementation_id=source.id) WHEN MATCHED THEN UPDATE SET target.implementation_code="
                + literal + ",target.created_at=SYSTIMESTAMP WHEN NOT MATCHED THEN INSERT(implementation_code) VALUES(" + literal + ")";
        String rendered = PostgresqlRepositorySql.render(sql);
        require(rendered.contains("ON(target.implementation_id=source.id)"), "MERGE predicate was changed");
        require(rendered.contains("SET implementation_code=" + literal), "Imported literal or assignment was changed");
        require(rendered.contains(",created_at=statement_timestamp()"), "Repository timestamp was not rendered");
        require(rendered.endsWith("VALUES(" + literal + ")"), "Protected placeholders leaked into imported text");
        String comments = "SELECT TO_CHAR(started_at,'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM') FROM anonymization_job -- SYSTIMESTAMP\n";
        require(PostgresqlRepositorySql.render(comments).contains("-- SYSTIMESTAMP"), "Comment was rewritten");
        String scripts = "/* outer /* inner ; */ done */ CREATE FUNCTION f() RETURNS text LANGUAGE sql AS $body$ SELECT ';' $body$; -- separator ;\nSELECT 'a;''b';";
        var statements = PostgresqlScripts.statements(scripts);
        require(statements.size() == 2 && statements.get(0).contains("SELECT ';'"), "Function body was split");
        require(statements.get(1).equals("SELECT 'a;''b'"), "Escaped literal was split");
        System.out.println("PostgreSQL repository smoke checks passed: literal preservation, MERGE rendering and function script boundaries.");
    }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
