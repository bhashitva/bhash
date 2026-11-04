/* SPDX-License-Identifier: Apache-2.0
 * Detached asynchronous database state. Only the dispatcher touches Bhash values.
 * SQLite workers publish with release/acquire; no worker calls runtime_error,
 * allocates a Bhash object, or retains a Bhash pointer. */
#include <stdatomic.h>
#ifndef _WIN32
#include <pthread.h>
#include <poll.h>
#endif
#define AD_ROWS 1024u
#define AD_JOBS 64u
enum {AD_INTEGER=1,AD_REAL,AD_TEXT,AD_BYTES,AD_NULL,AD_BOOL,AD_DECIMAL,AD_TIME,AD_UNSUPPORTED};
typedef struct ad_value {int kind,isnull;uint64_t bits;unsigned char *data;size_t length;} ad_value;
typedef struct ad_connection ad_connection;
typedef struct ad_statement {
    uint64_t id;ad_connection *owner;sqlite3_stmt *sqlite;char *sql;PGresult *meta;
    int parameters,closed;struct ad_statement *next;
} ad_statement;
struct ad_connection {
    uint64_t id;int postgres,closed,busy,transaction,failed;
    sqlite3 *sqlite;PGconn *pg;ad_statement *statements;
};
typedef struct ad_job {
    uint64_t id,result;int operation,started,claimed,joined,cleanup;
    _Atomic int done,cancelled,setup_done,control_done,dispatch;
    ad_connection *connection,*opened;ad_statement *statement,*prepared;
    char *text;ad_value *parameters;size_t parameter_count;
    ad_value *cells;char **names;size_t rows,columns,bytes;
    char error[512];uint64_t deadline,reserved;
#ifdef _WIN32
    HANDLE thread;
#else
    pthread_t thread;
#endif
    int has_thread,thread_kind,phase,pg_status;void *cancel_object;
} ad_job;
static ad_job *ad_jobs[AD_JOBS];static ad_connection *ad_connections[32];static ad_statement *ad_statements[128];
static uint64_t ad_next,ad_handle,ad_reserved;static unsigned ad_running,ad_cleaning;
static void ad_pg_start(ad_job *j);
static void ad_pg_connect_worker(ad_job *j);
static void ad_pg_cancel_worker(ad_job *j);
static void ad_pg_poll(ad_job *j);
static void ad_pg_abort(ad_job *j);
static uint64_t ad_clock(void){
#ifdef _WIN32
    return GetTickCount64();
#else
    struct timespec t;if(clock_gettime(CLOCK_MONOTONIC,&t))return 0;return (uint64_t)t.tv_sec*1000+(uint64_t)t.tv_nsec/1000000;
#endif
}
static void ad_error(ad_job *j,const char *message){if(!j->error[0])snprintf(j->error,sizeof j->error,"%s",message);}
static void ad_value_free(ad_value *v){free(v->data);memset(v,0,sizeof *v);}
static void ad_connection_close(ad_connection *c){
    if(!c||c->closed)return;
    for(ad_statement *s=c->statements;s;s=s->next){
        if(s->sqlite){sqlite3_finalize(s->sqlite);s->sqlite=NULL;}
        if(s->meta){pg_PQclear(s->meta);s->meta=NULL;}s->closed=1;
    }
    if(c->sqlite){sqlite3_close_v2(c->sqlite);c->sqlite=NULL;}
    if(c->pg){pg_PQfinish(c->pg);c->pg=NULL;}c->closed=1;c->transaction=0;
}
static void ad_job_free(void *value){
    ad_job *j=value;if(!j)return;
    ad_reserved-=j->reserved;
    for(size_t i=0;i<j->parameter_count;i++)ad_value_free(&j->parameters[i]);free(j->parameters);
    for(size_t i=0;i<j->rows*j->columns;i++)ad_value_free(&j->cells[i]);free(j->cells);
    for(size_t i=0;i<j->columns;i++)free(j->names?j->names[i]:NULL);free(j->names);free(j->text);free(j);
}
static char *ad_copy(const void *value,size_t size){char *s=malloc(size+1);if(s){if(size)memcpy(s,value,size);s[size]=0;}return s;}
static int ad_sqlite_progress(void *value){ad_job *j=value;return atomic_load_explicit(&j->cancelled,memory_order_relaxed)||ad_clock()>=j->deadline;}
static int ad_sqlite_busy(void *value,int count){
    ad_job *j=value;if(count>=1000||ad_sqlite_progress(j))return 0;sqlite3_sleep(5);return 1;
}
static int ad_sqlite_check(ad_job *j,int code){if(code==SQLITE_OK)return 1;ad_error(j,db_message(code));return 0;}
static int ad_sqlite_exec(ad_job *j,const char *sql){return ad_sqlite_check(j,sqlite3_exec(j->connection->sqlite,sql,NULL,NULL,NULL));}
static int ad_sqlite_row(ad_job *j,sqlite3_stmt *query){
    if(j->rows>=AD_ROWS){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
    ad_value *cells=realloc(j->cells,(j->rows+1)*j->columns*sizeof *cells);
    if(!cells){ad_error(j,"स्मृति उपलब्ध नहीं है।");return 0;}j->cells=cells;
    ad_value *row=cells+j->rows*j->columns;memset(row,0,j->columns*sizeof *row);j->rows++;
    for(size_t i=0;i<j->columns;i++){
        ad_value *v=&row[i];v->kind=sqlite3_column_type(query,(int)i);
        if(v->kind==SQLITE_INTEGER){int64_t n=sqlite3_column_int64(query,(int)i);memcpy(&v->bits,&n,8);j->bytes+=8;}
        else if(v->kind==SQLITE_FLOAT){double n=sqlite3_column_double(query,(int)i);if(!isfinite(n)){ad_error(j,"डेटाकथन में सीमित दशमलव मान दें।");return 0;}memcpy(&v->bits,&n,8);j->bytes+=8;}
        else if(v->kind==SQLITE_TEXT||v->kind==SQLITE_BLOB){
            const void *data=v->kind==SQLITE_TEXT?(const void *)sqlite3_column_text(query,(int)i):sqlite3_column_blob(query,(int)i);
            int count=sqlite3_column_bytes(query,(int)i);if(count<0||(size_t)count>DB_LIMIT-j->bytes){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
            v->length=(size_t)count;v->data=(unsigned char *)ad_copy(data,v->length);if(!v->data){ad_error(j,"स्मृति उपलब्ध नहीं है।");return 0;}j->bytes+=v->length;
        }else j->bytes+=8;
        if(j->bytes>DB_LIMIT){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
    }
    return 1;
}
static void ad_sqlite_query(ad_job *j){
    ad_statement *s=j->statement;sqlite3_stmt *query=s->sqlite;
    if(j->connection->failed){ad_error(j,"डेटालेनदेन असफल है; वापस लें।");return;}
    if(j->parameter_count!=(size_t)s->parameters){ad_error(j,"डेटाकथन के सभी मापदंड बाँधें।");return;}
    sqlite3_reset(query);sqlite3_clear_bindings(query);
    for(size_t i=0;i<j->parameter_count;i++){
        ad_value *v=&j->parameters[i];int code;
        if(v->kind==AD_INTEGER||v->kind==AD_BOOL){int64_t n;memcpy(&n,&v->bits,8);code=sqlite3_bind_int64(query,(int)i+1,n);}
        else if(v->kind==AD_REAL){double n;memcpy(&n,&v->bits,8);code=sqlite3_bind_double(query,(int)i+1,n);}
        else if(v->kind==AD_TEXT)code=sqlite3_bind_text(query,(int)i+1,(const char *)v->data,(int)v->length,SQLITE_TRANSIENT);
        else if(v->kind==AD_BYTES)code=sqlite3_bind_blob(query,(int)i+1,v->data,(int)v->length,SQLITE_TRANSIENT);
        else code=sqlite3_bind_null(query,(int)i+1);
        if(!ad_sqlite_check(j,code))return;
    }
    int count=sqlite3_column_count(query);
    if((j->operation==3&&count)||(j->operation==4&&!count)){ad_error(j,j->operation==3?"डेटाकथन पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।":"डेटाकथन बदलाव के लिए चलाओ उपयोग करें।");return;}
    if(count<0||count>DB_COLUMNS){ad_error(j,"डेटा परिणाम की सीमा पार हो गई।");return;}
    j->columns=(size_t)count;
    if(count){
        j->names=calloc((size_t)count,sizeof *j->names);if(!j->names){ad_error(j,"स्मृति उपलब्ध नहीं है।");return;}
        for(int i=0;i<count;i++){const char *name=sqlite3_column_name(query,i);j->names[i]=ad_copy(name,strlen(name));if(!j->names[i]){ad_error(j,"स्मृति उपलब्ध नहीं है।");return;}}
    }
    sqlite3_int64 before=sqlite3_total_changes64(j->connection->sqlite);int code;
    while((code=sqlite3_step(query))==SQLITE_ROW)if(!ad_sqlite_row(j,query))break;
    if(code!=SQLITE_DONE&&!j->error[0])ad_sqlite_check(j,code);
    j->result=j->operation==3?(uint64_t)(sqlite3_total_changes64(j->connection->sqlite)-before):(uint64_t)j->rows;
    sqlite3_reset(query);
}
static void ad_sqlite_run(ad_job *j){
    int expected=0;if(atomic_load(&j->cancelled)||!atomic_compare_exchange_strong(&j->dispatch,&expected,1))goto done;
    ad_connection *c=j->connection;
    if(c){sqlite3_progress_handler(c->sqlite,500,ad_sqlite_progress,j);sqlite3_busy_handler(c->sqlite,ad_sqlite_busy,j);}
    if(j->operation==0){
        c=calloc(1,sizeof *c);if(!c){ad_error(j,"स्मृति उपलब्ध नहीं है।");goto done;}j->opened=c;
        if(!ad_sqlite_check(j,sqlite3_open_v2(j->text,&c->sqlite,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,NULL)))goto done;
        sqlite3_limit(c->sqlite,SQLITE_LIMIT_LENGTH,DB_LIMIT);sqlite3_limit(c->sqlite,SQLITE_LIMIT_SQL_LENGTH,65536);sqlite3_limit(c->sqlite,SQLITE_LIMIT_COLUMN,DB_COLUMNS);sqlite3_limit(c->sqlite,SQLITE_LIMIT_VARIABLE_NUMBER,999);sqlite3_limit(c->sqlite,SQLITE_LIMIT_ATTACHED,0);
        sqlite3_progress_handler(c->sqlite,500,ad_sqlite_progress,j);sqlite3_busy_handler(c->sqlite,ad_sqlite_busy,j);
        ad_sqlite_check(j,sqlite3_exec(c->sqlite,"PRAGMA foreign_keys=ON; PRAGMA schema_version",NULL,NULL,NULL));
    }else if(j->operation==2){
        if(c->failed){ad_error(j,"डेटालेनदेन असफल है; वापस लें।");goto done;}
        ad_statement *s=calloc(1,sizeof *s);if(!s){ad_error(j,"स्मृति उपलब्ध नहीं है।");goto done;}j->prepared=s;s->owner=c;
        if(!ad_sqlite_check(j,sqlite3_prepare_v3(c->sqlite,j->text,-1,SQLITE_PREPARE_PERSISTENT,&s->sqlite,NULL)))goto done;
        if(!s->sqlite){ad_error(j,"SQLite कथन असफल।");goto done;}s->parameters=sqlite3_bind_parameter_count(s->sqlite);
    }else if(j->operation==3||j->operation==4)ad_sqlite_query(j);
    else if(j->operation==5){if(j->statement->sqlite)sqlite3_finalize(j->statement->sqlite);j->statement->sqlite=NULL;j->statement->closed=1;}
    else if(j->operation==6){if(c->transaction)ad_error(j,"डेटालेनदेन पहले सक्रिय है।");else if(ad_sqlite_exec(j,"BEGIN DEFERRED")){c->transaction=1;c->failed=0;}}
    else if(j->operation==7){
        if(c->failed)ad_error(j,"डेटालेनदेन असफल है; वापस लें।");
        else if(!c->transaction)ad_error(j,"डेटालेनदेन सक्रिय नहीं है।");
        else {int code=sqlite3_exec(c->sqlite,"COMMIT",NULL,NULL,NULL);
            if(code==SQLITE_OK)c->transaction=0;
            else if((code&255)==SQLITE_IOERR||(code&255)==SQLITE_CORRUPT||(code&255)==SQLITE_NOTADB){ad_error(j,"लेनदेन का परिणाम अज्ञात है; स्वचालित पुनःप्रयास न करें।");ad_connection_close(c);}
            else ad_sqlite_check(j,code);
        }
    }else if(j->operation==8){if(c->transaction&&!sqlite3_get_autocommit(c->sqlite))ad_sqlite_exec(j,"ROLLBACK");c->transaction=0;c->failed=0;}
    else if(j->operation==9)ad_connection_close(c);
done:
    if(j->connection&&j->connection->sqlite){sqlite3_progress_handler(j->connection->sqlite,0,NULL,NULL);sqlite3_busy_timeout(j->connection->sqlite,5000);if(j->error[0]&&j->connection->transaction)j->connection->failed=1;}
    if(j->opened&&j->opened->sqlite){sqlite3_progress_handler(j->opened->sqlite,0,NULL,NULL);sqlite3_busy_timeout(j->opened->sqlite,5000);}
    if(j->error[0]||atomic_load(&j->cancelled)){
        if(j->opened){ad_connection_close(j->opened);free(j->opened);j->opened=NULL;}
        if(j->prepared){if(j->prepared->sqlite)sqlite3_finalize(j->prepared->sqlite);free(j->prepared);j->prepared=NULL;}
        if(atomic_load(&j->cancelled)&&atomic_load(&j->dispatch)==1&&j->operation!=7)ad_connection_close(j->connection);
    }
    atomic_store_explicit(&j->done,1,memory_order_release);
}
static void ad_worker_body(ad_job *j){
    if(j->thread_kind==2)ad_pg_connect_worker(j);
    else if(j->thread_kind==3)ad_pg_cancel_worker(j);
    else ad_sqlite_run(j);
}
#ifdef _WIN32
static DWORD WINAPI ad_worker(void *value){ad_worker_body(value);return 0;}
#else
static void *ad_worker(void *value){ad_worker_body(value);return NULL;}
#endif
static void ad_join(ad_job *j){
    if(!j->has_thread||j->joined)return;
    int ready=j->thread_kind==2?atomic_load_explicit(&j->setup_done,memory_order_acquire):j->thread_kind==3?atomic_load_explicit(&j->control_done,memory_order_acquire):atomic_load_explicit(&j->done,memory_order_acquire);
    if(!ready)return;
#ifdef _WIN32
    WaitForSingleObject(j->thread,INFINITE);CloseHandle(j->thread);
#else
    pthread_join(j->thread,NULL);
#endif
    j->joined=1;if(j->cleanup)ad_cleaning--;else ad_running--;
}
static int ad_launch(ad_job *j,int kind,int cleanup){
    if(cleanup?ad_cleaning>=2:ad_running>=4)return 0;
    j->thread_kind=kind;j->cleanup=cleanup;j->has_thread=0;j->joined=0;
#ifdef _WIN32
    j->thread=CreateThread(NULL,0,ad_worker,j,0,NULL);int failed=j->thread==NULL;
#else
    int failed=pthread_create(&j->thread,NULL,ad_worker,j)!=0;
#endif
    if(failed){ad_error(j,"डेटा कार्यकर्ता उपलब्ध नहीं है।");if(kind==2)atomic_store(&j->setup_done,1);else if(kind==3)atomic_store(&j->control_done,1);else atomic_store(&j->done,1);}
    else {j->has_thread=1;if(cleanup)ad_cleaning++;else ad_running++;}
    return 1;
}
static void ad_drive(void){
    for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i])ad_join(ad_jobs[i]);
    for(size_t i=0;i<AD_JOBS;i++){
        ad_job *j=ad_jobs[i];if(!j||atomic_load_explicit(&j->done,memory_order_acquire))continue;
        if(j->operation==1){
            if(!j->started){if(ad_launch(j,2,0))j->started=1;continue;}
            if(!atomic_load_explicit(&j->setup_done,memory_order_acquire))continue;
            ad_join(j);ad_pg_poll(j);continue;
        }
        if(j->connection&&j->connection->postgres){if(!j->started){j->started=1;ad_pg_start(j);}ad_pg_poll(j);continue;}
        if(j->started)continue;
        if(ad_launch(j,1,j->operation==5||j->operation==8||j->operation==9))j->started=1;
    }
}

static ad_job *ad_find(uint64_t id,const void *location){for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]&&ad_jobs[i]->id==id)return ad_jobs[i];runtime_error(location,"असमकालिक डेटा क्रिया बंद है।");}
static ad_job *ad_ready(uint64_t id,const void *location){ad_job *j=ad_find(id,location);if(!atomic_load_explicit(&j->done,memory_order_acquire))runtime_error(location,"डेटा क्रिया अभी पूरी नहीं हुई है।");ad_join(j);if(j->error[0])runtime_error(location,j->error);return j;}
static void ad_snapshot(ad_job *j,bh_collection *values,const void *location){
    if(values->size>999)runtime_error(location,"डेटा मापदंड सीमा से बाहर हैं।");
    uint64_t reserve=j->operation==4?DB_LIMIT:0;
    for(uint64_t i=0;i<values->size;i++){
        void *value=bits_pointer(values->data[i]);if(!value)continue;uint64_t kind=boxed_kind(value);
        if(kind==BH_TEXT)reserve+=text_length(bits_pointer(box_payload(value)));
        else if(kind==BH_REFERENCE){
            const bh_class_info *meta;memcpy(&meta,value,8);int collection=0;
            for(uint64_t k=0;k<meta->type_count;k++)if(meta->type_ids[k]==-4)collection=1;
            if(!collection||((bh_collection *)value)->info->kind!=BH_BUFFER)runtime_error(location,"असमर्थित डेटा मापदंड।");
            // Reserve the additional libpq hexadecimal upload representation too.
            reserve+=((bh_collection *)value)->size*(j->connection&&j->connection->postgres?3:1);
        }
        if(reserve>67108864)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");
    }
    if(reserve>67108864-ad_reserved)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");
    j->reserved=reserve;ad_reserved+=reserve;
    j->parameter_count=(size_t)values->size;j->parameters=calloc(j->parameter_count?j->parameter_count:1,sizeof *j->parameters);
    if(!j->parameters)runtime_error(location,"स्मृति उपलब्ध नहीं है।");size_t total=0;
    for(size_t i=0;i<j->parameter_count;i++){
        void *value=bits_pointer(values->data[i]);ad_value *v=&j->parameters[i];v->kind=AD_NULL;if(!value)continue;
        uint64_t kind=boxed_kind(value),bits=kind==BH_REFERENCE?0:box_payload(value);
        const void *data=NULL;size_t length=0;
        if(kind==BH_INT){v->kind=AD_INTEGER;v->bits=bits;}
        else if(kind==BH_BOOL){v->kind=AD_BOOL;v->bits=bits;}
        else if(kind==BH_REAL){v->kind=AD_REAL;v->bits=bits;}
        else if(kind==BH_TEXT){void *text=bits_pointer(bits);v->kind=AD_TEXT;data=text_data(text);length=(size_t)text_length(text);}
        else {
            const bh_class_info *info;memcpy(&info,value,8);int collection=0;
            for(uint64_t k=0;k<info->type_count;k++)if(info->type_ids[k]==-4)collection=1;
            if(!collection||((bh_collection *)value)->info->kind!=BH_BUFFER)runtime_error(location,"असमर्थित डेटा मापदंड।");
            bh_collection *buffer=value;v->kind=AD_BYTES;data=buffer->data;length=(size_t)buffer->size;
        }
        if(length>DB_LIMIT-total)runtime_error(location,"डेटा मापदंड सीमा से बाहर हैं।");total+=length;
        if(v->kind==AD_TEXT||v->kind==AD_BYTES){v->data=(unsigned char *)ad_copy(data,length);v->length=length;if(!v->data)runtime_error(location,"स्मृति उपलब्ध नहीं है।");}
    }
}
static void ad_reap(void){
    for(size_t i=0;i<128;i++){
        ad_statement *s=ad_statements[i];if(!s||(!s->closed&&!s->owner->closed))continue;
        ad_statement **link=&s->owner->statements;while(*link&&*link!=s)link=&(*link)->next;if(*link)*link=s->next;
        if(s->meta)pg_PQclear(s->meta);free(s->sql);free(s);ad_statements[i]=NULL;
    }
    for(size_t i=0;i<32;i++)if(ad_connections[i]&&ad_connections[i]->closed&&!ad_connections[i]->busy){free(ad_connections[i]);ad_connections[i]=NULL;}
}
static void ad_abort(ad_job *j){
    if(j->claimed&&atomic_load_explicit(&j->done,memory_order_acquire))return;
    if(j->operation==7){int expected=0;if(!atomic_compare_exchange_strong(&j->dispatch,&expected,2)&&expected==1)return;}
    if(j->operation==1||(j->connection&&j->connection->postgres)){ad_pg_abort(j);return;}
    if(atomic_load_explicit(&j->done,memory_order_acquire)){
        if(!j->opened&&!j->prepared)return;
        ad_join(j);j->started=0;j->has_thread=0;j->joined=0;atomic_store(&j->done,0);
    }
    atomic_store(&j->cancelled,1);
}
static const char *ad_kind(int kind){switch(kind){case AD_INTEGER:return "पूर्णांक";case AD_REAL:return "दशमलव";case AD_TEXT:return "पाठ";case AD_BYTES:return "बाइटबफ़र";case AD_BOOL:return "तार्किक";case AD_DECIMAL:return "सटीकदशमलव";case AD_TIME:return "समय";case AD_UNSUPPORTED:return "असमर्थित";default:return "रिक्त";}}
static ad_value *ad_cell(ad_job *j,uint64_t row,uint64_t column,const void *location){if(row>=j->rows||column>=j->columns)runtime_error(location,"डेटाकथन परिणाम सूचकांक सीमा से बाहर है।");return &j->cells[(size_t)row*j->columns+(size_t)column];}
static uint64_t ad_text_bytes(const unsigned char *data,size_t size,const void *location){if(!valid_utf8(data,size))runtime_error(location,"अमान्य यूनिकोड पाठ।");void *text=new_text(size,location);if(size)memcpy(text_data(text),data,size);return pointer_bits(text);}
static uint64_t ad_call(uint32_t op,uint64_t a,uint64_t b,uint64_t c,uint64_t d,const bh_collection_info *info,const void *location){
    if(op==16)return (uint64_t)(atomic_load(&ad_find(a,location)->dispatch)==1);
    if(op==15){uint64_t n=0;for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i])n++;for(size_t i=0;i<32;i++)if(ad_connections[i])n++;for(size_t i=0;i<128;i++)if(ad_statements[i])n++;return n;}
    if(op==14){for(size_t i=0;i<128;i++)if(ad_statements[i]&&ad_statements[i]->id==a&&!ad_statements[i]->closed)return (uint64_t)ad_statements[i]->parameters;runtime_error(location,"असमकालिक डेटाकथन बंद है।");}
    if(op==0){
        if(a>9)runtime_error(location,"अमान्य असमकालिक डेटा क्रिया।");
        if(a==1&&!pg_load())runtime_error(location,"PostgreSQL libpq 14+ रनटाइम उपलब्ध नहीं है।");
        size_t slot=0;while(slot<AD_JOBS&&ad_jobs[slot])slot++;if(slot==AD_JOBS)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");
        ad_connection *connection=NULL;ad_statement *statement=NULL;
        if(a==3||a==4||a==5){for(size_t i=0;i<128;i++)if(ad_statements[i]&&ad_statements[i]->id==b)statement=ad_statements[i];if(statement)connection=statement->owner;}
        else if(a>=2){for(size_t i=0;i<32;i++)if(ad_connections[i]&&ad_connections[i]->id==b)connection=ad_connections[i];}
        int absent=a>=2&&(!connection||connection->closed);
        if(absent&&a!=5&&a!=8&&a!=9)runtime_error(location,"असमकालिक डेटासंबंध बंद है।");
        if(connection&&connection->busy)runtime_error(location,"असमकालिक डेटासंबंध व्यस्त है।");
        if(a<2){size_t n=0;for(size_t i=0;i<32;i++)if(ad_connections[i])n++;for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]&&ad_jobs[i]->operation<2&&!ad_jobs[i]->claimed)n++;if(n>=32)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");}
        if(a==2){size_t n=0;for(size_t i=0;i<128;i++)if(ad_statements[i])n++;for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]&&ad_jobs[i]->operation==2&&!ad_jobs[i]->claimed)n++;if(n>=128)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");}
        ad_job *j=calloc(1,sizeof *j);if(!j)runtime_error(location,"स्मृति उपलब्ध नहीं है।");
        bh_cleanup guard={cleanups,j,ad_job_free};cleanups=&guard;
        j->operation=(int)a;j->connection=connection;j->statement=statement;j->deadline=ad_clock()+30000;
        j->text=db_string(c,location);ad_snapshot(j,bh_instance(bits_pointer(d),location),location);
        if(absent){j->connection=NULL;j->statement=NULL;atomic_store(&j->done,1);}
        else if(connection)connection->busy=1;
        j->id=++ad_next;ad_jobs[slot]=j;cleanups=guard.previous;ad_drive();return j->id;
    }
    ad_job *j=ad_find(a,location);
    if(op==1){ad_drive();return (uint64_t)atomic_load_explicit(&j->done,memory_order_acquire);}
    if(op==2){ad_abort(j);ad_drive();return 0;}
    if(op==4){
        if(!atomic_load_explicit(&j->done,memory_order_acquire))runtime_error(location,"डेटा क्रिया अभी पूरी नहीं हुई है।");ad_join(j);
        if(!j->claimed&&(j->opened||j->prepared))runtime_error(location,"डेटा क्रिया परिणाम का समापन शेष है।");
        if(j->connection)j->connection->busy=0;
        for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]==j)ad_jobs[i]=NULL;ad_job_free(j);ad_reap();return 0;
    }
    j=ad_ready(a,location);
    if(op==3){
        if(!j->claimed){
            if(j->opened){size_t i=0;while(i<32&&ad_connections[i])i++;if(i==32)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");j->opened->id=++ad_handle;ad_connections[i]=j->opened;j->result=j->opened->id;}
            if(j->prepared){size_t i=0;while(i<128&&ad_statements[i])i++;if(i==128)runtime_error(location,"असमकालिक डेटा क्षमता समाप्त।");ad_statement *s=j->prepared;s->id=++ad_handle;s->next=s->owner->statements;s->owner->statements=s;ad_statements[i]=s;j->result=s->id;}
            j->claimed=1;
        }return j->result;
    }
    if(op==5)return (uint64_t)j->rows;
    if(op==6)return (uint64_t)j->columns;
    if(op==7){if(b>=j->columns)runtime_error(location,"डेटाकथन स्तंभ सीमा से बाहर है।");return db_text(j->names[b],location);}
    ad_value *v=ad_cell(j,b,c,location);
    if(op==8)return db_text(ad_kind(v->kind),location);
    if(op==9)return (uint64_t)(v->kind==AD_NULL||v->isnull);
    if(v->kind==AD_NULL||v->isnull)runtime_error(location,"डेटाकथन स्तंभ रिक्त है।");
    if(op==10&&(v->kind==AD_INTEGER||v->kind==AD_BOOL))return v->bits;
    if(op==11&&v->kind==AD_REAL)return v->bits;
    if(op==12&&(v->kind==AD_TEXT||v->kind==AD_DECIMAL||v->kind==AD_TIME))return ad_text_bytes(v->data,v->length,location);
    if(op==13&&v->kind==AD_BYTES){bh_collection *buffer=bh_collection_new(info,(int64_t)v->length,location);if(v->length)memcpy(buffer->data,v->data,v->length);return pointer_bits(buffer);}
    runtime_error(location,"डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");return 0;
}

