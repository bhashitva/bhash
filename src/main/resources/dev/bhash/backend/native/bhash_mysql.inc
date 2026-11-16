/* SPDX-License-Identifier: Apache-2.0
 * unixODBC 3 ABI on 64-bit Linux. Loading at runtime keeps C development
 * headers and the client driver optional for other Bhash programs. */
#include <iconv.h>
typedef int16_t my_short;
typedef uint16_t my_ushort;
typedef intptr_t my_len;
typedef uintptr_t my_ulen;
typedef void *my_handle;
#define MY_API(X) \
 X(SQLAllocHandle,(my_short,my_handle,my_handle*)) \
 X(SQLFreeHandle,(my_short,my_handle)) \
 X(SQLSetEnvAttr,(my_handle,int,void*,int)) \
 X(SQLSetConnectAttr,(my_handle,int,void*,int)) \
 X(SQLSetStmtAttr,(my_handle,int,void*,int)) \
 X(SQLDriverConnect,(my_handle,void*,unsigned char*,my_short,unsigned char*,my_short,my_short*,my_ushort)) \
 X(SQLDriverConnectW,(my_handle,void*,uint16_t*,my_short,uint16_t*,my_short,my_short*,my_ushort)) \
 X(SQLPrepareW,(my_handle,uint16_t*,int)) \
 X(SQLDescribeParam,(my_handle,my_ushort,my_short*,my_ulen*,my_short*,my_short*)) \
 X(SQLDescribeColW,(my_handle,my_ushort,uint16_t*,my_short,my_short*,my_short*,my_ulen*,my_short*,my_short*)) \
 X(SQLDisconnect,(my_handle)) \
 X(SQLPrepare,(my_handle,unsigned char*,int)) \
 X(SQLNumParams,(my_handle,my_short*)) \
 X(SQLNumResultCols,(my_handle,my_short*)) \
 X(SQLDescribeCol,(my_handle,my_ushort,unsigned char*,my_short,my_short*,my_short*,my_ulen*,my_short*,my_short*)) \
 X(SQLColAttribute,(my_handle,my_ushort,my_ushort,void*,my_short,my_short*,my_len*)) \
 X(SQLBindParameter,(my_handle,my_ushort,my_short,my_short,my_short,my_ulen,my_short,void*,my_len,my_len*)) \
 X(SQLExecute,(my_handle)) \
 X(SQLFetch,(my_handle)) \
 X(SQLGetData,(my_handle,my_ushort,my_short,void*,my_len,my_len*)) \
 X(SQLRowCount,(my_handle,my_len*)) \
 X(SQLFreeStmt,(my_handle,my_ushort)) \
 X(SQLEndTran,(my_short,my_handle,my_short)) \
 X(SQLGetDiagRec,(my_short,my_handle,my_short,unsigned char*,int*,unsigned char*,my_short,my_short*))
#define MY_DECLARE(name,args) static my_short (*my_##name)args;
MY_API(MY_DECLARE)
#undef MY_DECLARE
enum {MY_ENV=1,MY_DBC=2,MY_STMT=3,MY_NO_DATA=100,MY_NULL=-1,MY_NTS=-3};
enum {MY_INTEGER,MY_REAL,MY_BOOL,MY_TEXT,MY_BYTES,MY_DECIMAL,MY_TIME,MY_UNSUPPORTED};
enum {MY_ROW_LIMIT=8388608};
static my_handle my_env;
typedef struct {uint64_t id,token;my_handle db;int failed,timeout,sqlserver;} my_connection;
typedef struct {void *value;my_len length;my_short c_type,sql_type;unsigned char bound;} my_binding;
typedef struct {char name[1024];int kind;unsigned char *data;size_t length;int null;} my_column;
typedef struct {
    uint64_t id;my_connection *owner;my_handle stmt;
    my_binding *params;my_column *columns;int count,cols,state,select;
} my_statement;
static my_connection my_connections[32];
static my_statement my_statements[128];
static uint64_t my_next_conn=UINT64_C(1)<<63,my_next_stmt=UINT64_C(1)<<63,my_next_tx;

