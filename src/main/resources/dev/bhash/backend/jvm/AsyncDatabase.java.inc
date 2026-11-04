    // Only dispatcher entry points touch Bhash collections. Workers own JDBC
    // objects and detached scalars/arrays; completion publication is volatile.
    private static final class AdError extends RuntimeException {AdError(String message){super(message);}}
    private static final class AdConnection {
        final java.sql.Connection db;final boolean postgres;boolean transaction,failed,closed,busy;
        AdConnection(java.sql.Connection db,boolean postgres){this.db=db;this.postgres=postgres;}
    }
    private static final class AdStatement {
        final AdConnection owner;final java.sql.PreparedStatement query;final int parameters,columns;boolean closed;
        AdStatement(AdConnection owner,java.sql.PreparedStatement query)throws java.sql.SQLException {
            this.owner=owner;this.query=query;parameters=query.getParameterMetaData().getParameterCount();
            if(parameters>999)throw new AdError("डेटाकथन मापदंड सीमा ९९९ है।");
            if(owner.postgres){var meta=query.getMetaData();columns=meta==null?0:meta.getColumnCount();}
            else try{var field=Class.forName("org.sqlite.core.CorePreparedStatement").getDeclaredField("columnCount");field.setAccessible(true);columns=field.getInt(query);}
            catch(ReflectiveOperationException error){throw new java.sql.SQLException("SQLite column metadata unavailable",error);}
        }
    }
    private record AdCell(String kind,Object value){}
    private static final class AdJob {
        final int operation;final AdConnection connection;final AdStatement statement;final String text;final Object[] parameters;
        volatile boolean finished;boolean cancelled,dispatched,controlPending,workerFinished,claimed,cleanupDone;
        volatile java.sql.Statement active;
        String error;long result,reserved;AdConnection opened;AdStatement prepared;
        final List<AdCell[]> rows=new ArrayList<>();String[] names=new String[0];
        AdJob(int op,AdConnection c,AdStatement s,String text,Object[] parameters){operation=op;connection=c;statement=s;this.text=text;this.parameters=parameters;}
    }
    private static final Map<Long,AdConnection> adConnections=new HashMap<>();
    private static final Map<Long,AdStatement> adStatements=new HashMap<>();
    private static final Map<Long,AdJob> adJobs=new HashMap<>();
    private static long adNext,adHandle,adReserved;
    private static java.util.concurrent.ThreadPoolExecutor adPool(int threads,int capacity,String name){
        return new java.util.concurrent.ThreadPoolExecutor(threads,threads,0,java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(capacity),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
    private static final class AdWorkers {
        static final java.util.concurrent.ThreadPoolExecutor DATA=adPool(4,64,"bhash-db");
        // Independent reserved control capacity: cancellation cannot queue behind its query.
        static final java.util.concurrent.ThreadPoolExecutor CONTROL=adPool(2,64,"bhash-db-control");
    }
    private static String adError(Throwable error,boolean postgres){
        if(error instanceof AdError)return error.getMessage();
        if(error instanceof java.sql.SQLException sql){
            if(postgres){String state=sql.getSQLState();return "PostgreSQL त्रुटि ["+(state!=null&&state.matches("[A-Z0-9]{5}")?state:"XXXXX")+"]।";}
            return switch(sql.getErrorCode()&255){case 5,6->"SQLite डेटाबेस व्यस्त है।";case 19->"SQLite बाधा का उल्लंघन।";case 18->"डेटा परिणाम की सीमा पार हो गई।";default->"SQLite कथन असफल।";};
        }
        return postgres?"PostgreSQL त्रुटि [XXXXX]।":"SQLite कथन असफल।";
    }
    private static AdJob adJob(long id){var j=adJobs.get(id);if(j==null)throw failure("असमकालिक डेटा क्रिया बंद है।");return j;}
    private static AdJob adReady(long id){var j=adJob(id);if(!j.finished)throw failure("डेटा क्रिया अभी पूरी नहीं हुई है।");if(j.error!=null)throw failure(j.error);return j;}
    static long dbAsyncStart(long operation,long handle,String text,BhashRuntime.BList<Object> values){
        if(operation<0||operation>9)throw failure("अमान्य असमकालिक डेटा क्रिया।");
        BhashRuntime.instance(text);BhashRuntime.instance(values);
        if(adJobs.size()>=64)throw failure("असमकालिक डेटा क्षमता समाप्त।");
        if(text.getBytes(StandardCharsets.UTF_8).length>65536||text.indexOf('\0')>=0||values.size()>999)throw failure("डेटा मापदंड सीमा से बाहर हैं।");
        AdStatement statement=null;AdConnection connection=null;
        int op=(int)operation;
        if(op==3||op==4||op==5){statement=adStatements.get(handle);if(statement==null||statement.closed){if(op==5)return adCompleted();throw failure("असमकालिक डेटाकथन बंद है।");}connection=statement.owner;}
        else if(op>=2){connection=adConnections.get(handle);if(connection==null||connection.closed){if(op==8||op==9)return adCompleted();throw failure("असमकालिक डेटासंबंध बंद है।");}}
        if(connection!=null&&connection.busy)throw failure("असमकालिक डेटासंबंध व्यस्त है।");
        long opening=adJobs.values().stream().filter(j->j.operation<2&&!j.claimed).count(),preparing=adJobs.values().stream().filter(j->j.operation==2&&!j.claimed).count();
        if(op<2&&adConnections.size()+opening>=32||op==2&&adStatements.size()+preparing>=128)throw failure("असमकालिक डेटा क्षमता समाप्त।");
        long reservation=op==4?DB_LIMIT:0;
        for(int i=0;i<values.size();i++){
            Object value=values.get(i);
            if(value instanceof BhashRuntime.BBuffer buffer)reservation+=buffer.size()*(connection!=null&&connection.postgres?3:1);
            else if(value instanceof String textValue)reservation+=textValue.getBytes(StandardCharsets.UTF_8).length;
            if(reservation>67108864-adReserved)throw failure("असमकालिक डेटा क्षमता समाप्त।");
        }
        if(reservation>67108864-adReserved)throw failure("असमकालिक डेटा क्षमता समाप्त।");
        Object[] parameters=new Object[(int)values.size()];long bytes=0;
        for(int i=0;i<parameters.length;i++){
            Object v=values.get(i);
            if(v instanceof BhashRuntime.BBuffer buffer){bytes+=buffer.size();if(bytes>DB_LIMIT)throw failure("डेटा मापदंड सीमा से बाहर हैं।");v=dbArray(buffer);}
            else if(v instanceof String value){bytes+=value.getBytes(StandardCharsets.UTF_8).length;}
            else if(v!=null&&!(v instanceof Long)&&!(v instanceof Double)&&!(v instanceof Boolean))throw failure("असमर्थित डेटा मापदंड।");
            if(bytes>DB_LIMIT)throw failure("डेटा मापदंड सीमा से बाहर हैं।");parameters[i]=v;
        }
        var job=new AdJob(op,connection,statement,text,parameters);job.reserved=reservation;long id=++adNext;
        adReserved+=reservation;
        if(connection!=null)connection.busy=true;adJobs.put(id,job);
        try{(op==5||op==8||op==9?AdWorkers.CONTROL:AdWorkers.DATA).execute(()->adRun(job));}
        catch(java.util.concurrent.RejectedExecutionException full){adJobs.remove(id);adReserved-=reservation;if(connection!=null)connection.busy=false;throw failure("असमकालिक डेटा क्षमता समाप्त।");}
        return id;
    }
    private static long adCompleted(){var j=new AdJob(9,null,null,"",new Object[0]);j.finished=true;long id=++adNext;adJobs.put(id,j);return id;}
    private static void adClose(AdConnection c){
        if(c==null||c.closed)return;
        try{if(!c.db.getAutoCommit())c.db.rollback();}catch(java.sql.SQLException ignored){}
        try{c.db.close();}catch(java.sql.SQLException ignored){}c.closed=true;c.transaction=false;
    }
    private static void adFinish(AdJob j){
        // Called with the operation monitor held. No next command may start until
        // both cancellation control and provider work have relinquished the handle.
        if(!j.workerFinished||j.controlPending)return;
        if(j.cancelled&&j.operation!=7&&j.dispatched&&!j.cleanupDone){
            j.controlPending=true;
            try{AdWorkers.CONTROL.execute(()->{
                try{adClose(j.connection);adClose(j.opened);if(j.prepared!=null)try{j.prepared.query.close();}catch(java.sql.SQLException ignored){}}
                finally{synchronized(j){j.opened=null;j.prepared=null;j.cleanupDone=true;j.controlPending=false;adFinish(j);}}
            });}catch(java.util.concurrent.RejectedExecutionException full){j.controlPending=false;}
            return;
        }
        j.finished=true;
    }
    private static void adRun(AdJob j){
        synchronized(j){if(j.cancelled){j.workerFinished=true;adFinish(j);return;}j.dispatched=true;}
        try{
            switch(j.operation){
                case 0 -> j.opened=new AdConnection(adSqliteConnect(j.text),false);
                case 1 -> j.opened=new AdConnection(adPostgresConnect(j.text),true);
                case 2 -> {
                    if(j.connection.failed)throw new AdError("डेटालेनदेन असफल है; वापस लें।");
                    var query=j.connection.db.prepareStatement(j.text);j.active=query;
                    try{j.prepared=new AdStatement(j.connection,query);}catch(Throwable error){query.close();throw error;}
                }
                case 3,4 -> adQuery(j);
                case 5 -> {j.statement.query.close();j.statement.closed=true;}
                case 6 -> {
                    if(j.connection.transaction)throw new AdError("डेटालेनदेन पहले सक्रिय है।");
                    j.connection.db.setAutoCommit(false);j.connection.transaction=true;j.connection.failed=false;
                }
                case 7 -> {
                    if(j.connection.failed)throw new AdError("डेटालेनदेन असफल है; वापस लें।");
                    if(!j.connection.transaction)throw new AdError("डेटालेनदेन सक्रिय नहीं है।");
                    try{j.connection.db.commit();j.connection.db.setAutoCommit(true);j.connection.transaction=false;}
                    catch(java.sql.SQLException error){
                        String state=error.getSQLState();int code=error.getErrorCode()&255;
                        if(j.connection.postgres&&state!=null&&!state.startsWith("08")&&!state.equals("40003")&&!state.equals("57P01")&&!state.equals("57P02")&&!state.equals("57P03")||!j.connection.postgres&&code!=10&&code!=11&&code!=26)throw error;
                        adClose(j.connection);throw new AdError("लेनदेन का परिणाम अज्ञात है; स्वचालित पुनःप्रयास न करें।");
                    }
                }
                case 8 -> {if(j.connection.transaction){j.connection.db.rollback();j.connection.db.setAutoCommit(true);}j.connection.transaction=false;j.connection.failed=false;}
                case 9 -> adClose(j.connection);
                default -> throw new AdError("अमान्य असमकालिक डेटा क्रिया।");
            }
        }catch(Throwable error){j.error=adError(error,j.operation==1||j.connection!=null&&j.connection.postgres);if(j.connection!=null&&j.connection.transaction)j.connection.failed=true;}
        finally{synchronized(j){j.active=null;j.workerFinished=true;adFinish(j);}}
    }
    static boolean dbAsyncDispatched(long id){var j=adJob(id);synchronized(j){return j.dispatched;}}
    static boolean dbAsyncPoll(long id){var j=adJob(id);synchronized(j){adFinish(j);return j.finished;}}
    static void dbAsyncAbort(long id){
        var j=adJob(id);
        synchronized(j){
            if(j.finished){
                if(j.claimed||j.opened==null&&j.prepared==null)return;
                j.finished=false;j.cancelled=true;adFinish(j);return;
            }
            if(j.cancelled||j.operation==7&&j.dispatched)return;
            j.cancelled=true;
            var active=j.active;
            if(active!=null||j.dispatched&&j.connection!=null){
                j.controlPending=true;
                // At most 64 registered operations, so reserved control queue cannot
                // be saturated by data jobs. Keep ownership if submission fails.
                try{AdWorkers.CONTROL.execute(()->{
                    try{
                        if(active!=null)active.cancel();
                        else if(j.connection.postgres){
                            var api=Class.forName("org.postgresql.PGConnection");
                            api.getMethod("cancelQuery").invoke(j.connection.db.unwrap(api));
                        }else{
                            var api=Class.forName("org.sqlite.SQLiteConnection");
                            Object database=api.getMethod("getDatabase").invoke(j.connection.db);
                            Class.forName("org.sqlite.core.DB").getMethod("interrupt").invoke(database);
                        }
                    }catch(java.sql.SQLException|ReflectiveOperationException ignored){}
                    finally{synchronized(j){j.controlPending=false;adFinish(j);}}
                });}catch(java.util.concurrent.RejectedExecutionException full){j.controlPending=false;}
            }
        }
    }
    static long dbAsyncResult(long id){
        var j=adReady(id);
        if(!j.claimed){
            if(j.opened!=null){j.result=++adHandle;adConnections.put(j.result,j.opened);}
            if(j.prepared!=null){j.result=++adHandle;adStatements.put(j.result,j.prepared);}
            j.claimed=true;
        }
        return j.result;
    }
    static void dbAsyncRelease(long id){
        var j=adJobs.get(id);if(j==null)return;if(!j.finished)throw failure("डेटा क्रिया अभी पूरी नहीं हुई है।");
        if(!j.claimed&&(j.opened!=null||j.prepared!=null))throw failure("डेटा क्रिया परिणाम का समापन शेष है।");
        if(j.connection!=null)j.connection.busy=false;
        adReserved-=j.reserved;adJobs.remove(id);adStatements.entrySet().removeIf(e->e.getValue().closed||e.getValue().owner.closed);adConnections.entrySet().removeIf(e->e.getValue().closed);
    }
    static long dbAsyncParameters(long id){var s=adStatements.get(id);if(s==null||s.closed)throw failure("असमकालिक डेटाकथन बंद है।");return s.parameters;}
    static long dbAsyncLive(){return adJobs.size()+adConnections.size()+adStatements.size();}
    static long dbAsyncRows(long id){return adReady(id).rows.size();}
    static long dbAsyncColumns(long id){return adReady(id).names.length;}
    static String dbAsyncName(long id,long column){var j=adReady(id);if(column<0||column>=j.names.length)throw failure("डेटाकथन स्तंभ सीमा से बाहर है।");return j.names[(int)column];}
    private static AdCell adCell(long id,long row,long column){var j=adReady(id);if(row<0||row>=j.rows.size()||column<0||column>=j.names.length)throw failure("डेटाकथन परिणाम सूचकांक सीमा से बाहर है।");return j.rows.get((int)row)[(int)column];}
    static String dbAsyncKind(long id,long row,long col){return adCell(id,row,col).kind();}
    static boolean dbAsyncNull(long id,long row,long col){return adCell(id,row,col).value()==null;}
    static long dbAsyncInteger(long id,long row,long col){Object v=adCell(id,row,col).value();if(v instanceof Boolean b)return b?1:0;if(v instanceof Long n)return n;throw failure("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");}
    static double dbAsyncReal(long id,long row,long col){Object v=adCell(id,row,col).value();if(v instanceof Double n)return n;throw failure("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");}
    static String dbAsyncText(long id,long row,long col){Object v=adCell(id,row,col).value();if(v instanceof String text){unicode(text);return text;}throw failure("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");}
    static BhashRuntime.BBuffer dbAsyncBytes(long id,long row,long col){Object v=adCell(id,row,col).value();if(v instanceof byte[] bytes)return dbBuffer(bytes);throw failure("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");}
    private static java.sql.Connection adSqliteConnect(String path)throws java.sql.SQLException {
        try{Class.forName("org.sqlite.JDBC");}
        catch(ClassNotFoundException|LinkageError missing){throw new AdError("SQLite रनटाइम उपलब्ध नहीं है।");}
        var properties=new java.util.Properties();properties.setProperty("busy_timeout","5000");
        properties.setProperty("foreign_keys","true");properties.setProperty("enable_load_extension","false");
        properties.setProperty("transaction_mode","DEFERRED");
        java.sql.Connection connection=null;
        try{
            connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path,properties);
            if(!connection.getMetaData().getDatabaseProductVersion().equals("3.54.1"))throw new AdError("SQLite रनटाइम संस्करण मेल नहीं खाता।");
            Class<?> limits=Class.forName("org.sqlite.SQLiteLimits");
            var setter=connection.getClass().getMethod("setLimit",limits,int.class);
            String[] names={"SQLITE_LIMIT_LENGTH","SQLITE_LIMIT_SQL_LENGTH","SQLITE_LIMIT_COLUMN","SQLITE_LIMIT_VARIABLE_NUMBER","SQLITE_LIMIT_ATTACHED"};
            int[] values={DB_LIMIT,65536,DB_COLUMNS,999,0};
            for(int i=0;i<names.length;i++)setter.invoke(connection,limits.getField(names[i]).get(null),values[i]);
            try(var check=connection.createStatement();var row=check.executeQuery("PRAGMA schema_version")){row.next();}
            var answer=connection;connection=null;return answer;
        }catch(ReflectiveOperationException unsupported){throw new AdError("SQLite रनटाइम संस्करण मेल नहीं खाता।");}
        finally{if(connection!=null)try{connection.close();}catch(java.sql.SQLException ignored){}}
    }
    private static java.sql.Connection adPostgresConnect(String config){

        String[] a=config.split("\n",-1);if(a.length!=10)throw new AdError("अमान्य PostgreSQL विन्यास।");
        java.sql.Connection db=null;
        try{
            Class<?> sourceClass=Class.forName("org.postgresql.ds.PGSimpleDataSource");
            Object source=sourceClass.getConstructor().newInstance();
            var p=new java.util.Properties();p.setProperty("PGHOST",a[0]);p.setProperty("PGPORT",a[1]);p.setProperty("PGDBNAME",a[2]);
            p.setProperty("user",a[3]);p.setProperty("password",a[4]);p.setProperty("sslmode",a[5]);
            if(!a[6].isEmpty())p.setProperty("sslrootcert",a[6]);
            p.setProperty("connectTimeout",a[7]);p.setProperty("socketTimeout",Integer.toString((Integer.parseInt(a[8])+999)/1000+5));
            p.setProperty("options","-c statement_timeout="+a[8]+" -c lock_timeout="+a[8]+" -c timezone=UTC -c datestyle=ISO -c standard_conforming_strings=on -c bytea_output=hex");
            p.setProperty("ApplicationName","Bhash");p.setProperty("gssEncMode","disable");p.setProperty("allowEncodingChanges","false");
            p.setProperty("maxResultBuffer","8388608");p.setProperty("defaultRowFetchSize","32");
            var property=sourceClass.getMethod("setProperty",String.class,String.class);
            for(String key:p.stringPropertyNames())property.invoke(source,key,p.getProperty(key));
            db=((javax.sql.DataSource)source).getConnection();
            if(db.getMetaData().getDatabaseMajorVersion()<14)throw new AdError("PostgreSQL 14 या नया संस्करण चाहिए।");
            var result=db;db=null;return result;
        }catch(ReflectiveOperationException|LinkageError missing){throw new AdError("PostgreSQL JDBC रनटाइम उपलब्ध नहीं है।");}
        catch(java.sql.SQLException e){throw new AdError("PostgreSQL त्रुटि [XXXXX]।");}
        finally{if(db!=null)try{db.close();}catch(java.sql.SQLException ignored){}}
    }

    private static void adQuery(AdJob j)throws java.sql.SQLException {
        var c=j.connection;var s=j.statement;
        if(c.failed)throw new AdError("डेटालेनदेन असफल है; वापस लें।");
        if(j.parameters.length!=s.parameters)throw new AdError("डेटाकथन के सभी मापदंड बाँधें।");
        synchronized(j){if(j.cancelled)throw new AdError("कार्य निरस्त किया गया।");j.active=s.query;}
        s.query.clearParameters();s.query.setQueryTimeout(30);
        // PostgreSQL's driver buffer cap is enforced while decoding its protocol.
        // Fetch size adds bounded cursor batches inside explicit transactions.
        s.query.setFetchSize(c.postgres?32:0);
        for(int i=0;i<j.parameters.length;i++){
            Object v=j.parameters[i];int n=i+1;
            if(v==null)s.query.setNull(n,java.sql.Types.NULL);
            else if(v instanceof Long number)s.query.setLong(n,number);
            else if(v instanceof Double number)s.query.setDouble(n,number);
            else if(v instanceof Boolean flag){if(c.postgres)s.query.setBoolean(n,flag);else s.query.setLong(n,flag?1:0);}
            else if(v instanceof byte[] bytes)s.query.setBytes(n,bytes);
            else s.query.setString(n,(String)v);
        }
        if(j.operation==3){
            if(s.columns!=0)throw new AdError("डेटाकथन पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।");
            j.result=s.query.executeLargeUpdate();return;
        }
        try(var rows=s.query.executeQuery()){
            var meta=rows.getMetaData();int count=meta.getColumnCount();
            if(count>256)throw new AdError("डेटा परिणाम की सीमा पार हो गई।");
            j.names=new String[count];for(int i=0;i<count;i++)j.names[i]=meta.getColumnLabel(i+1);
            long bytes=0;
            while(rows.next()){
                synchronized(j){if(j.cancelled)throw new AdError("कार्य निरस्त किया गया।");}
                if(j.rows.size()>=1024)throw new AdError("डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");
                var row=new AdCell[count];
                for(int i=0;i<count;i++){
                    Object value=rows.getObject(i+1);String kind;
                    if(c.postgres){
                        kind=value==null?"रिक्त":pgKind(meta.getColumnTypeName(i+1));
                        if(value!=null)value=switch(kind){
                            case "पूर्णांक" -> rows.getLong(i+1);
                            case "दशमलव" -> rows.getDouble(i+1);
                            case "तार्किक" -> rows.getBoolean(i+1);
                            case "बाइटबफ़र" -> rows.getBytes(i+1);
                            case "पाठ","समय","सटीकदशमलव" -> rows.getString(i+1);
                            default -> throw new AdError("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");
                        };
                    }else{
                        if(value==null)kind="रिक्त";
                        else if(value instanceof byte[])kind="बाइटबफ़र";
                        else if(value instanceof String){
                            kind="पाठ";
                            try{value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(rows.getBytes(i+1))).toString();}
                            catch(java.nio.charset.CharacterCodingException invalid){throw new AdError("अमान्य यूनिकोड पाठ।");}
                        }
                        else if(value instanceof Float||value instanceof Double){kind="दशमलव";value=((Number)value).doubleValue();}
                        else if(value instanceof Number){kind="पूर्णांक";value=((Number)value).longValue();}
                        else throw new AdError("डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");
                    }
                    if(value instanceof Double number&&!Double.isFinite(number))throw new AdError("डेटाकथन में सीमित दशमलव मान दें।");
                    bytes+=value instanceof byte[] buffer?buffer.length:value instanceof String text?text.getBytes(StandardCharsets.UTF_8).length:8;
                    if(bytes>DB_LIMIT)throw new AdError("डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");
                    row[i]=new AdCell(kind,value);
                }
                j.rows.add(row);
            }
            j.result=j.rows.size();
        }
    }