static void ad_pg_error(ad_job *j,PGresult *r){
    const char *state=r?pg_PQresultErrorField(r,'C'):NULL;char code[6]="XXXXX";
    if(state&&strlen(state)==5){int valid=1;for(int i=0;i<5;i++)if(!((state[i]>='A'&&state[i]<='Z')||(state[i]>='0'&&state[i]<='9')))valid=0;if(valid)memcpy(code,state,5);}
    if(j->operation==7&&(!strcmp(code,"XXXXX")||!strncmp(code,"08",2)||!strcmp(code,"40003")||!strcmp(code,"57P01")||!strcmp(code,"57P02")||!strcmp(code,"57P03")))ad_error(j,"लेनदेन का परिणाम अज्ञात है; स्वचालित पुनःप्रयास न करें।");
    else {char message[100];snprintf(message,sizeof message,"PostgreSQL त्रुटि [%s]।",code);ad_error(j,message);}
    if(j->connection&&j->connection->transaction)j->connection->failed=1;
}
static void ad_pg_connect_worker(ad_job *j){
    if(atomic_load(&j->cancelled)){atomic_store_explicit(&j->setup_done,1,memory_order_release);return;}
    char *copy=ad_copy(j->text,strlen(j->text));if(!copy){ad_error(j,"स्मृति उपलब्ध नहीं है।");goto done;}
    char *values[9],*p=copy;
    for(int i=0;i<9;i++){values[i]=p;char *end=strchr(p,'\n');if(!end){ad_error(j,"अमान्य PostgreSQL विन्यास।");goto free_copy;}*end=0;p=end+1;}
    if(*p){ad_error(j,"अमान्य PostgreSQL विन्यास।");goto free_copy;}
    ad_connection *c=calloc(1,sizeof *c);if(!c){ad_error(j,"स्मृति उपलब्ध नहीं है।");goto free_copy;}c->postgres=1;j->opened=c;
    char options[200];snprintf(options,sizeof options,"-c statement_timeout=%s -c lock_timeout=%s -c timezone=UTC -c datestyle=ISO -c standard_conforming_strings=on -c bytea_output=hex",values[8],values[8]);
    const char *keys[]={"host","port","dbname","user","password","sslmode","sslrootcert","connect_timeout","options","client_encoding","application_name","gssencmode",NULL};
    const char *config[]={values[0],values[1],values[2],values[3],values[4],values[5],*values[6]?values[6]:NULL,values[7],options,"UTF8","Bhash","disable",NULL};
    // DNS resolution in connectStart can block. It runs on a bounded worker;
    // only the returned PGconn is transferred to dispatcher-owned polling.
    c->pg=pg_PQconnectStartParams(keys,config,0);j->phase=10;j->pg_status=2;
    if(!c->pg||pg_PQstatus(c->pg)==1)ad_pg_error(j,NULL);
free_copy:memset(copy,0,strlen(j->text));free(copy);
done:atomic_store_explicit(&j->setup_done,1,memory_order_release);
}
static int ad_pg_socket_ready(PGconn *db,int writing){
    int fd=pg_PQsocket(db);if(fd<0)return 1;
#ifdef _WIN32
    WSAPOLLFD p;memset(&p,0,sizeof p);p.fd=(SOCKET)fd;p.events=writing?POLLWRNORM:POLLRDNORM;return WSAPoll(&p,1,0)!=0;
#else
    struct pollfd p={fd,(short)(writing?POLLOUT:POLLIN),0};return poll(&p,1,0)!=0;
#endif
}
static void ad_pg_cancel_worker(ad_job *j){
    PGcancel *cancel=j->cancel_object;if(cancel){char message[256];(void)pg_PQcancel(cancel,message,sizeof message);pg_PQfreeCancel(cancel);j->cancel_object=NULL;}
    atomic_store_explicit(&j->control_done,1,memory_order_release);
}
static void ad_pg_finish(ad_job *j,int discard){
    if(j->operation==7&&!strcmp(j->error,"लेनदेन का परिणाम अज्ञात है; स्वचालित पुनःप्रयास न करें।"))discard=1;
    if(discard){
        ad_connection_close(j->connection);
        if(j->opened){ad_connection_close(j->opened);free(j->opened);j->opened=NULL;}
    }
    if(j->error[0]||atomic_load(&j->cancelled)){
        if(j->prepared){if(j->prepared->meta)pg_PQclear(j->prepared->meta);free(j->prepared->sql);free(j->prepared);j->prepared=NULL;}
    }
    atomic_store_explicit(&j->done,1,memory_order_release);
}
static void ad_pg_abort(ad_job *j){
    if(j->claimed&&atomic_load(&j->done))return;
    if(j->operation==7){int expected=0;if(!atomic_compare_exchange_strong(&j->dispatch,&expected,2)&&expected==1)return;}
    if(atomic_load(&j->done)&&!j->opened&&!j->prepared)return;
    atomic_store(&j->cancelled,1);
    if(atomic_load(&j->done)){ad_pg_finish(j,1);return;}
}
static int ad_pg_parameters(ad_job *j,char ***out){
    char **values=calloc(j->parameter_count?j->parameter_count:1,sizeof *values);if(!values)return 0;
    for(size_t i=0;i<j->parameter_count;i++){
        ad_value *v=&j->parameters[i];char number[64];const void *data=NULL;size_t size=0;
        if(v->kind==AD_NULL)continue;
        if(v->kind==AD_INTEGER){int64_t n;memcpy(&n,&v->bits,8);snprintf(number,sizeof number,"%lld",(long long)n);data=number;size=strlen(number);}
        else if(v->kind==AD_REAL){double n;memcpy(&n,&v->bits,8);snprintf(number,sizeof number,"%.17g",n);data=number;size=strlen(number);}
        else if(v->kind==AD_BOOL){data=v->bits?"true":"false";size=v->bits?4:5;}
        else if(v->kind==AD_TEXT){if(memchr(v->data,0,v->length)){ad_error(j,"अमान्य PostgreSQL पाठ।");goto fail;}data=v->data;size=v->length;}
        else if(v->kind==AD_BYTES){
            values[i]=malloc(v->length*2+3);if(!values[i])goto fail;values[i][0]='\\';values[i][1]='x';
            for(size_t n=0;n<v->length;n++){values[i][2+2*n]="0123456789abcdef"[v->data[n]>>4];values[i][3+2*n]="0123456789abcdef"[v->data[n]&15];}values[i][v->length*2+2]=0;continue;
        }
        values[i]=ad_copy(data,size);if(!values[i])goto fail;
    }
    *out=values;return 1;
fail:for(size_t i=0;i<j->parameter_count;i++)free(values[i]);free(values);return 0;
}
static void ad_pg_start(ad_job *j){
    ad_connection *c=j->connection;
    if(atomic_load(&j->cancelled)){ad_pg_finish(j,1);return;}
    if(j->operation==9){ad_connection_close(c);ad_pg_finish(j,0);return;}
    if(j->operation==5){j->statement->closed=1;ad_pg_finish(j,0);return;}
    if(c->failed&&j->operation!=8){ad_error(j,"डेटालेनदेन असफल है; वापस लें।");ad_pg_finish(j,0);return;}
    int expected=0;if(!atomic_compare_exchange_strong(&j->dispatch,&expected,1)){ad_pg_finish(j,0);return;}
    int sent=0;j->phase=20;
    if(j->operation==2){
        if(j->parameter_count!=1||j->parameters[0].kind!=AD_TEXT){ad_error(j,"अमान्य PostgreSQL कथन।");ad_pg_finish(j,0);return;}
        ad_statement *s=calloc(1,sizeof *s);if(!s){ad_error(j,"स्मृति उपलब्ध नहीं है।");ad_pg_finish(j,0);return;}j->prepared=s;s->owner=c;
        s->sql=ad_copy(j->parameters[0].data,j->parameters[0].length);if(!s->sql){ad_error(j,"स्मृति उपलब्ध नहीं है।");ad_pg_finish(j,0);return;}
        sent=pg_PQsendPrepare(c->pg,"",s->sql,0,NULL);j->phase=21;
    }else if(j->operation==3||j->operation==4){
        ad_statement *s=j->statement;int columns=pg_PQnfields(s->meta);
        if(j->parameter_count!=(size_t)s->parameters){ad_error(j,"डेटाकथन के सभी मापदंड बाँधें।");ad_pg_finish(j,0);return;}
        if((j->operation==3&&columns)||(j->operation==4&&!columns)){ad_error(j,j->operation==3?"डेटाकथन पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।":"डेटाकथन बदलाव के लिए चलाओ उपयोग करें।");ad_pg_finish(j,0);return;}
        char **values=NULL;if(!ad_pg_parameters(j,&values)){ad_error(j,"स्मृति उपलब्ध नहीं है।");ad_pg_finish(j,0);return;}
        sent=pg_PQsendQueryParams(c->pg,s->sql,s->parameters,NULL,(const char *const *)values,NULL,NULL,0);
        for(size_t i=0;i<j->parameter_count;i++)free(values[i]);free(values);
        if(sent&&!pg_PQsetSingleRowMode(c->pg))sent=0;
    }else {
        if(j->operation==6&&c->transaction){ad_error(j,"डेटालेनदेन पहले सक्रिय है।");ad_pg_finish(j,0);return;}
        if(j->operation==7&&!c->transaction){ad_error(j,"डेटालेनदेन सक्रिय नहीं है।");ad_pg_finish(j,0);return;}
        sent=pg_PQsendQuery(c->pg,j->operation==6?"BEGIN":j->operation==7?"COMMIT":"ROLLBACK");
    }
    if(!sent){ad_pg_error(j,NULL);ad_pg_finish(j,1);}
}
static int ad_pg_row(ad_job *j,PGresult *r){
    int columns=pg_PQnfields(r);if(columns<0||columns>DB_COLUMNS){ad_error(j,"डेटा परिणाम की सीमा पार हो गई।");return 0;}
    if(!j->names){
        j->columns=(size_t)columns;j->names=calloc(j->columns?j->columns:1,sizeof *j->names);if(!j->names)return 0;
        for(int i=0;i<columns;i++){const char *name=pg_PQfname(r,i);j->names[i]=ad_copy(name,strlen(name));if(!j->names[i])return 0;}
    }
    if(pg_PQntuples(r)==0)return 1;
    if(j->rows>=AD_ROWS){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
    ad_value *cells=realloc(j->cells,(j->rows+1)*j->columns*sizeof *cells);if(!cells)return 0;j->cells=cells;
    ad_value *row=cells+j->rows*j->columns;memset(row,0,j->columns*sizeof *row);j->rows++;
    for(int i=0;i<columns;i++){
        ad_value *v=&row[i];unsigned oid=pg_PQftype(r,i);v->kind=oid==20||oid==21||oid==23?AD_INTEGER:oid==700||oid==701?AD_REAL:oid==16?AD_BOOL:oid==17?AD_BYTES:oid==1700?AD_DECIMAL:!strcmp(pg_kind(oid),"समय")?AD_TIME:!strcmp(pg_kind(oid),"पाठ")?AD_TEXT:AD_UNSUPPORTED;
        v->isnull=pg_PQgetisnull(r,0,i);if(v->isnull){v->kind=AD_NULL;j->bytes+=8;if(j->bytes>DB_LIMIT){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}continue;}
        const char *text=pg_PQgetvalue(r,0,i);size_t size=(size_t)pg_PQgetlength(r,0,i);char *end;
        if(v->kind==AD_INTEGER){errno=0;int64_t n=(int64_t)strtoll(text,&end,10);if(errno||*end)return 0;memcpy(&v->bits,&n,8);j->bytes+=8;}
        else if(v->kind==AD_REAL){double n=strtod(text,&end);if(*end||!isfinite(n))return 0;memcpy(&v->bits,&n,8);j->bytes+=8;}
        else if(v->kind==AD_BOOL){v->bits=(uint64_t)!strcmp(text,"t");j->bytes+=8;}
        else {
            if(v->kind==AD_UNSUPPORTED){ad_error(j,"डेटाकथन स्तंभ का प्रकार मेल नहीं खाता।");return 0;}
            if(v->kind==AD_BYTES){if(size<2||text[0]!='\\'||text[1]!='x'||size%2)return 0;size=(size-2)/2;}
            if(size>DB_LIMIT-j->bytes){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
            v->length=size;v->data=malloc(size+1);if(!v->data)return 0;
            if(v->kind==AD_BYTES){for(size_t k=0;k<size;k++){unsigned value=0;for(int n=0;n<2;n++){char ch=text[2+2*k+(size_t)n];int digit=ch>='0'&&ch<='9'?ch-'0':ch>='a'&&ch<='f'?ch-'a'+10:-1;if(digit<0)return 0;value=value*16+(unsigned)digit;}v->data[k]=(unsigned char)value;}}
            else memcpy(v->data,text,size);v->data[size]=0;j->bytes+=size;
        }
        if(j->bytes>DB_LIMIT){ad_error(j,"डेटा परिणाम की सीमा १०२४ पंक्तियाँ / ८३८८६०८ बाइट है।");return 0;}
    }
    return 1;
}
static void ad_pg_poll(ad_job *j){
    if(atomic_load(&j->done))return;
    if(j->operation==1&&!atomic_load_explicit(&j->setup_done,memory_order_acquire))return;
    ad_connection *c=j->operation==1?j->opened:j->connection;
    if(!c||!c->pg||(j->operation==1&&j->error[0])){ad_pg_error(j,NULL);ad_pg_finish(j,1);return;}
    if(atomic_load(&j->cancelled)){
        if(j->operation==1){ad_pg_finish(j,1);return;}
        if(j->thread_kind!=3){
            if(ad_cleaning>=2)return;
            j->cancel_object=pg_PQgetCancel(c->pg);
            if(!j->cancel_object){ad_pg_finish(j,1);return;}
            (void)ad_launch(j,3,1);return;
        }
        if(atomic_load_explicit(&j->control_done,memory_order_acquire)){ad_join(j);if(j->cancel_object){pg_PQfreeCancel(j->cancel_object);j->cancel_object=NULL;}ad_pg_finish(j,1);}return;
    }
    if(ad_clock()>=j->deadline){
        if(j->operation==7){ad_error(j,"लेनदेन का परिणाम अज्ञात है; स्वचालित पुनःप्रयास न करें।");ad_pg_finish(j,1);}
        else {ad_error(j,"कार्य की समयसीमा समाप्त।");atomic_store(&j->cancelled,1);}return;
    }
    if(j->phase==10){
        if(!ad_pg_socket_ready(c->pg,j->pg_status==2))return;
        j->pg_status=pg_PQconnectPoll(c->pg);
        if(j->pg_status==0){ad_pg_error(j,NULL);ad_pg_finish(j,1);}
        else if(j->pg_status==3){
            if(pg_PQserverVersion(c->pg)<140000||pg_PQsetnonblocking(c->pg,1)){ad_pg_error(j,NULL);ad_pg_finish(j,1);}
            else ad_pg_finish(j,0);
        }return;
    }
    int flushed=pg_PQflush(c->pg);if(flushed<0||!pg_PQconsumeInput(c->pg)){ad_pg_error(j,NULL);ad_pg_finish(j,1);return;}
    if(flushed)return;
    for(int turn=0;turn<32&&!pg_PQisBusy(c->pg);turn++){
        PGresult *r=pg_PQgetResult(c->pg);
        if(!r){
            if(j->phase==21&&!j->error[0]){j->phase=22;if(!pg_PQsendDescribePrepared(c->pg,"")){ad_pg_error(j,NULL);ad_pg_finish(j,1);}return;}
            if(j->operation==6&&!j->error[0]){c->transaction=1;c->failed=0;}
            if((j->operation==7||j->operation==8)&&!j->error[0]){c->transaction=0;c->failed=0;}
            if(j->error[0]&&c->transaction)c->failed=1;
            if(j->operation==4)j->result=(uint64_t)j->rows;
            ad_pg_finish(j,0);return;
        }
        int status=pg_PQresultStatus(r);
        if(status!=1&&status!=2&&status!=9){ad_pg_error(j,r);pg_PQclear(r);continue;}
        if(j->phase==22){j->prepared->meta=r;j->prepared->parameters=pg_PQnparams(r);if(j->prepared->parameters>999||pg_PQnfields(r)>DB_COLUMNS)ad_error(j,"डेटा परिणाम की सीमा पार हो गई।");r=NULL;}
        else if(j->operation==4&&(status==9||status==2)){
            if(!ad_pg_row(j,r)){ad_error(j,"डेटा परिणाम पढ़ा नहीं जा सका।");pg_PQclear(r);atomic_store(&j->cancelled,1);return;}
        }else if(j->operation==3&&status==1){const char *count=pg_PQcmdTuples(r);j->result=*count?(uint64_t)strtoull(count,NULL,10):0;}
        if(r)pg_PQclear(r);
    }
}

/* Drain foreign work before SQLite shutdown. This also covers an uncaught
 * application exception leaving an explicitly opened resource unclosed. */
static void ad_shutdown(void){
    for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i])ad_abort(ad_jobs[i]);
    for(;;){
        ad_drive();int pending=0;
        for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]&&!atomic_load_explicit(&ad_jobs[i]->done,memory_order_acquire))pending=1;
        if(!pending)break;sqlite3_sleep(1);
    }
    for(size_t i=0;i<AD_JOBS;i++)if(ad_jobs[i]){
        ad_job *j=ad_jobs[i];ad_join(j);
        if(!j->claimed){
            if(j->prepared){if(j->prepared->sqlite)sqlite3_finalize(j->prepared->sqlite);if(j->prepared->meta)pg_PQclear(j->prepared->meta);free(j->prepared->sql);free(j->prepared);}
            if(j->opened){ad_connection_close(j->opened);free(j->opened);}
        }
        if(j->connection)j->connection->busy=0;ad_job_free(j);ad_jobs[i]=NULL;
    }
    for(size_t i=0;i<32;i++)if(ad_connections[i])ad_connection_close(ad_connections[i]);
    ad_reap();
}
