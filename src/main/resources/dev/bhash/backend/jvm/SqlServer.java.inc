    private static final String SQL_SERVER_PREFIX = "\nsqlserver-v1\n";
    private static final String SQL_SERVER = "SQL Server";

    private static java.sql.Connection sqlServerConnect(String config) {
        String url = config.substring(SQL_SERVER_PREFIX.length());
        if (!url.startsWith("jdbc:sqlserver://") || url.length() > 16384
                || url.chars().anyMatch(ch -> ch < 32)) {
            throw failure("SQL Server का JDBC संबंधपाठ दें।");
        }
        java.sql.Driver driver;
        try {
            driver = (java.sql.Driver) Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError error) {
            throw failure("Microsoft JDBC Driver for SQL Server को Java classpath में जोड़ें।");
        }
        var settings = new java.util.Properties();
        settings.setProperty("loginTimeout", "10");
        settings.setProperty("socketTimeout", "40000");
        settings.setProperty("cancelQueryTimeout", "5");
        settings.setProperty("responseBuffering", "adaptive");
        try {
            var connection = driver.connect(url, settings);
            if (connection != null) return connection;
        } catch (java.sql.SQLException | RuntimeException error) {
            // JDBC diagnostics can contain the connection URL, including its credentials.
            throw failure("SQL Server संबंध नहीं खुला।");
        }
        throw failure("SQL Server संबंध नहीं खुला।");
    }

    private record SqlServerStatement(int parameters, boolean rows) {}

    private static SqlServerStatement sqlServerStatement(String sql) {
        var verb = java.util.regex.Pattern.compile("(?i)^\\s*(SELECT|INSERT|UPDATE|DELETE)\\b").matcher(sql);
        if (!verb.find() || sql.indexOf(0) >= 0 || sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw failure("SQL Server में SELECT, INSERT, UPDATE या DELETE दें।");
        int parameters = 0;
        char quoted = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i+1 < sql.length() ? sql.charAt(i+1) : 0;
            if (quoted != 0) {
                if (ch == quoted) {
                    if (next == quoted) i++;
                    else quoted = 0;
                }
            } else if (ch == '\'' || ch == '"' || ch == '[') {
                quoted = ch == '[' ? ']' : ch;
            } else if (ch == ';' || ch == '-' && next == '-' || ch == '/' && next == '*') {
                throw failure("SQL Server कथन में बैच विभाजक या टिप्पणी स्वीकार नहीं हैं।");
            } else if (ch == '?' && ++parameters > 999) {
                throw failure("डेटाकथन मापदंड सीमा ९९९ है।");
            }
        }
        if (quoted != 0) throw failure("अमान्य SQL Server उद्धरण।");
        return new SqlServerStatement(parameters, verb.group(1).equalsIgnoreCase("SELECT"));
    }

    private static String sqlServerKind(java.sql.ResultSetMetaData meta, int column)
            throws java.sql.SQLException {
        String type = meta.getColumnTypeName(column).toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "bigint", "int", "smallint", "tinyint" -> "पूर्णांक";
            case "float", "real" -> "दशमलव";
            case "bit" -> "तार्किक";
            case "decimal", "numeric", "money", "smallmoney" -> "सटीकदशमलव";
            case "binary", "varbinary", "image", "timestamp", "rowversion" -> "बाइटबफ़र";
            case "date", "time", "datetime", "datetime2", "smalldatetime", "datetimeoffset" -> "समय";
            case "char", "varchar", "text", "nchar", "nvarchar", "ntext", "uniqueidentifier", "xml" -> "पाठ";
            default -> "असमर्थित";
        };
    }
