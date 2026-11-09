package com.privonym.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Preserves reviewed scalar SQL in its source dialect; never translates functions. */
public final class ImportedSqlExpression {
    private static Set<String> names(String text) { return Set.of(text.split(" ")); }
    private static final Set<String> COMMON = names("CONCAT LOWER UPPER TRIM LTRIM RTRIM REPLACE TRANSLATE COALESCE NULLIF ABS ACOS ASIN ATAN ATAN2 COS SIN TAN EXP FLOOR LOG POWER ROUND SIGN SQRT CAST");
    private static final Map<String,Set<String>> FUNCTIONS = Map.of(
        "ORACLE", names("SUBSTR SUBSTRB SUBSTRC SUBSTR2 SUBSTR4 INSTR INSTRB INSTRC INSTR2 INSTR4 LENGTH LENGTHB LENGTHC LENGTH2 LENGTH4 NVL NVL2 NANVL REGEXP_REPLACE REGEXP_SUBSTR REGEXP_INSTR REGEXP_COUNT REGEXP_LIKE CHR NCHR ASCII ASCIISTR INITCAP LPAD RPAD SOUNDEX TO_CHAR TO_NCHAR TO_DATE TO_TIMESTAMP TO_TIMESTAMP_TZ TO_NUMBER TO_CLOB TO_NCLOB TO_BINARY_FLOAT TO_BINARY_DOUBLE ROUND TRUNC MOD REMAINDER CEIL DECODE GREATEST LEAST ADD_MONTHS MONTHS_BETWEEN LAST_DAY NEXT_DAY EXTRACT STANDARD_HASH ORA_HASH RAWTOHEX HEXTORAW SYS_GUID BITAND LN LOG COSH SINH TANH WIDTH_BUCKET FROM_TZ SYS_EXTRACT_UTC NUMTODSINTERVAL NUMTOYMINTERVAL TO_DSINTERVAL TO_YMINTERVAL JSON_VALUE JSON_QUERY JSON_OBJECT JSON_ARRAY NLS_LOWER NLS_UPPER NLS_INITCAP NLSSORT UNISTR DUMP VSIZE"),
        "SQLSERVER", names("CONCAT_WS CHAR NCHAR ASCII UNICODE CHARINDEX PATINDEX LEFT RIGHT LEN DATALENGTH REPLICATE REVERSE SPACE STUFF STRING_ESCAPE SUBSTRING FORMAT QUOTENAME SOUNDEX DIFFERENCE ISNULL IIF CHOOSE CONVERT TRY_CAST TRY_CONVERT TRY_PARSE PARSE HASHBYTES CHECKSUM BINARY_CHECKSUM NEWID RAND GETDATE GETUTCDATE SYSDATETIME SYSUTCDATETIME SYSDATETIMEOFFSET DATEADD DATEDIFF DATEDIFF_BIG DATEPART DATENAME DATEFROMPARTS DATETIMEFROMPARTS DATETIME2FROMPARTS SMALLDATETIMEFROMPARTS TIMEFROMPARTS DATETIMEOFFSETFROMPARTS YEAR MONTH DAY EOMONTH DATETRUNC DATE_BUCKET CEILING SQUARE LOG10 DEGREES RADIANS PI JSON_VALUE JSON_QUERY JSON_MODIFY ISJSON COMPRESS DECOMPRESS"),
        "POSTGRESQL", names("CONCAT_WS SUBSTR SUBSTRING OVERLAY POSITION STRPOS SPLIT_PART REPEAT REVERSE LEFT RIGHT LENGTH CHAR_LENGTH CHARACTER_LENGTH OCTET_LENGTH BIT_LENGTH ASCII CHR INITCAP LPAD RPAD BTRIM TRANSLATE REGEXP_REPLACE REGEXP_MATCH REGEXP_SUBSTR REGEXP_INSTR REGEXP_COUNT REGEXP_LIKE FORMAT QUOTE_LITERAL QUOTE_NULLABLE QUOTE_IDENT MD5 SHA224 SHA256 SHA384 SHA512 ENCODE DECODE DIGEST GEN_RANDOM_UUID UUID_GENERATE_V4 RANDOM CEIL TRUNC MOD DIV LN LOG10 CBRT DEGREES RADIANS PI GREATEST LEAST TO_CHAR TO_DATE TO_TIMESTAMP TO_NUMBER DATE_TRUNC DATE_PART EXTRACT AGE MAKE_DATE MAKE_TIME MAKE_TIMESTAMP MAKE_TIMESTAMPTZ MAKE_INTERVAL NOW CLOCK_TIMESTAMP STATEMENT_TIMESTAMP TRANSACTION_TIMESTAMP TIMEZONE JSON_BUILD_OBJECT JSONB_BUILD_OBJECT JSON_BUILD_ARRAY JSONB_BUILD_ARRAY TO_JSON TO_JSONB JSONB_SET JSONB_EXTRACT_PATH_TEXT JSON_EXTRACT_PATH_TEXT REPLACE ARRAY_TO_STRING STRING_TO_ARRAY CARDINALITY"));
    private static final Set<String> FORBIDDEN = names("SELECT INSERT UPDATE DELETE MERGE DROP ALTER CREATE COMMIT ROLLBACK EXEC EXECUTE GRANT REVOKE UNION CONNECT PRIOR NEXTVAL CURRVAL INTO OVER FILTER OPENROWSET OPENQUERY");
    private static final Set<String> SYNTAX = names("IN CASE WHEN THEN ELSE AND OR NOT VARCHAR VARCHAR2 NVARCHAR NVARCHAR2 CHAR NCHAR NUMBER NUMERIC DECIMAL FLOAT REAL DOUBLE TIMESTAMP TIME INTERVAL BIT BINARY VARBINARY CHARACTER");
    private ImportedSqlExpression() { }

