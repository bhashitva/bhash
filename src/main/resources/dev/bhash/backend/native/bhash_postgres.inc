/* SPDX-License-Identifier: Apache-2.0
 * libpq owns results and copied parameters; no Bhash pointers survive a call.
 * Loaded at runtime, so LLVM/C headers and Java/.NET are not deployment dependencies. */
#ifndef _WIN32
#include <dlfcn.h>
#endif
#include <errno.h>
typedef struct pg_conn PGconn;
typedef struct pg_result PGresult;
typedef struct pg_cancel PGcancel;
#define PG_API_LIST(X) \
 X(PGconn*,PQconnectdbParams,(const char *const*,const char *const*,int)) \
 X(int,PQstatus,(const PGconn*)) X(void,PQfinish,(PGconn*)) \
 X(int,PQserverVersion,(const PGconn*)) X(int,PQlibVersion,(void)) \
 X(PGresult*,PQexec,(PGconn*,const char*)) \
 X(PGresult*,PQprepare,(PGconn*,const char*,const char*,int,const unsigned int*)) \
 X(PGresult*,PQdescribePrepared,(PGconn*,const char*)) \
 X(PGresult*,PQexecParams,(PGconn*,const char*,int,const unsigned int*,const char *const*,const int*,const int*,int)) \
 X(int,PQresultStatus,(const PGresult*)) X(char*,PQresultErrorField,(const PGresult*,int)) \
 X(void,PQclear,(PGresult*)) X(int,PQnparams,(const PGresult*)) \
 X(int,PQnfields,(const PGresult*)) X(int,PQntuples,(const PGresult*)) \
 X(char*,PQfname,(const PGresult*,int)) X(unsigned int,PQftype,(const PGresult*,int)) \
 X(int,PQgetisnull,(const PGresult*,int,int)) X(int,PQgetlength,(const PGresult*,int,int)) \
 X(char*,PQgetvalue,(const PGresult*,int,int)) X(char*,PQcmdTuples,(PGresult*)) \
 X(PGconn*,PQconnectStartParams,(const char *const*,const char *const*,int)) \
 X(int,PQconnectPoll,(PGconn*)) X(int,PQsocket,(const PGconn*)) \
 X(int,PQsetnonblocking,(PGconn*,int)) X(int,PQflush,(PGconn*)) \
 X(int,PQconsumeInput,(PGconn*)) X(int,PQisBusy,(PGconn*)) X(PGresult*,PQgetResult,(PGconn*)) \
 X(int,PQsendQuery,(PGconn*,const char*)) \
 X(int,PQsendPrepare,(PGconn*,const char*,const char*,int,const unsigned int*)) \
 X(int,PQsendDescribePrepared,(PGconn*,const char*)) \
 X(int,PQsendQueryParams,(PGconn*,const char*,int,const unsigned int*,const char *const*,const int*,const int*,int)) \
 X(int,PQsetSingleRowMode,(PGconn*)) X(int,PQtransactionStatus,(const PGconn*)) \
 X(PGcancel*,PQgetCancel,(PGconn*)) X(int,PQcancel,(PGcancel*,char*,int)) X(void,PQfreeCancel,(PGcancel*))