static int my_ok(my_short status){return status==0||status==1;}
static _Noreturn void my_error(my_connection *c,my_short kind,my_handle handle,const void *location){
    unsigned char state[6]="XXXXX";
    if(handle){
        unsigned char ignored[2];int code;my_short length;
        if(!my_ok(my_SQLGetDiagRec(kind,handle,1,state,&code,ignored,sizeof ignored,&length)))memcpy(state,"XXXXX",6);
        for(int i=0;i<5;i++)if(!((state[i]>='A'&&state[i]<='Z')||(state[i]>='0'&&state[i]<='9'))){memcpy(state,"XXXXX",6);break;}
        state[5]=0;
    }
    if(c&&c->token)c->failed=1;
    char message[64];snprintf(message,sizeof message,"%s त्रुटि [%s]।",c&&c->sqlserver?"SQL Server":"MySQL",state);
    runtime_error(location,message);
}
static void my_load(const void *location){
    if(my_env)return;
    void *lib=dlopen("libodbc.so.2",RTLD_NOW|RTLD_LOCAL);
    if(!lib)runtime_error(location,"इस डेटा चालक के लिए unixODBC रनटाइम चाहिए।");
#define MY_LOAD(name,args) do{void *p=dlsym(lib,#name);if(!p){dlclose(lib);runtime_error(location,"unixODBC रनटाइम असंगत है।");}memcpy(&my_##name,&p,sizeof p);}while(0);
    MY_API(MY_LOAD)
#undef MY_LOAD
    my_handle env=NULL;
    if(!my_ok(my_SQLAllocHandle(MY_ENV,NULL,&env))||!my_ok(my_SQLSetEnvAttr(env,200,(void *)(intptr_t)3,0))){
        if(env)my_SQLFreeHandle(MY_ENV,env);
        dlclose(lib);runtime_error(location,"unixODBC परिवेश नहीं खुला।");
    }
    my_env=env;
}
static void *sqlserver_convert(const void *text,size_t size,size_t *length,int wide){
    if(size>(SIZE_MAX-4)/4)return NULL;
    iconv_t converter=iconv_open(wide?"UTF-16LE":"UTF-8",wide?"UTF-8":"UTF-16LE");
    if(converter==(iconv_t)-1)return NULL;
    size_t capacity=size*4+4,remaining=capacity-2;
    char *result=malloc(capacity),*out=result,*in=(char *)text;
    if(!result||iconv(converter,&in,&size,&out,&remaining)==(size_t)-1){free(result);result=NULL;}
    else {*length=(size_t)(out-result);out[0]=out[1]=0;}
    iconv_close(converter);
    return result;
}
static int sqlserver_connect(const char *config,my_handle *db){
    size_t bytes;
    uint16_t *wide=sqlserver_convert(config,strlen(config),&bytes,1);
    if(!wide)return 0;
    int ok=bytes/2<=32767&&my_ok(my_SQLAllocHandle(MY_DBC,my_env,db))&&
        my_ok(my_SQLSetConnectAttr(*db,103,(void *)(intptr_t)15,0))&&
        my_ok(my_SQLDriverConnectW(*db,NULL,wide,(my_short)(bytes/2),NULL,0,NULL,0));
    free(wide);
    if(!ok&&*db){my_SQLFreeHandle(MY_DBC,*db);*db=NULL;}
    if(ok)(void)my_SQLSetConnectAttr(*db,113,(void *)(intptr_t)30,0);
    return ok;
}
static uint64_t sqlserver_open(const char *config,size_t length,const void *location){
    my_load(location);
    for(size_t i=0;i<32;i++)if(!my_connections[i].id){
        char *copy=pg_copy(config,length,location);
        my_handle db=NULL;
        int ok=sqlserver_connect(copy,&db);
        free(copy);
        if(!ok)runtime_error(location,"SQL Server संबंध नहीं खुला। ODBC चालक, प्रमाणपत्र और विन्यास जाँचें।");
        my_connections[i]=(my_connection){.id=++my_next_conn,.db=db,.timeout=30,.sqlserver=1};
        return my_connections[i].id;
    }
    runtime_error(location,"बहुत अधिक खुले डेटा संबंध।");
}
static int sqlserver_prepare(my_handle db,const char *sql,size_t length,my_handle *stmt,my_short *count){
    size_t bytes;
    uint16_t *wide=sqlserver_convert(sql,length,&bytes,1);
    if(!wide)return 0;
    int ok=my_ok(my_SQLAllocHandle(MY_STMT,db,stmt))&&
        my_ok(my_SQLSetStmtAttr(*stmt,0,(void *)(intptr_t)30,0))&&
        my_ok(my_SQLPrepareW(*stmt,wide,(int)(bytes/2)))&&
        my_ok(my_SQLNumParams(*stmt,count))&&*count>=0&&*count<=999;
    free(wide);
    return ok;
}
static int sqlserver_bind(my_handle stmt,int index,my_binding *p){
    my_short type=p->sql_type,scale=0,nullable;
    my_ulen size=p->length>0?(my_ulen)p->length:1;
    if(p->c_type==-8)size=size>1?size/2:1;
    if(p->sql_type==-3&&size>8000)type=-4;
    if(p->length==MY_NULL&&!my_ok(my_SQLDescribeParam(stmt,(my_ushort)index,&type,&size,&scale,&nullable)))return 0;
    return my_ok(my_SQLBindParameter(stmt,(my_ushort)index,1,p->c_type,type,size,scale,
        p->value,p->length>0?p->length:0,&p->length));
}
static int sqlserver_read(my_handle stmt,my_ushort column,int binary,size_t limit,
    unsigned char **result,size_t *length,int *isnull){
    size_t used=0;unsigned char *data=NULL;
    *result=NULL;*length=0;*isnull=0;
    for(;;){
        uint16_t chunk[2049];my_len indicator=0;
        my_short status=my_SQLGetData(stmt,column,binary?-2:-8,chunk,binary?4096:sizeof chunk,&indicator);
        if(status==MY_NO_DATA)break;
        if(!my_ok(status)){free(data);return 0;}
        if(indicator==MY_NULL){free(data);*isnull=1;return 1;}
        if(indicator<0&&status==0){free(data);return 0;}
        size_t got=indicator<0||indicator>4096?4096:(size_t)indicator;
        size_t maximum=binary?limit:limit*2;
        if(got>maximum-used){free(data);return 0;}
        unsigned char *more=realloc(data,used+got+2);
        if(!more){free(data);return 0;}
        data=more;memcpy(data+used,chunk,got);used+=got;
        if(!status)break;
        if(!got){free(data);return 0;}
    }
    if(!binary){
        unsigned char *utf8=sqlserver_convert(data,used,length,0);free(data);
        if(!utf8||*length>limit){free(utf8);return 0;}
        *result=utf8;return 1;
    }
    *result=data;*length=used;
    return 1;
}
static int sqlserver_column(my_handle stmt,my_ushort index,my_short *type,char **name){
    uint16_t wide[256];my_short chars,scale,nullable;my_ulen width;
    if(!my_ok(my_SQLDescribeColW(stmt,index,wide,256,&chars,type,&width,&scale,&nullable))||chars<0||chars>=256)return 0;
    size_t length;
    *name=sqlserver_convert(wide,(size_t)chars*2,&length,0);
    return *name!=NULL;
}
static int my_safe(const char *s,const char *blocked){return !strpbrk(s,blocked);}
static uint64_t mysql_open(const char *config,size_t length,const void *location){
    my_load(location);
    my_connection *slot=NULL;
    for(size_t i=0;i<32;i++)if(!my_connections[i].id){slot=&my_connections[i];break;}
    if(!slot)runtime_error(location,"बहुत अधिक खुले MySQL संबंध।");
    char *copy=pg_copy(config,length,location),*cursor=copy,*field[8];
    for(int i=0;i<8;i++){
        field[i]=cursor;
        char *end=strchr(cursor,'\n');
        if(!end){free(copy);runtime_error(location,"अमान्य MySQL विन्यास।");}
        *end=0;cursor=end+1;
    }
    const char *driver=getenv("BHASH_MYSQL_ODBC_DRIVER");
    const char *ca=getenv("BHASH_MYSQL_CA");
    if(!driver||!*driver)driver="MariaDB Unicode";
    if(!ca||!*ca)ca="/etc/ssl/certs/ca-certificates.crt";
    int tls=!strcmp(field[5],"verify-full");
    int safe=!*cursor&&my_safe(driver,"{};\r\n")&&my_safe(ca,"{};\r\n");
    for(int i=0;i<8;i++)safe=safe&&my_safe(field[i],"{};");
    if(!safe){free(copy);runtime_error(location,"अमान्य MySQL ODBC विन्यास।");}
    char connection[33000];
    int len=snprintf(connection,sizeof connection,
        "DRIVER={%s};SERVER={%s};PORT=%s;DATABASE={%s};UID={%s};PWD={%s};CHARSET=utf8mb4;FORWARDONLY=0;%s%s%s",
        driver,field[0],field[1],field[2],field[3],field[4],
        tls?"FORCETLS=1;SSLVERIFY=1;SSLCA={":"",tls?ca:"",tls?"};":"");
    int connect=atoi(field[6]),timeout=(atoi(field[7])+999)/1000;
    free(copy);
    if(len<0||len>32767)runtime_error(location,"MySQL ODBC विन्यास सीमा से बाहर है।");
    my_handle db=NULL;
    if(!my_ok(my_SQLAllocHandle(MY_DBC,my_env,&db)))my_error(NULL,MY_ENV,my_env,location);
    if(!my_ok(my_SQLSetConnectAttr(db,103,(void *)(intptr_t)connect,0))||
       !my_ok(my_SQLDriverConnect(db,NULL,(unsigned char*)connection,(my_short)len,NULL,0,NULL,0))){
        my_SQLFreeHandle(MY_DBC,db);
        runtime_error(location,"MySQL संबंध नहीं खुला। ODBC चालक, प्रमाणपत्र और विन्यास जाँचें।");
    }
    *slot=(my_connection){.id=++my_next_conn,.db=db,.timeout=timeout};
    return slot->id;
}
static my_connection *my_conn(uint64_t id){
    for(size_t i=0;i<32;i++)if(my_connections[i].id==id)return &my_connections[i];
    return NULL;
}
static my_statement *my_stmt(uint64_t id){
    for(size_t i=0;i<128;i++)if(my_statements[i].id==id)return &my_statements[i];
    return NULL;
}
static void my_clear_cells(my_statement *s){
    for(int i=0;i<s->cols;i++){free(s->columns[i].data);s->columns[i].data=NULL;}
}
static void my_rows(my_statement *s,int state){
    my_clear_cells(s);
    if(s->state)my_SQLFreeStmt(s->stmt,0);
    s->state=state;
}
static void my_bind_clear(my_statement *s){
    my_SQLFreeStmt(s->stmt,3);
    for(int i=0;i<s->count;i++){free(s->params[i].value);memset(&s->params[i],0,sizeof s->params[i]);}
}
static void my_dispose(my_statement *s){
    my_rows(s,0);my_bind_clear(s);my_SQLFreeHandle(MY_STMT,s->stmt);
    free(s->params);free(s->columns);memset(s,0,sizeof *s);
}
static void my_healthy(my_connection *c,const void *location){
    if(c->failed)runtime_error(location,"MySQL लेनदेन असफल है। इसे वापस लें।");
}
static int my_sql_type(const char *text,size_t n,int sqlserver){
    if(n>65536||memchr(text,0,n)||(!sqlserver&&memchr(text,';',n)))return -1;
    size_t i=0;while(i<n&&strchr(" \t\r\n",text[i]))i++;
    char verb[8]={0};size_t j=0;
    while(i<n&&j<7&&((text[i]>='A'&&text[i]<='Z')||(text[i]>='a'&&text[i]<='z'))){
        unsigned char c=text[i++];verb[j++]=(char)(c>='a'&&c<='z'?c-32:c);
    }
    if(i<n&&!strchr(" \t\r\n(",text[i]))return -1;
    if(sqlserver){
        char quote=0;
        for(size_t k=i;k<n;k++){
            char c=text[k];
            if(quote){
                if(c==quote){if(k+1<n&&text[k+1]==quote)k++;else quote=0;}
            }else if(c=='\''||c=='"'||c=='[')quote=c=='['?']':c;
            else if(c==';'||c=='@'||c=='#'||(k+1<n&&((c=='-'&&text[k+1]=='-')||(c=='/'&&text[k+1]=='*'))))return -1;
        }
        if(quote)return -1;
    }
    if(!strcmp(verb,"SELECT"))return 1;
    if(!strcmp(verb,"INSERT")||!strcmp(verb,"UPDATE")||!strcmp(verb,"DELETE"))return 0;
    return -1;
}
static uint64_t my_prepare(my_connection *c,void *sql,const void *location){
    my_healthy(c,location);bh_instance(sql,location);
    int select=my_sql_type((const char*)text_data(sql),text_length(sql),c->sqlserver);
    if(select<0)runtime_error(location,"एक SELECT, INSERT, UPDATE या DELETE कथन दें।");
    my_statement *slot=NULL;
    for(size_t i=0;i<128;i++)if(!my_statements[i].id){slot=&my_statements[i];break;}
    if(!slot)runtime_error(location,"बहुत अधिक खुले MySQL कथन।");
    my_handle stmt=NULL;
    my_short count=0;
    if(c->sqlserver){
        if(!sqlserver_prepare(c->db,(const char*)text_data(sql),text_length(sql),&stmt,&count)){
            if(stmt)my_SQLFreeHandle(MY_STMT,stmt);
            my_error(c,MY_DBC,c->db,location);
        }
    }else if(!my_ok(my_SQLAllocHandle(MY_STMT,c->db,&stmt))||!my_ok(my_SQLSetStmtAttr(stmt,0,(void *)(intptr_t)c->timeout,0))||
       !my_ok(my_SQLPrepare(stmt,text_data(sql),(int)text_length(sql)))||
       !my_ok(my_SQLNumParams(stmt,&count))){
        my_SQLFreeHandle(MY_STMT,stmt);my_error(c,MY_DBC,c->db,location);
    }
    if(count<0||count>999){my_SQLFreeHandle(MY_STMT,stmt);runtime_error(location,"मापदंड सीमा ९९९ है।");}
    my_binding *params=count?calloc((size_t)count,sizeof *params):NULL;
    if(count&&!params){my_SQLFreeHandle(MY_STMT,stmt);runtime_error(location,"स्मृति उपलब्ध नहीं है।");}
    *slot=(my_statement){.id=++my_next_stmt,.owner=c,.stmt=stmt,.params=params,.count=count,.select=select};
    return slot->id;
}
static int my_kind(my_short type,my_len uns,my_ulen width){
    switch(type){
        case -7:return width==1?MY_BOOL:MY_BYTES;
        case -5:return uns?MY_DECIMAL:MY_INTEGER;
        case -6:case 4:case 5:return MY_INTEGER;
        case 6:case 7:case 8:return MY_REAL;
        case 2:case 3:return MY_DECIMAL;
        case -2:case -3:case -4:return MY_BYTES;
        case -155:case -154:case 91:case 92:case 93:return MY_TIME;
        case -11:case -10:case -9:case -8:case -1:case 1:case 12:return MY_TEXT;
        default:return MY_UNSUPPORTED;
    }
}
static const char *my_name(int kind){
    static const char *names[]={"पूर्णांक","दशमलव","तार्किक","पाठ","बाइटबफ़र","सटीकदशमलव","समय","असमर्थित"};
    return names[kind];
}
static void my_fail_stmt(my_statement *s,const void *location){
    s->state=3;my_error(s->owner,MY_STMT,s->stmt,location);
}
static void my_describe(my_statement *s,const void *location){
    my_short cols;
    if(!my_ok(my_SQLNumResultCols(s->stmt,&cols)))my_fail_stmt(s,location);
    if(cols<0||cols>256){my_rows(s,3);runtime_error(location,"MySQL स्तंभ सीमा से बाहर हैं।");}
    if(cols==s->cols&&s->columns)return;
    free(s->columns);s->columns=NULL;s->cols=0;
    if(!cols)return;
    s->columns=calloc((size_t)cols,sizeof *s->columns);
    if(!s->columns){my_rows(s,3);runtime_error(location,"स्मृति उपलब्ध नहीं है।");}
    s->cols=cols;
    for(int i=0;i<cols;i++){
        my_column *column=&s->columns[i];
        my_short type,length,scale,nullable;my_ulen width=0;my_len uns=0;
        if(!s->owner->sqlserver&&!my_ok(my_SQLDescribeCol(s->stmt,(my_ushort)i+1,(unsigned char*)column->name,
            sizeof column->name,&length,&type,&width,&scale,&nullable)))my_fail_stmt(s,location);
        if(s->owner->sqlserver){
            char *name=NULL;
            if(!sqlserver_column(s->stmt,(my_ushort)i+1,&type,&name))my_fail_stmt(s,location);
            snprintf(column->name,sizeof column->name,"%s",name);free(name);
            width=1;
        }
        if(type==-5&&!s->owner->sqlserver)my_SQLColAttribute(s->stmt,(my_ushort)i+1,8,NULL,0,NULL,&uns);
        column->kind=my_kind(type,uns,width);
    }
}
static void my_execute(my_statement *s,const void *location){
    for(int i=0;i<s->count;i++)if(!s->params[i].bound)runtime_error(location,"सभी MySQL मापदंड बाँधें।");
    for(int i=0;i<s->count;i++){
        my_binding *p=&s->params[i];
        if(s->owner->sqlserver){if(!sqlserver_bind(s->stmt,i+1,p))my_fail_stmt(s,location);continue;}
        my_ulen size=p->length>0?(my_ulen)p->length:1;
        if(!my_ok(my_SQLBindParameter(s->stmt,(my_ushort)i+1,1,p->c_type,p->sql_type,size,0,
            p->value,p->length>0?p->length:0,&p->length)))my_fail_stmt(s,location);
    }
    my_short result=my_SQLExecute(s->stmt);
    if(!my_ok(result)&&result!=MY_NO_DATA)my_fail_stmt(s,location);
    s->state=1;
}
static int my_read_cell(my_statement *s,int col,size_t *total,const void *location){
    my_column *cell=&s->columns[col];
    int binary=cell->kind==MY_BYTES;
    if(s->owner->sqlserver){
        if(!sqlserver_read(s->stmt,(my_ushort)col+1,binary,MY_ROW_LIMIT-*total,&cell->data,&cell->length,&cell->null))my_fail_stmt(s,location);
        *total+=cell->length;return 1;
    }
    unsigned char chunk[4097];size_t length=0;
    cell->null=0;cell->length=0;
    for(;;){
        my_len unread=0;
        my_short status=my_SQLGetData(s->stmt,(my_ushort)col+1,binary?-2:1,chunk,binary?4096:4097,&unread);
        if(status==MY_NO_DATA)break;
        if(!my_ok(status))my_fail_stmt(s,location);
        if(unread==MY_NULL){cell->null=1;return 1;}
        if(unread<0&&status==0&&binary)return 0;
        size_t n=binary?(unread<0||unread>4096?4096:(size_t)unread):strnlen((const char*)chunk,4096);
        if(n>MY_ROW_LIMIT-*total)return 0;
        unsigned char *more=realloc(cell->data,length+n+1);
        if(!more)return 0;
        cell->data=more;memcpy(more+length,chunk,n);length+=n;*total+=n;more[length]=0;
        if(status==0||n<4096)break;
    }
    cell->length=length;
    return 1;
}
static uint64_t mysql_call(uint32_t op,uint64_t id,uint64_t b,uint64_t value,const bh_collection_info *info,const void *location){
    if(op==1||op==2||op==4){
        my_connection *c=my_conn(id);
        if(!c){if(op==1||(op==4&&value==3))return 0;runtime_error(location,"MySQL संबंध बंद है।");}
        if(op==2)return my_prepare(c,bits_pointer(b),location);
        if(op==1){
            for(size_t i=0;i<128;i++)if(my_statements[i].owner==c)my_dispose(&my_statements[i]);
            if(c->token)my_SQLEndTran(MY_DBC,c->db,1);
            my_SQLDisconnect(c->db);my_SQLFreeHandle(MY_DBC,c->db);memset(c,0,sizeof *c);return 0;
        }
        if(value==3&&c->token!=b)return 0;
        if((value==0&&c->token)||(value!=0&&(!b||b!=c->token)))runtime_error(location,"MySQL लेनदेन सक्रिय नहीं है।");
        if(value==1)my_healthy(c,location);
        for(size_t i=0;i<128;i++)if(my_statements[i].owner==c&&my_statements[i].state)my_rows(&my_statements[i],3);
        if(value!=0&&!my_ok(my_SQLEndTran(MY_DBC,c->db,value==1?0:1)))my_error(c,MY_DBC,c->db,location);
        if(!my_ok(my_SQLSetConnectAttr(c->db,102,(void *)(intptr_t)(value!=0),0)))my_error(c,MY_DBC,c->db,location);
        c->failed=0;c->token=value==0?++my_next_tx:0;return c->token;
    }
    my_statement *s=my_stmt(id);
    if(op==3){if(s)my_dispose(s);return 0;}
    if(!s)runtime_error(location,"MySQL कथन बंद है।");
    if(op==5)return (uint64_t)s->count;
    if(op>=6&&op<=11){
        if(s->state||b<1||b>(uint64_t)s->count)runtime_error(location,"MySQL कथन अवस्था या बंधन सूचकांक अमान्य है।");
        my_binding p={.c_type=1,.sql_type=12,.length=MY_NULL,.bound=1};
        if(op>=6&&op<=8){
            if(op==7){double number;memcpy(&number,&value,8);if(!isfinite(number))runtime_error(location,"MySQL दशमलव सीमित नहीं है।");}
            p.value=pg_copy((const char*)&value,8,location);p.length=8;p.c_type=op==7?8:-25;p.sql_type=op==7?8:-5;
        }else if(op==9){
            void *t=bits_pointer(value);bh_instance(t,location);size_t n=text_length(t);
            if(n>MY_ROW_LIMIT||memchr(text_data(t),0,n))runtime_error(location,"अमान्य MySQL पाठ।");
            if(s->owner->sqlserver){
                size_t length;p.value=sqlserver_convert(text_data(t),n,&length,1);
                if(!p.value)runtime_error(location,"अमान्य SQL Server पाठ।");
                p.length=(my_len)length;p.c_type=-8;p.sql_type=length>8000?-10:-9;
            }else{p.value=pg_copy((const char*)text_data(t),n,location);p.length=(my_len)n;}
        }else if(op==10){
            bh_collection *buffer=bits_pointer(value);bh_instance(buffer,location);
            if(buffer->size>MY_ROW_LIMIT)runtime_error(location,"MySQL मान सीमा से बाहर है।");
            p.value=pg_copy((const char *)buffer->data,(size_t)buffer->size,location);p.length=(my_len)buffer->size;p.c_type=-2;p.sql_type=-3;
        }
        free(s->params[b-1].value);s->params[b-1]=p;return 0;
    }
    if(op==12||op==13){
        my_healthy(s->owner,location);
        if(op==13&&s->state==2)return 0;
        if(s->state==3||(op==12&&s->state)||(op==13)!=(s->select!=0))runtime_error(location,"MySQL कथन या परिणाम अवस्था अमान्य है।");
        if(!s->state){
            my_execute(s,location);
            if(op==13)my_describe(s,location);
        }
        if(op==12){
            my_len n;if(!my_ok(my_SQLRowCount(s->stmt,&n)))my_fail_stmt(s,location);
            my_rows(s,2);return n<0?0:(uint64_t)n;
        }
        my_clear_cells(s);
        my_short fetched=my_SQLFetch(s->stmt);
        if(fetched==MY_NO_DATA){my_rows(s,2);return 0;}
        if(!my_ok(fetched))my_fail_stmt(s,location);
        size_t total=0;for(int i=0;i<s->cols;i++)if(!my_read_cell(s,i,&total,location)){
            my_rows(s,3);runtime_error(location,"MySQL पंक्ति सीमा से बाहर है।");
        }
        return 1;
    }
    if(op==14){my_rows(s,0);return 0;}
    if(op==15){if(s->state)runtime_error(location,"MySQL कथन को रीसेट करें।");my_bind_clear(s);return 0;}
    if(op==16)return (uint64_t)s->cols;
    if(b>=(uint64_t)s->cols)runtime_error(location,"MySQL स्तंभ सूचकांक सीमा से बाहर है।");
    my_column *col=&s->columns[b];
    if(op==17)return pg_text(col->name,location);
    if(s->state!=1)runtime_error(location,"MySQL परिणाम की वर्तमान पंक्ति नहीं है।");
    if(op==18)return pg_text(col->null?"रिक्त":my_name(col->kind),location);
    if(op==19)return (uint64_t)col->null;
    if(col->null)runtime_error(location,"MySQL स्तंभ रिक्त है।");
    const char *p=col->data?(const char*)col->data:"";
    if(op==20&&col->kind==MY_INTEGER){
        char *end;errno=0;long long v=strtoll(p,&end,10);
        if(errno||end==p||*end)runtime_error(location,"MySQL पूर्णांक सीमा से बाहर है।");
        return (uint64_t)v;
    }
    if(op==21&&col->kind==MY_REAL){
        char *end;double v=strtod(p,&end);uint64_t bits;
        if(end==p||*end||!isfinite(v))runtime_error(location,"MySQL दशमलव सीमित नहीं है।");
        memcpy(&bits,&v,8);return bits;
    }
    if(op==22&&col->kind==MY_BOOL)return *p!=0&&*p!='0';
    if(op==23&&(col->kind==MY_TEXT||col->kind==MY_DECIMAL||col->kind==MY_TIME))return pg_text(p,location);
    if(op==24&&col->kind==MY_BYTES){
        bh_collection *out=bh_collection_new(info,col->length,location);
        if(col->length)memcpy(out->data,p,col->length);
        return pointer_bits(out);
    }
    runtime_error(location,"MySQL स्तंभ का प्रकार मेल नहीं खाता।");
}
