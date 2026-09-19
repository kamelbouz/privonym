-- Install once in the SQL Server target database used by Privonym.
CREATE OR ALTER FUNCTION dbo.PRIVONYM_IBAN_GENERATE(
    @value nvarchar(128),
    @key nvarchar(512)
)
RETURNS nvarchar(128)
AS
BEGIN
    IF @value IS NULL RETURN NULL;
    IF @key IS NULL OR LEN(@key) = 0 RETURN NULL;

    DECLARE @clean nvarchar(128) = UPPER(REPLACE(REPLACE(@value, N' ', N''), N'-', N''));
    IF LEN(@clean) NOT BETWEEN 15 AND 34
       OR UNICODE(SUBSTRING(@clean, 1, 1)) NOT BETWEEN 65 AND 90
       OR UNICODE(SUBSTRING(@clean, 2, 1)) NOT BETWEEN 65 AND 90 RETURN NULL;

    DECLARE @country nvarchar(2) = LEFT(@clean, 2);
    DECLARE @hash varchar(64) = LOWER(CONVERT(varchar(64),
        HASHBYTES('SHA2_256', CONVERT(varbinary(max), CONCAT(@key, N':', @clean))), 2));
    DECLARE @bban nvarchar(128) = N'';
    DECLARE @i int = 1;
    DECLARE @hex int;
    WHILE @i <= LEN(@clean) - 4
    BEGIN
        SET @hex = CHARINDEX(SUBSTRING(@hash, ((@i - 1) % LEN(@hash)) + 1, 1),
            '0123456789abcdef') - 1;
        SET @bban = CONCAT(@bban, CONVERT(varchar(1), @hex % 10));
        SET @i += 1;
    END;

    DECLARE @numeric varchar(256) = CONCAT(@bban,
        UNICODE(SUBSTRING(@country, 1, 1)) - 55,
        UNICODE(SUBSTRING(@country, 2, 1)) - 55, '00');
    DECLARE @remainder int = 0;
    SET @i = 1;
    WHILE @i <= LEN(@numeric)
    BEGIN
        SET @remainder = (@remainder * 10 + CONVERT(int, SUBSTRING(@numeric, @i, 1))) % 97;
        SET @i += 1;
    END;
    RETURN CONCAT(@country, RIGHT(CONCAT('0', 98 - @remainder), 2), @bban);
END;
GO

