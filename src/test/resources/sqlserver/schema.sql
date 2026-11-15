CREATE DATABASE bhash;
GO
USE bhash;
GO
CREATE TABLE gradelevel (id BIGINT NOT NULL PRIMARY KEY, name NVARCHAR(100) NOT NULL);
CREATE TABLE student (
    id BIGINT NOT NULL PRIMARY KEY,
    name NVARCHAR(200) NOT NULL,
    gradelevel_id BIGINT NULL REFERENCES gradelevel(id),
    active BIT NOT NULL,
    score FLOAT NULL,
    note NVARCHAR(200) NULL,
    version BIGINT NOT NULL
);
GO
IF OBJECT_ID(N'dbo.gradelevel', N'U') IS NULL OR OBJECT_ID(N'dbo.student', N'U') IS NULL
    THROW 50000, 'The ORM fixture tables were not created.', 1;
GO
