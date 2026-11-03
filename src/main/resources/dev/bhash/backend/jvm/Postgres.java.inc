    // PostgreSQL resources contain no Bhash-managed references. All errors are sanitized.
    private static final java.util.Map<Long,PgConnection> pgConnections=new java.util.HashMap<>();
    private static final java.util.Map<Long,PgStatement> pgStatements=new java.util.HashMap<>();
    private static long pgConnectionNext,pgStatementNext,pgTransactionNext;
    private static final class PgConnection {
        java.sql.Connection db; long transaction; boolean failed;
        PgConnection(java.sql.Connection db){this.db=db;}
    }
    private static final class PgStatement {
        PgConnection owner; java.sql.PreparedStatement query; java.sql.ResultSet rows;
        boolean[] bound; int state; String[] names,types; Object[] values;
        PgStatement(PgConnection owner,java.sql.PreparedStatement query)throws java.sql.SQLException {
            this.owner=owner;this.query=query;bound=new boolean[query.getParameterMetaData().getParameterCount()];
            var meta=query.getMetaData();int n=meta==null?0:meta.getColumnCount();names=new String[n];types=new String[n];
            if(n>256||bound.length>999)throw failure("PostgreSQL परिणाम या मापदंड सीमा से बाहर है।");
            for(int i=0;i<n;i++){names[i]=meta.getColumnLabel(i+1);types[i]=pgKind(meta.getColumnTypeName(i+1));}
        }
    }
    private static BhashRuntime.Failure pgFailure(java.sql.SQLException e,PgConnection c){
        if(c!=null&&c.transaction!=0)c.failed=true;
        String code=e.getSQLState();if(code==null||!code.matches("[A-Z0-9]{5}"))code="XXXXX";
        return failure("PostgreSQL त्रुटि ["+code+"]।");
    }
    static long pgOpen(String config){
        BhashRuntime.instance(config);if(pgConnections.size()>=32)throw failure("बहुत अधिक खुले PostgreSQL संबंध।");
        String[] a=config.split("\n",-1);if(a.length!=10)throw failure("अमान्य PostgreSQL विन्यास।");
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
            var property=sourceClass.getMethod("setProperty",String.class,String.class);
            for(String key:p.stringPropertyNames())property.invoke(source,key,p.getProperty(key));
            db=((javax.sql.DataSource)source).getConnection();
            if(db.getMetaData().getDatabaseMajorVersion()<14)throw failure("PostgreSQL 14 या नया संस्करण चाहिए।");
            var c=new PgConnection(db);long id=++pgConnectionNext;pgConnections.put(id,c);db=null;return id;
        }catch(ReflectiveOperationException|LinkageError missing){throw failure("PostgreSQL JDBC रनटाइम उपलब्ध नहीं है।");}
        catch(java.sql.SQLException e){throw failure("PostgreSQL त्रुटि [XXXXX]।");}
        finally{if(db!=null)try{db.close();}catch(java.sql.SQLException ignored){}}
    }
    private static PgConnection pgConnection(long id){var c=pgConnections.get(id);if(c==null)throw failure("PostgreSQL संबंध बंद है।");return c;}
    private static PgStatement pgStatement(long id){var s=pgStatements.get(id);if(s==null)throw failure("PostgreSQL कथन बंद है।");return s;}
    private static void pgReady(PgStatement s){if(s.state!=0)throw failure("PostgreSQL कथन को रीसेट करें।");}
    private static void pgHealthy(PgConnection c){if(c.failed&&c.transaction!=0)throw failure("PostgreSQL लेनदेन असफल है; वापस लें।");}
    private static int pgBinding(PgStatement s,long i){pgReady(s);if(i<1||i>s.bound.length)throw failure("PostgreSQL बंधन सूचकांक सीमा से बाहर है।");return (int)i;}
    private static int pgColumn(PgStatement s,long i){if(i<0||i>=s.names.length)throw failure("PostgreSQL स्तंभ सूचकांक सीमा से बाहर है।");return (int)i;}
    private static int pgCell(PgStatement s,long i,String want){int n=pgColumn(s,i);if(s.state!=1)throw failure("PostgreSQL परिणाम की वर्तमान पंक्ति नहीं है।");
        if(s.values[n]==null)throw failure("PostgreSQL स्तंभ रिक्त है।");if(!s.types[n].equals(want))throw failure("PostgreSQL स्तंभ का प्रकार मेल नहीं खाता।");return n;}
    static long pgPrepare(long id,String original,String converted){var c=pgConnection(id);pgHealthy(c);
        if(pgStatements.size()>=128)throw failure("बहुत अधिक खुले PostgreSQL कथन।");java.sql.PreparedStatement query=null;
        try{query=c.db.prepareStatement(original);var s=new PgStatement(c,query);long result=++pgStatementNext;pgStatements.put(result,s);query=null;return result;}
        catch(java.sql.SQLException e){throw pgFailure(e,c);}finally{if(query!=null)try{query.close();}catch(java.sql.SQLException ignored){}}
    }
    static void pgClose(long id){var c=pgConnections.remove(id);if(c==null)return;
        for(var entry:new java.util.ArrayList<>(pgStatements.entrySet()))if(entry.getValue().owner==c){pgStatements.remove(entry.getKey());try{entry.getValue().query.close();}catch(java.sql.SQLException ignored){}}
        try{c.db.close();}catch(java.sql.SQLException e){throw pgFailure(e,null);}
    }
    static void pgFinalize(long id){var s=pgStatements.remove(id);if(s==null)return;try{s.query.close();}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static long pgParameterCount(long id){return pgStatement(id).bound.length;}
    static void pgBindInt(long id,long index,long v){var s=pgStatement(id);int i=pgBinding(s,index);try{s.query.setLong(i,v);s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgBindReal(long id,long index,double v){var s=pgStatement(id);int i=pgBinding(s,index);if(!Double.isFinite(v))throw failure("PostgreSQL दशमलव सीमित नहीं है।");try{s.query.setDouble(i,v);s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgBindBool(long id,long index,boolean v){var s=pgStatement(id);int i=pgBinding(s,index);try{s.query.setBoolean(i,v);s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgBindText(long id,long index,String v){var s=pgStatement(id);int i=pgBinding(s,index);BhashRuntime.instance(v);
        if(v.indexOf(0)>=0||v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8388608)throw failure("अमान्य PostgreSQL पाठ।");
        try{s.query.setObject(i,v,java.sql.Types.OTHER);s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgBindBytes(long id,long index,BhashRuntime.BBuffer v){var s=pgStatement(id);int i=pgBinding(s,index);try{s.query.setBytes(i,dbArray(v));s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgBindNull(long id,long index){var s=pgStatement(id);int i=pgBinding(s,index);try{s.query.setNull(i,java.sql.Types.NULL);s.bound[i-1]=true;}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    private static void pgBound(PgStatement s){for(boolean b:s.bound)if(!b)throw failure("सभी PostgreSQL मापदंड बाँधें।");}
    private static void pgResetValue(PgStatement s,int state)throws java.sql.SQLException {if(s.rows!=null){s.rows.close();s.rows=null;}s.values=null;s.state=state;}
    static long pgExecute(long id){var s=pgStatement(id);pgHealthy(s.owner);pgReady(s);pgBound(s);
        if(s.names.length!=0)throw failure("PostgreSQL पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।");
        try{long n=s.query.executeLargeUpdate();s.state=2;return n;}catch(java.sql.SQLException e){s.state=3;throw pgFailure(e,s.owner);}}
    private static String pgKind(String name){return switch(name){
        case "int2","int4","int8"->"पूर्णांक";
        case "float4","float8"->"दशमलव";
        case "bool"->"तार्किक";
        case "numeric"->"सटीकदशमलव";
        case "bytea"->"बाइटबफ़र";
        case "date","time","timetz","timestamp","timestamptz"->"समय";
        case "char","name","text","bpchar","varchar","uuid","json","jsonb"->"पाठ";
        default->"असमर्थित";};}
    static boolean pgNext(long id){var s=pgStatement(id);pgHealthy(s.owner);if(s.state==2)return false;if(s.state==3)throw failure("PostgreSQL कथन को रीसेट करें।");
        if(s.names.length==0)throw failure("PostgreSQL बदलाव के लिए चलाओ उपयोग करें।");
        try{
            if(s.state==0){pgBound(s);s.rows=s.query.executeQuery();}
            if(!s.rows.next()){pgResetValue(s,2);return false;}
            s.values=new Object[s.names.length];long bytes=0;
            for(int i=0;i<s.values.length;i++){
                if(s.rows.getObject(i+1)==null){s.values[i]=null;continue;}
                s.values[i]=switch(s.types[i]){case "पूर्णांक"->s.rows.getLong(i+1);case "दशमलव"->s.rows.getDouble(i+1);case "तार्किक"->s.rows.getBoolean(i+1);case "बाइटबफ़र"->s.rows.getBytes(i+1);default->s.rows.getString(i+1);};
                if(s.values[i] instanceof byte[] b)bytes+=b.length;
                if(s.values[i] instanceof String t)bytes+=t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
            if(bytes>8388608)throw failure("PostgreSQL पंक्ति सीमा से बाहर है।");s.state=1;return true;
        }catch(java.sql.SQLException e){s.state=3;throw pgFailure(e,s.owner);}catch(RuntimeException e){try{pgResetValue(s,3);}catch(java.sql.SQLException ignored){}throw e;}
    }
    static void pgReset(long id){var s=pgStatement(id);try{pgResetValue(s,0);}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static void pgClear(long id){var s=pgStatement(id);pgReady(s);try{s.query.clearParameters();java.util.Arrays.fill(s.bound,false);}catch(java.sql.SQLException e){throw pgFailure(e,s.owner);}}
    static long pgColumns(long id){return pgStatement(id).names.length;}
    static String pgName(long id,long i){var s=pgStatement(id);return s.names[pgColumn(s,i)];}
    static String pgType(long id,long i){var s=pgStatement(id);int n=pgColumn(s,i);if(s.state!=1)throw failure("PostgreSQL परिणाम की वर्तमान पंक्ति नहीं है।");return s.values[n]==null?"रिक्त":s.types[n];}
    static boolean pgIsNull(long id,long i){return pgType(id,i).equals("रिक्त");}
    static long pgInteger(long id,long i){var s=pgStatement(id);return (Long)s.values[pgCell(s,i,"पूर्णांक")];}
    static double pgReal(long id,long i){var s=pgStatement(id);double v=(Double)s.values[pgCell(s,i,"दशमलव")];if(!Double.isFinite(v))throw failure("PostgreSQL दशमलव सीमित नहीं है।");return v;}
    static boolean pgBool(long id,long i){var s=pgStatement(id);return (Boolean)s.values[pgCell(s,i,"तार्किक")];}
    static String pgText(long id,long i){var s=pgStatement(id);int n=pgColumn(s,i);String type=s.types[n];if(!java.util.Set.of("पाठ","सटीकदशमलव","समय").contains(type))throw failure("PostgreSQL स्तंभ का प्रकार मेल नहीं खाता।");return (String)s.values[pgCell(s,i,type)];}
    static BhashRuntime.BBuffer pgBytes(long id,long i){var s=pgStatement(id);return dbBuffer((byte[])s.values[pgCell(s,i,"बाइटबफ़र")]);}
    static long pgTransaction(long id,long token,long op){var c=pgConnections.get(id);if(c==null&&op==3)return 0;if(c==null)throw failure("PostgreSQL संबंध बंद है।");
        if(op==3&&c.transaction!=token)return 0;
        if(op==0&&c.transaction!=0||op!=0&&(token==0||token!=c.transaction))throw failure("PostgreSQL लेनदेन सक्रिय नहीं है।");
        if(op==1)pgHealthy(c);
        try{for(var s:pgStatements.values())if(s.owner==c&&s.state!=0)pgResetValue(s,3);
            if(op==0){c.db.setAutoCommit(false);c.failed=false;c.transaction=++pgTransactionNext;return c.transaction;}
            if(op==1)c.db.commit();else c.db.rollback();c.db.setAutoCommit(true);c.transaction=0;c.failed=false;return 0;
        }catch(java.sql.SQLException e){throw pgFailure(e,c);}
    }