    public static String algorithm(String technology) {
        String dialect = normalize(technology);
        if (!FUNCTIONS.containsKey(dialect)) throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect);
        return dialect + "_EXPRESSION_EXACT";
    }
    public static boolean isExact(String code) {
        return FUNCTIONS.keySet().stream().anyMatch(engine -> algorithm(engine).equals(code));
    }
    public static String validateAlgorithm(String code,String technology,String expression) {
        if (!algorithm(technology).equals(code)) throw new IllegalArgumentException("Imported expression belongs to another SQL dialect");
        return validate(technology,expression);
    }
    public static String validate(String technology,String expression) {
        String dialect=normalize(technology);
        algorithm(dialect);
        if(expression==null||expression.isBlank()||expression.length()>4000||expression.contains("${"))
            throw new IllegalArgumentException("Imported expression must contain 1–4000 characters and no template tokens");
        List<String> tokens=lex(expression,dialect); int depth=0;
        for(int i=0;i<tokens.size();i++) {
            String token=tokens.get(i), next=i+1<tokens.size()?tokens.get(i+1):"";
            if(token.startsWith("$")&&dialect.equals("POSTGRESQL"))continue;
            if(token.startsWith("'")||token.startsWith("\"")||token.startsWith("[")) {
                if(!token.startsWith("'")&&next.equals("("))throw new IllegalArgumentException("Quoted function calls require review");
                continue;
            }
            if(FORBIDDEN.contains(token)||token.equals(";")||token.equals("&")&&!dialect.equals("SQLSERVER")
                    ||token.equals("-")&&next.equals("-")||token.equals("/")&&next.equals("*"))
                throw new IllegalArgumentException("Unsupported token in imported expression: "+token);
            if(token.equals(".")) {
                if(i==0||!tokens.get(i-1).matches("[0-9]+")||!next.matches("[0-9]+"))
                    throw new IllegalArgumentException("Qualified references and package calls require review");
                continue;
            }
            if(token.equals(":")) {
                if(!dialect.equals("POSTGRESQL")||!next.equals(":"))throw new IllegalArgumentException("Runtime bind variables require review");
                i++;continue;
            }
            if(token.matches("[A-Z_][A-Z0-9_$#]*")&&next.equals("(")
                    &&!COMMON.contains(token)&&!FUNCTIONS.get(dialect).contains(token)&&!SYNTAX.contains(token))
                throw new IllegalArgumentException("Unsupported "+dialect+" scalar function: "+token);
            if(token.equals("("))depth++;
            if(token.equals(")")&&--depth<0)throw new IllegalArgumentException("Unbalanced imported expression");
            if(!token.matches("[A-Z0-9_$#]+|[()+*/|=,<>!%~^&-]"))throw new IllegalArgumentException("Unsupported expression token: "+token);
        }
        if(depth!=0)throw new IllegalArgumentException("Unbalanced imported expression");
        return expression;
    }
    private static String normalize(String technology) { return technology==null?"":technology.trim().toUpperCase(Locale.ROOT); }
    private static List<String> lex(String expression,String dialect) {
        List<String> tokens=new ArrayList<>();
        for(int i=0;i<expression.length();) {
            char c=expression.charAt(i); if(Character.isWhitespace(c)){i++;continue;}
            int start=i++;
            if(c=='$'&&dialect.equals("POSTGRESQL")) {
                var marker=java.util.regex.Pattern.compile("^\\$[A-Za-z0-9_]*\\$").matcher(expression.substring(start));
                if(!marker.find())throw new IllegalArgumentException("Unsupported dollar token");
                String delimiter=marker.group();int end=expression.indexOf(delimiter,start+delimiter.length());
                if(end<0)throw new IllegalArgumentException("Unclosed dollar-quoted SQL literal");
                i=end+delimiter.length();tokens.add(expression.substring(start,i));continue;
            }
            if(c=='\''||c=='"'||c=='['&&dialect.equals("SQLSERVER")) {
                char end=c=='['?']':c; boolean closed=false;
                while(i<expression.length())if(expression.charAt(i++)==end){if(i<expression.length()&&expression.charAt(i)==end){i++;continue;}closed=true;break;}
                if(!closed)throw new IllegalArgumentException("Unclosed SQL literal or identifier");
                tokens.add(expression.substring(start,i));
            } else if(Character.isLetterOrDigit(c)||c=='_'||c=='$'||c=='#') {
                while(i<expression.length()&&(Character.isLetterOrDigit(expression.charAt(i))||"_$#".indexOf(expression.charAt(i))>=0))i++;
                tokens.add(expression.substring(start,i).toUpperCase(Locale.ROOT));
            } else tokens.add(String.valueOf(c));
        }
        return tokens;
    }
}
