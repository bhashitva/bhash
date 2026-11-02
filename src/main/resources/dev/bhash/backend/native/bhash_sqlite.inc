/* SPDX-License-Identifier: Apache-2.0
 * SQLite owns every address in these registries. No Bhash references survive a call.
 * Included only for programs using the database intrinsics. */
#include "sqlite3.h"
#define DB_LIMIT 8388608
#define DB_COLUMNS 256
typedef struct {uint64_t id,token;sqlite3 *db;int failed;} db_connection;
typedef struct {uint64_t id;db_connection *owner;sqlite3_stmt *query;int state;unsigned char bound[999],types[DB_COLUMNS];} db_statement;
static db_connection db_connections[32];
static db_statement db_statements[128];
static uint64_t db_connection_next,db_statement_next,db_transaction_next;
static const char *db_message(int code){
    switch(code&255){case SQLITE_BUSY:case SQLITE_LOCKED:return "SQLite डेटाबेस व्यस्त है।";
        case SQLITE_CONSTRAINT:return "SQLite बाधा का उल्लंघन।";case SQLITE_READONLY:return "SQLite डेटाबेस केवल पढ़ने योग्य है।";
        case SQLITE_CORRUPT:case SQLITE_NOTADB:return "अमान्य SQLite डेटाबेस।";case SQLITE_TOOBIG:return "SQLite मान की सीमा ८३८८६०८ बाइट है।";
        case SQLITE_IOERR:case SQLITE_CANTOPEN:return "SQLite फ़ाइल तक पहुँच असफल।";default:return "SQLite कथन असफल।";}
}
static void db_check(int code,const void *location){if(code!=SQLITE_OK)runtime_error(location,db_message(code));}
static db_connection *db_conn(uint64_t id,const void *location){
    for(size_t i=0;i<32;i++)if(id&&db_connections[i].id==id)return &db_connections[i];
    runtime_error(location,"SQLite डेटाबेस बंद है।");
}
static db_statement *db_stmt(uint64_t id,const void *location){
    for(size_t i=0;i<128;i++)if(id&&db_statements[i].id==id)return &db_statements[i];
    runtime_error(location,"SQLite कथन बंद है।");
}
static void db_end_statement(db_statement *s){sqlite3_finalize(s->query);memset(s,0,sizeof *s);}
static void db_end_connection(db_connection *c){
    for(size_t i=0;i<128;i++)if(db_statements[i].id&&db_statements[i].owner==c)db_end_statement(&db_statements[i]);
    sqlite3_close_v2(c->db);memset(c,0,sizeof *c);
}
static void db_shutdown(void){for(size_t i=0;i<32;i++)if(db_connections[i].id)db_end_connection(&db_connections[i]);sqlite3_shutdown();}
static void db_initialize(const void *location){static int initialized;
    if(!initialized){db_check(sqlite3_initialize(),location);if(atexit(db_shutdown))runtime_error(location,"SQLite रनटाइम उपलब्ध नहीं है।");initialized=1;}
    if(strcmp(sqlite3_libversion(),"3.54.1"))runtime_error(location,"SQLite रनटाइम संस्करण मेल नहीं खाता।");
}
static int db_column(db_statement *s,uint64_t column,const void *location){
    if(column>=(uint64_t)sqlite3_column_count(s->query))runtime_error(location,"SQLite स्तंभ सूचकांक सीमा से बाहर है।");return (int)column;
}
static int db_cell(db_statement *s,uint64_t column,int expected,const void *location){
    int at=db_column(s,column,location);if(s->state!=1)runtime_error(location,"SQLite परिणाम की वर्तमान पंक्ति नहीं है।");
    int kind=s->types[at];if(kind==SQLITE_NULL)runtime_error(location,"SQLite स्तंभ रिक्त है।");
    if(kind!=expected&&!(expected==SQLITE_FLOAT&&kind==SQLITE_INTEGER))runtime_error(location,"SQLite स्तंभ का प्रकार मेल नहीं खाता।");return at;
}
static void db_ready(db_statement *s,const void *location){if(s->state)runtime_error(location,"SQLite कथन को रीसेट करें।");}
static int db_binding(db_statement *s,uint64_t index,const void *location){
    if(index<1||index>(uint64_t)sqlite3_bind_parameter_count(s->query))runtime_error(location,"SQLite बंधन सूचकांक सीमा से बाहर है।");db_ready(s,location);return (int)index;
}
static void db_bound(db_statement *s,const void *location){for(int i=0;i<sqlite3_bind_parameter_count(s->query);i++)if(!s->bound[i])runtime_error(location,"सभी SQLite मापदंड बाँधें।");}
static void db_boundary(db_connection *c){for(size_t i=0;i<128;i++)if(db_statements[i].id&&db_statements[i].owner==c&&db_statements[i].state){sqlite3_reset(db_statements[i].query);db_statements[i].state=3;}}
static uint64_t db_text(const char *value,const void *location){
    size_t n=strlen(value);if(!valid_utf8((const unsigned char *)value,n))runtime_error(location,"अमान्य यूनिकोड पाठ।");
    void *result=new_text(n,location);memcpy(text_data(result),value,n);return pointer_bits(result);
}
uint64_t bh_database_call(uint32_t op,uint64_t a,uint64_t b,uint64_t c,uint64_t d,const bh_collection_info *info,const void *location){
    db_initialize(location);
    if(op==19)return db_text(sqlite3_libversion(),location);
    if(op==0){
        db_connection *slot=NULL;for(size_t i=0;i<32;i++)if(!db_connections[i].id){slot=&db_connections[i];break;}
        if(!slot)runtime_error(location,"बहुत अधिक खुले SQLite डेटाबेस।");
        bh_instance(bits_pointer(a),location);sqlite3 *database=NULL;
        int result=sqlite3_open_v2((const char *)text_data(bits_pointer(a)),&database,SQLITE_OPEN_READWRITE|SQLITE_OPEN_CREATE|SQLITE_OPEN_FULLMUTEX,NULL);
        if(result!=SQLITE_OK){sqlite3_close_v2(database);runtime_error(location,"SQLite डेटाबेस खोला नहीं जा सका।");}
        sqlite3_limit(database,SQLITE_LIMIT_LENGTH,DB_LIMIT);sqlite3_limit(database,SQLITE_LIMIT_SQL_LENGTH,65536);
        sqlite3_limit(database,SQLITE_LIMIT_COLUMN,DB_COLUMNS);sqlite3_limit(database,SQLITE_LIMIT_VARIABLE_NUMBER,999);sqlite3_limit(database,SQLITE_LIMIT_ATTACHED,0);
        result=sqlite3_busy_timeout(database,5000);
        if(result==SQLITE_OK)result=sqlite3_exec(database,"PRAGMA foreign_keys=ON; PRAGMA schema_version",NULL,NULL,NULL);
        if(result!=SQLITE_OK){sqlite3_close_v2(database);runtime_error(location,"SQLite डेटाबेस खोला नहीं जा सका।");}
        slot->id=++db_connection_next;slot->db=database;return slot->id;
    }
    if(op==1){
        if(!a||a>db_connection_next)runtime_error(location,"SQLite डेटाबेस बंद है।");
        for(size_t i=0;i<32;i++)if(db_connections[i].id==a){db_end_connection(&db_connections[i]);break;}return 0;
    }
    if(op==2){
        db_connection *owner=db_conn(a,location);db_statement *slot=NULL;
        for(size_t i=0;i<128;i++)if(!db_statements[i].id){slot=&db_statements[i];break;}
        if(!slot)runtime_error(location,"बहुत अधिक खुले SQLite कथन।");
        bh_instance(bits_pointer(b),location);sqlite3_stmt *query=NULL;
        int result=sqlite3_prepare_v3(owner->db,(const char *)text_data(bits_pointer(b)),(int)text_length(bits_pointer(b))+1,SQLITE_PREPARE_PERSISTENT,&query,NULL);
        if(result!=SQLITE_OK||!query){sqlite3_finalize(query);runtime_error(location,db_message(result));}
        slot->id=++db_statement_next;slot->owner=owner;slot->query=query;return slot->id;
    }
    if(op==3){
        if(!a||a>db_statement_next)runtime_error(location,"SQLite कथन बंद है।");
        for(size_t i=0;i<128;i++)if(db_statements[i].id==a){db_end_statement(&db_statements[i]);break;}return 0;
    }
    if(op==18){
        db_connection *owner=db_conn(a,location);
        if(c==0){if(owner->token)runtime_error(location,"SQLite लेनदेन पहले से सक्रिय है।");
            db_boundary(owner);db_check(sqlite3_exec(owner->db,"BEGIN DEFERRED",NULL,NULL,NULL),location);owner->failed=0;owner->token=++db_transaction_next;return owner->token;}
        if(!b||owner->token!=b){if(c==3)return 0;runtime_error(location,"SQLite लेनदेन सक्रिय नहीं है।");}
        if(c==1&&owner->failed)runtime_error(location,"SQLite लेनदेन असफल है; वापस लें।");
        db_boundary(owner);int result=sqlite3_exec(owner->db,c==1?"COMMIT":"ROLLBACK",NULL,NULL,NULL);
        if(result!=SQLITE_OK&&!((c==3||(c==2&&owner->failed))&&(result&255)==SQLITE_ERROR))db_check(result,location);
        owner->token=0;return 0;
    }
    db_statement *s=db_stmt(a,location);
    if(op>=4&&op<=7){
        int index=db_binding(s,b,location),result=SQLITE_OK;
        if(op==4)result=sqlite3_bind_int64(s->query,index,(int64_t)c);
        else if(op==5){double value;memcpy(&value,&c,8);if(!isfinite(value))runtime_error(location,"SQLite में सीमित दशमलव मान दें।");result=sqlite3_bind_double(s->query,index,value);}
        else if(op==7)result=sqlite3_bind_null(s->query,index);
        else {
            bh_collection *buffer=bits_pointer(d);bh_instance(buffer,location);
            if(buffer->size>DB_LIMIT)runtime_error(location,"SQLite मान की सीमा ८३८८६०८ बाइट है।");
            static const unsigned char empty=0;const void *bytes=buffer->size?(const void *)buffer->data:&empty;
            if(c==3){if(!valid_utf8(bytes,buffer->size))runtime_error(location,"अमान्य UTF-8 बाइटबफ़र।");result=sqlite3_bind_text(s->query,index,bytes,(int)buffer->size,SQLITE_TRANSIENT);}
            else if(c==4)result=sqlite3_bind_blob(s->query,index,bytes,(int)buffer->size,SQLITE_TRANSIENT);
            else runtime_error(location,"SQLite स्तंभ का प्रकार मेल नहीं खाता।");
        }
        db_check(result,location);s->bound[index-1]=1;return 0;
    }
    if(op==8||op==9){
        if(s->owner->token&&s->owner->failed)runtime_error(location,"SQLite लेनदेन असफल है; वापस लें।");
        if(op==9&&s->state==2)return 0;
        if(op==8)db_ready(s,location);else if(s->state==3)runtime_error(location,"SQLite कथन को रीसेट करें।");
        int columns=sqlite3_column_count(s->query);
        if(op==8&&columns)runtime_error(location,"SQLite पंक्तियाँ पढ़ने के लिए अगला उपयोग करें।");
        if(op==9&&!columns)runtime_error(location,"SQLite बदलाव के लिए चलाओ उपयोग करें।");
        db_bound(s,location);sqlite3_int64 before=sqlite3_total_changes64(s->owner->db);int result=sqlite3_step(s->query);
        if(result!=SQLITE_ROW&&result!=SQLITE_DONE){if(s->owner->token)s->owner->failed=1;sqlite3_reset(s->query);s->state=3;runtime_error(location,db_message(result));}
        if(result==SQLITE_DONE){sqlite3_reset(s->query);s->state=2;return op==8?(uint64_t)(sqlite3_total_changes64(s->owner->db)-before):0;}
        for(int i=0;i<columns;i++)s->types[i]=(unsigned char)sqlite3_column_type(s->query,i);
        uint64_t bytes=0;for(int i=0;i<columns;i++)if(s->types[i]==SQLITE_TEXT||s->types[i]==SQLITE_BLOB)bytes+=(uint64_t)sqlite3_column_bytes(s->query,i);
        if(bytes>DB_LIMIT){sqlite3_reset(s->query);s->state=3;runtime_error(location,"SQLite पंक्ति की सीमा ८३८८६०८ बाइट है।");}
        s->state=1;return 1;
    }
    if(op==10){sqlite3_reset(s->query);s->state=0;return 0;}
    if(op==11){db_ready(s,location);db_check(sqlite3_clear_bindings(s->query),location);memset(s->bound,0,sizeof s->bound);return 0;}
    if(op==12)return (uint64_t)sqlite3_column_count(s->query);
    if(op==13)return db_text(sqlite3_column_name(s->query,db_column(s,b,location)),location);
    if(op==14){int at=db_column(s,b,location);if(s->state!=1)runtime_error(location,"SQLite परिणाम की वर्तमान पंक्ति नहीं है।");return s->types[at];}
    if(op==15)return (uint64_t)sqlite3_column_int64(s->query,db_cell(s,b,SQLITE_INTEGER,location));
    if(op==16){double value=sqlite3_column_double(s->query,db_cell(s,b,SQLITE_FLOAT,location));if(!isfinite(value))runtime_error(location,"SQLite दशमलव सीमित नहीं है।");uint64_t bits;memcpy(&bits,&value,8);return bits;}
    if(op==17){
        int at=db_cell(s,b,(int)c,location);int length=sqlite3_column_bytes(s->query,at);
        bh_collection *buffer=bh_collection_new(info,length,location);
        const void *bytes=sqlite3_column_blob(s->query,at);if(length)memcpy(buffer->data,bytes,(size_t)length);return pointer_bits(buffer);
    }
    if(op==20)return (uint64_t)sqlite3_bind_parameter_count(s->query);
    runtime_error(location,"अमान्य SQLite क्रिया।");
}