#define PG_DECLARE(ret,name,args) static ret (*pg_##name)args;
PG_API_LIST(PG_DECLARE)
#undef PG_DECLARE
static int pg_loaded;
static int pg_load(void){
    if(pg_loaded)return 1;
    const char *configured=getenv("BHASH_LIBPQ");
#ifdef _WIN32
    HMODULE library=NULL;
    if(configured&&*configured){
        wchar_t path[32768];if(MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,configured,-1,path,32768)<=0)return 0;
        if(!((path[0]&&path[1]==L':')||(path[0]==L'\\'&&path[1]==L'\\')))return 0;
        library=LoadLibraryExW(path,NULL,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    }else library=LoadLibraryExW(L"libpq.dll",NULL,LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if(!library)return 0;
#define PG_LOAD(ret,name,args) do{FARPROC p=GetProcAddress(library,#name);if(!p){FreeLibrary(library);return 0;}memcpy(&pg_##name,&p,sizeof p);}while(0);
#else
    if(configured&&*configured&&configured[0]!='/')return 0;
#ifdef __APPLE__
    const char *default_name="libpq.5.dylib";
#else
    const char *default_name="libpq.so.5";
#endif
    void *library=dlopen(configured&&*configured?configured:default_name,RTLD_NOW|RTLD_LOCAL);if(!library)return 0;
#define PG_LOAD(ret,name,args) do{void *p=dlsym(library,#name);if(!p){dlclose(library);return 0;}memcpy(&pg_##name,&p,sizeof p);}while(0);
#endif
    PG_API_LIST(PG_LOAD)
#undef PG_LOAD
    if(pg_PQlibVersion()<140000){
#ifdef _WIN32
        FreeLibrary(library);
#else
        dlclose(library);
#endif
        return 0;
    }
    pg_loaded=1;return 1;
}
typedef struct {uint64_t id,token;PGconn *db;int failed;} pg_connection;
typedef struct {uint64_t id;pg_connection *owner;char *sql;PGresult *meta,*rows;char *params[999];unsigned char bound[999];int n,state,row;} pg_statement;
static pg_connection pg_connections[32];static pg_statement pg_statements[128];
static uint64_t pg_connection_next,pg_statement_next,pg_transaction_next;
static void pg_error(pg_connection *c,PGresult *r,const void *location){
    char message[80]="PostgreSQL त्रुटि [XXXXX]।";char code[6]="XXXXX";
    if(r){const char *s=pg_PQresultErrorField(r,'C');if(s&&strlen(s)==5){int valid=1;for(int i=0;i<5;i++)if(!((s[i]>='A'&&s[i]<='Z')||(s[i]>='0'&&s[i]<='9')))valid=0;if(valid)memcpy(code,s,5);}pg_PQclear(r);}
    if(c&&c->token)c->failed=1;
    snprintf(message,sizeof message,"PostgreSQL त्रुटि [%s]।",code);runtime_error(location,message);
}
static PGresult *pg_checked(pg_connection *c,PGresult *r,const void *location){if(!r||(pg_PQresultStatus(r)!=1&&pg_PQresultStatus(r)!=2))pg_error(c,r,location);return r;}
static pg_connection *pg_conn(uint64_t id,const void *location){for(int i=0;i<32;i++)if(id&&pg_connections[i].id==id)return &pg_connections[i];runtime_error(location,"PostgreSQL संबंध बंद है।");}
static pg_statement *pg_stmt(uint64_t id,const void *location){for(int i=0;i<128;i++)if(id&&pg_statements[i].id==id)return &pg_statements[i];runtime_error(location,"PostgreSQL कथन बंद है।");}
static void pg_healthy(pg_connection *c,const void *location){if(c->token&&c->failed)runtime_error(location,"PostgreSQL लेनदेन असफल है; वापस लें।");}
static void pg_ready(pg_statement *s,const void *location){if(s->state)runtime_error(location,"PostgreSQL कथन को रीसेट करें।");}
static void pg_rows_clear(pg_statement *s,int state){if(s->rows)pg_PQclear(s->rows);s->rows=NULL;s->row=-1;s->state=state;}
static void pg_dispose(pg_statement *s){if(s->meta)pg_PQclear(s->meta);pg_rows_clear(s,0);for(int i=0;i<s->n;i++)free(s->params[i]);free(s->sql);memset(s,0,sizeof *s);}
static int pg_column(pg_statement *s,uint64_t column,const void *location){int n=pg_PQnfields(s->meta);if(column>=(uint64_t)n)runtime_error(location,"PostgreSQL स्तंभ सूचकांक सीमा से बाहर है।");return (int)column;}
static const char *pg_kind(unsigned int oid){switch(oid){case 20:case 21:case 23:return "पूर्णांक";case 700:case 701:return "दशमलव";case 16:return "तार्किक";case 17:return "बाइटबफ़र";case 1700:return "सटीकदशमलव";case 1082:case 1083:case 1114:case 1184:case 1266:return "समय";case 18:case 19:case 25:case 1042:case 1043:case 114:case 3802:case 2950:return "पाठ";default:return "असमर्थित";}}
static const char *pg_cell(pg_statement *s,uint64_t at,const char *want,const void *location){int col=pg_column(s,at,location);if(s->state!=1)runtime_error(location,"PostgreSQL परिणाम की वर्तमान पंक्ति नहीं है।");if(pg_PQgetisnull(s->rows,s->row,col))runtime_error(location,"PostgreSQL स्तंभ रिक्त है।");if(strcmp(pg_kind(pg_PQftype(s->meta,col)),want))runtime_error(location,"PostgreSQL स्तंभ का प्रकार मेल नहीं खाता।");return pg_PQgetvalue(s->rows,s->row,col);}
static uint64_t pg_text(const char *s,const void *location){if(!s)runtime_error(location,"PostgreSQL कथन असफल।");size_t n=strlen(s);if(!valid_utf8((const unsigned char*)s,n))runtime_error(location,"अमान्य यूनिकोड पाठ।");void *value=new_text(n,location);memcpy(text_data(value),s,n);return pointer_bits(value);}
static char *pg_copy(const char *s,size_t n,const void *location){char *p=malloc(n+1);if(!p)runtime_error(location,"स्मृति उपलब्ध नहीं है।");memcpy(p,s,n);p[n]=0;return p;}
static uint64_t pg_call(uint32_t op,uint64_t a,uint64_t b,uint64_t c,uint64_t d,const bh_collection_info *info,const void *location){
    (void)d;
    if(op==0){
        if(!pg_load())runtime_error(location,"PostgreSQL libpq 14+ रनटाइम उपलब्ध नहीं है।");
        pg_connection *slot=NULL;for(int i=0;i<32;i++)if(!pg_connections[i].id){slot=&pg_connections[i];break;}
        if(!slot)runtime_error(location,"बहुत अधिक खुले PostgreSQL संबंध।");
        void *config=bits_pointer(a);bh_instance(config,location);size_t n=text_length(config);if(n>40000)runtime_error(location,"अमान्य PostgreSQL विन्यास।");
        char *copy=pg_copy((const char*)text_data(config),n,location);char *v[9];char *p=copy;
        for(int i=0;i<9;i++){v[i]=p;char *end=strchr(p,'\n');if(!end){free(copy);runtime_error(location,"अमान्य PostgreSQL विन्यास।");}*end=0;p=end+1;}
        if(*p){free(copy);runtime_error(location,"अमान्य PostgreSQL विन्यास।");}
        char options[200];snprintf(options,sizeof options,"-c statement_timeout=%s -c lock_timeout=%s -c timezone=UTC -c datestyle=ISO -c standard_conforming_strings=on -c bytea_output=hex",v[8],v[8]);
        const char *keys[]={"host","port","dbname","user","password","sslmode","sslrootcert","connect_timeout","options","client_encoding","application_name","gssencmode",NULL};
        const char *values[]={v[0],v[1],v[2],v[3],v[4],v[5],*v[6]?v[6]:NULL,v[7],options,"UTF8","Bhash","disable",NULL};
        PGconn *db=pg_PQconnectdbParams(keys,values,0);free(copy);
        if(!db||pg_PQstatus(db)!=0){if(db)pg_PQfinish(db);pg_error(NULL,NULL,location);}
        if(pg_PQserverVersion(db)<140000){pg_PQfinish(db);runtime_error(location,"PostgreSQL 14 या नया संस्करण चाहिए।");}
        slot->db=db;slot->id=++pg_connection_next;return slot->id;
    }
    if(op==1){pg_connection *owner=NULL;for(int i=0;i<32;i++)if(a&&pg_connections[i].id==a)owner=&pg_connections[i];if(!owner)return 0;
        for(int i=0;i<128;i++)if(pg_statements[i].id&&pg_statements[i].owner==owner)pg_dispose(&pg_statements[i]);pg_PQfinish(owner->db);memset(owner,0,sizeof *owner);return 0;}
    if(op==4){
        pg_connection *owner=NULL;for(int i=0;i<32;i++)if(a&&pg_connections[i].id==a)owner=&pg_connections[i];if(!owner&&c==3)return 0;if(!owner)runtime_error(location,"PostgreSQL संबंध बंद है।");
        if(c==3&&owner->token!=b)return 0;
        if((c==0&&owner->token)||(c!=0&&(!b||b!=owner->token)))runtime_error(location,"PostgreSQL लेनदेन सक्रिय नहीं है।");
        if(c==1)pg_healthy(owner,location);
        PGresult *r=pg_checked(owner,pg_PQexec(owner->db,c==0?"BEGIN":c==1?"COMMIT":"ROLLBACK"),location);pg_PQclear(r);
        for(int i=0;i<128;i++)if(pg_statements[i].id&&pg_statements[i].owner==owner&&pg_statements[i].state)pg_rows_clear(&pg_statements[i],3);
        owner->failed=0;owner->token=c==0?++pg_transaction_next:0;return owner->token;
    }
    if(op==2){pg_connection *owner=pg_conn(a,location);pg_healthy(owner,location);pg_statement *slot=NULL;
        for(int i=0;i<128;i++)if(!pg_statements[i].id){slot=&pg_statements[i];break;}if(!slot)runtime_error(location,"बहुत अधिक खुले PostgreSQL कथन।");
        void *sql=bits_pointer(c);bh_instance(sql,location);
        char *query=pg_copy((const char*)text_data(sql),(size_t)text_length(sql),location);
        PGresult *r=pg_PQprepare(owner->db,"",query,0,NULL);
        if(!r||pg_PQresultStatus(r)!=1){free(query);pg_error(owner,r,location);}pg_PQclear(r);
        r=pg_PQdescribePrepared(owner->db,"");if(!r||pg_PQresultStatus(r)!=1){free(query);pg_error(owner,r,location);}
        if(pg_PQnparams(r)>999||pg_PQnfields(r)>256){free(query);pg_PQclear(r);runtime_error(location,"PostgreSQL परिणाम या मापदंड सीमा से बाहर है।");}
        slot->id=++pg_statement_next;slot->owner=owner;slot->sql=query;slot->meta=r;slot->n=pg_PQnparams(r);slot->row=-1;return slot->id;
    }
    if(op==3){for(int i=0;i<128;i++)if(a&&pg_statements[i].id==a){pg_dispose(&pg_statements[i]);break;}return 0;}
    pg_statement *s=pg_stmt(a,location);
    if(op==5)return s->n;
    if(op>=6&&op<=11){pg_ready(s,location);if(b<1||b>(uint64_t)s->n)runtime_error(location,"PostgreSQL बंधन सूचकांक सीमा से बाहर है।");char *value=NULL;
        if(op==6){char t[32];snprintf(t,sizeof t,"%lld",(long long)c);value=pg_copy(t,strlen(t),location);}
        if(op==7){double f;memcpy(&f,&c,8);if(!isfinite(f))runtime_error(location,"PostgreSQL दशमलव सीमित नहीं है।");char t[40];snprintf(t,sizeof t,"%.17g",f);value=pg_copy(t,strlen(t),location);}
        if(op==8)value=pg_copy(c?"true":"false",c?4:5,location);
        if(op==9){void *t=bits_pointer(c);bh_instance(t,location);size_t len=text_length(t);if(len>8388608||memchr(text_data(t),0,len))runtime_error(location,"अमान्य PostgreSQL पाठ।");value=pg_copy((const char*)text_data(t),len,location);}
        if(op==10){bh_collection *buffer=bits_pointer(c);bh_instance(buffer,location);if(buffer->size>8388608)runtime_error(location,"PostgreSQL मान सीमा से बाहर है।");value=malloc((size_t)buffer->size*2+3);if(!value)runtime_error(location,"स्मृति उपलब्ध नहीं है।");value[0]='\\';value[1]='x';for(uint64_t i=0;i<buffer->size;i++){unsigned char v=((unsigned char*)buffer->data)[i];value[2+2*i]="0123456789abcdef"[v>>4];value[3+2*i]="0123456789abcdef"[v&15];}value[2+2*buffer->size]=0;}
        free(s->params[b-1]);s->params[b-1]=value;s->bound[b-1]=1;return 0;
    }
    if(op==12||op==13){pg_healthy(s->owner,location);if(op==13&&s->state==2)return 0;if(op==12)pg_ready(s,location);if(s->state==3)runtime_error(location,"PostgreSQL कथन को रीसेट करें।");
        int cols=pg_PQnfields(s->meta);if(op==12&&cols)runtime_error(location,"PostgreSQL पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।");if(op==13&&!cols)runtime_error(location,"PostgreSQL बदलाव के लिए चलाओ उपयोग करें।");
        if(s->state==0){for(int i=0;i<s->n;i++)if(!s->bound[i])runtime_error(location,"सभी PostgreSQL मापदंड बाँधें।");
            PGresult *r=pg_PQexecParams(s->owner->db,s->sql,s->n,NULL,(const char *const*)s->params,NULL,NULL,0);
            if(!r||(pg_PQresultStatus(r)!=1&&pg_PQresultStatus(r)!=2)){s->state=3;pg_error(s->owner,r,location);}s->rows=r;
            if(op==12){const char *n=pg_PQcmdTuples(r);uint64_t count=*n?(uint64_t)strtoull(n,NULL,10):0;pg_rows_clear(s,2);return count;}
        }
        s->row++;if(s->row>=pg_PQntuples(s->rows)){pg_rows_clear(s,2);return 0;}
        uint64_t bytes=0;for(int i=0;i<cols;i++){unsigned int oid=pg_PQftype(s->rows,i);uint64_t n=(uint64_t)pg_PQgetlength(s->rows,s->row,i);if(oid==17)bytes+=n>=2?(n-2)/2:0;else if(oid!=20&&oid!=21&&oid!=23&&oid!=700&&oid!=701&&oid!=16)bytes+=n;}if(bytes>8388608){pg_rows_clear(s,3);runtime_error(location,"PostgreSQL पंक्ति सीमा से बाहर है।");}s->state=1;return 1;
    }
    if(op==14){pg_rows_clear(s,0);return 0;}
    if(op==15){pg_ready(s,location);for(int i=0;i<s->n;i++){free(s->params[i]);s->params[i]=NULL;s->bound[i]=0;}return 0;}
    if(op==16)return pg_PQnfields(s->meta);
    int col=pg_column(s,b,location);
    if(op==17)return pg_text(pg_PQfname(s->meta,col),location);
    if(s->state!=1)runtime_error(location,"PostgreSQL परिणाम की वर्तमान पंक्ति नहीं है।");
    int isnull=pg_PQgetisnull(s->rows,s->row,col);const char *kind=pg_kind(pg_PQftype(s->meta,col));
    if(op==18)return pg_text(isnull?"रिक्त":kind,location);if(op==19)return (uint64_t)isnull;
    if(op==20){const char *v=pg_cell(s,b,"पूर्णांक",location);char *end;errno=0;long long n=strtoll(v,&end,10);if(errno||*end)runtime_error(location,"PostgreSQL पूर्णांक सीमा से बाहर है।");return (uint64_t)n;}
    if(op==21){const char *v=pg_cell(s,b,"दशमलव",location);char *end;double n=strtod(v,&end);if(*end||!isfinite(n))runtime_error(location,"PostgreSQL दशमलव सीमित नहीं है।");uint64_t bits;memcpy(&bits,&n,8);return bits;}
    if(op==22)return !strcmp(pg_cell(s,b,"तार्किक",location),"t");
    if(op==23){if(strcmp(kind,"पाठ")&&strcmp(kind,"सटीकदशमलव")&&strcmp(kind,"समय"))runtime_error(location,"PostgreSQL स्तंभ का प्रकार मेल नहीं खाता।");return pg_text(pg_cell(s,b,kind,location),location);}
    if(op==24){const char *v=pg_cell(s,b,"बाइटबफ़र",location);size_t len=strlen(v);if(len<2||v[0]!='\\'||v[1]!='x'||len%2)runtime_error(location,"अमान्य PostgreSQL बाइटबफ़र।");len=(len-2)/2;bh_collection *buffer=bh_collection_new(info,len,location);for(size_t i=0;i<len;i++){unsigned x=0;for(int j=0;j<2;j++){char ch=v[2+2*i+j];int n=ch>='0'&&ch<='9'?ch-'0':ch>='a'&&ch<='f'?ch-'a'+10:-1;if(n<0)runtime_error(location,"अमान्य PostgreSQL बाइटबफ़र।");x=x*16+(unsigned)n;}((unsigned char*)buffer->data)[i]=(unsigned char)x;}return pointer_bits(buffer);}
    runtime_error(location,"अमान्य PostgreSQL क्रिया।");
}
