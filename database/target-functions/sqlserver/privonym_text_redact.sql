-- SQL Server 2016 SP1+; nvarchar(max), no CLR or external service.
-- Deterministic format profiles, not a general-purpose regular-expression engine.
CREATE OR ALTER FUNCTION dbo.PRIVONYM_TEXT_REDACT(
    @value nvarchar(max), @profile varchar(32), @replacement nvarchar(100)
)
RETURNS nvarchar(max)
AS
BEGIN
    IF @value IS NULL RETURN NULL;
    IF @replacement IS NULL SET @replacement=N'ANONYMIZED';
    DECLARE @n bigint=LEN(@value+N'#')-1, @i bigint=1, @copied bigint=1;
    DECLARE @start bigint,@finish bigint,@cursor bigint,@at bigint;
    DECLARE @token nvarchar(320),@domain nvarchar(320),@prefix nvarchar(4),@ch nchar(1);
    DECLARE @digits int,@needed int,@valid bit,@dot int;
    DECLARE @result nvarchar(max)=N'';
    WHILE @i<=@n
    BEGIN
        SET @start=@i; SET @finish=0;
        IF @profile='EMAIL'
        BEGIN
            SET @at=CHARINDEX(N'@',@value,@i);
            IF @at=0 BREAK;
            SET @start=@at-1;
            WHILE @start>=@i AND SUBSTRING(@value,@start,1) COLLATE Latin1_General_100_BIN2
                LIKE N'[A-Za-z0-9_.%+-]' SET @start=@start-1;
            SET @start=@start+1;
            SET @cursor=@at+1;
            WHILE @cursor<=@n AND SUBSTRING(@value,@cursor,1) COLLATE Latin1_General_100_BIN2
                LIKE N'[A-Za-z0-9.-]' SET @cursor=@cursor+1;
            WHILE @cursor>@at+1 AND SUBSTRING(@value,@cursor-1,1) IN(N'.',N'-') SET @cursor=@cursor-1;
            SET @domain=SUBSTRING(@value,@at+1,@cursor-@at-1);
            SET @dot=CHARINDEX(N'.',REVERSE(@domain));
            IF @start<@at AND @dot>=3 AND @dot<LEN(@domain)
               AND LEFT(REVERSE(@domain),@dot-1) COLLATE Latin1_General_100_BIN2 NOT LIKE N'%[^A-Za-z]%'
                SET @finish=@cursor-1;
            ELSE SET @i=@at+1;
        END
        ELSE IF @profile IN('PHONE_FR','PHONE_CH')
        BEGIN
            SET @cursor=@i;
            SET @prefix=CASE WHEN @profile='PHONE_FR' THEN N'+33' ELSE N'+41' END;
            IF SUBSTRING(@value,@i,3)=@prefix SET @cursor=@i+3;
            ELSE IF SUBSTRING(@value,@i,4)=CASE WHEN @profile='PHONE_FR' THEN N'0033' ELSE N'0041' END SET @cursor=@i+4;
            ELSE IF SUBSTRING(@value,@i,1)=N'0' SET @cursor=@i+1;
            IF @cursor>@i AND (@i=1 OR SUBSTRING(@value,@i-1,1) COLLATE Latin1_General_100_BIN2 NOT LIKE N'[A-Za-z0-9]')
            BEGIN
                SET @digits=0; SET @valid=1;
                WHILE @cursor<=@n AND @digits<9 AND @valid=1
                BEGIN
                    SET @ch=SUBSTRING(@value,@cursor,1);
                    IF @ch IN(N' ',N'.',N'-') SET @cursor=@cursor+1;
                    ELSE IF @ch COLLATE Latin1_General_100_BIN2 LIKE N'[0-9]'
                    BEGIN
                        IF @digits=0 AND @ch=N'0' SET @valid=0;
                        SET @digits=@digits+1; SET @cursor=@cursor+1;
                    END
                    ELSE SET @valid=0;
                END;
                IF @valid=1 AND @digits=9 AND (@cursor>@n OR
                    SUBSTRING(@value,@cursor,1) COLLATE Latin1_General_100_BIN2 NOT LIKE N'[A-Za-z0-9]')
                    SET @finish=@cursor-1;
            END;
        END
        ELSE IF @profile='IBAN_FR_CH'
        BEGIN
            SET @prefix=UPPER(SUBSTRING(@value,@i,2));
            SET @needed=CASE @prefix WHEN N'CH' THEN 21 WHEN N'FR' THEN 27 ELSE 0 END;
            IF @needed>0 AND (@i=1 OR SUBSTRING(@value,@i-1,1) COLLATE Latin1_General_100_BIN2 NOT LIKE N'[A-Za-z0-9]')
               AND SUBSTRING(@value,@i+2,2) COLLATE Latin1_General_100_BIN2 LIKE N'[0-9][0-9]'
            BEGIN
                SET @cursor=@i+4; SET @digits=4; SET @valid=1;
                WHILE @cursor<=@n AND @digits<@needed AND @valid=1
                BEGIN
                    SET @ch=UPPER(SUBSTRING(@value,@cursor,1));
                    IF @ch=N' ' SET @cursor=@cursor+1;
                    ELSE IF @ch COLLATE Latin1_General_100_BIN2 LIKE N'[A-Z0-9]'
                    BEGIN SET @digits=@digits+1; SET @cursor=@cursor+1; END
                    ELSE SET @valid=0;
                END;
                IF @valid=1 AND @digits=@needed AND (@cursor>@n OR
                    SUBSTRING(@value,@cursor,1) COLLATE Latin1_General_100_BIN2 NOT LIKE N'[A-Za-z0-9]')
                    SET @finish=@cursor-1;
            END;
        END;
        IF @finish>=@start AND @finish>0
        BEGIN
            SET @result=@result+SUBSTRING(@value,@copied,@start-@copied)+@replacement;
            SET @i=@finish+1; SET @copied=@i;
        END
        ELSE IF @profile<>'EMAIL' SET @i=@i+1;
    END;
    RETURN @result+SUBSTRING(@value,@copied,@n-@copied+1);
END;
