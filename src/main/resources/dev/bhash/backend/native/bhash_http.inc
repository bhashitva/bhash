/* SPDX-License-Identifier: Apache-2.0
 * One-hop HTTP transport. Included only for programs using network intrinsics.
 * Worker callbacks never allocate, retain, or inspect Bhash heap references. */
#define NET_BODY_LIMIT 8388608u
#define NET_HEADER_LIMIT 65536u
enum { NET_ERROR=1, NET_TIMEOUT, NET_TLS, NET_BODY_SIZE, NET_HEADER_SIZE, NET_UNAVAILABLE };
typedef struct net_response {
    uint64_t id;
    long status;
    unsigned char *body;
    size_t size,capacity,header_size,field_count;
    char *fields[512];
    int error,headers_done;
} net_response;
static net_response *net_responses[32];
static uint64_t net_next;
static uint64_t net_clock(void);
static void net_fields_clear(net_response *r){
    for(size_t i=0;i<r->field_count;i++)free(r->fields[i]);
    r->field_count=0;r->header_size=0;r->headers_done=0;
}
static void net_dispose(net_response *r){if(r){net_fields_clear(r);free(r->body);free(r);}}
static const char *net_message(int error){
    switch(error){
        case NET_TIMEOUT:return "HTTP अनुरोध का समय समाप्त।";
        case NET_TLS:return "TLS संबंध स्थापित नहीं हो सका।";
        case NET_BODY_SIZE:return "HTTP उत्तर की सीमा ८३८८६०८ बाइट है।";
        case NET_HEADER_SIZE:return "HTTP शीर्षों की सीमा पार हो गई।";
        case NET_UNAVAILABLE:return "HTTP के लिए TLS और अतुल्यकालिक DNS सहित libcurl 7.68+ चाहिए।";
        default:return "HTTP अनुरोध असफल।";
    }
}
static char *net_string(const void *value,const void *location){
    bh_instance((void *)value,location);uint64_t n=text_length(value);
    if(n>8192||memchr(text_data(value),0,(size_t)n))runtime_error(location,"अमान्य HTTP पाठ।");
    char *copy=malloc((size_t)n+1);if(!copy)runtime_error(location,"HTTP अनुरोध असफल।");
    memcpy(copy,text_data(value),(size_t)n);copy[n]=0;return copy;
}
static size_t net_body_append(net_response *r,const void *data,size_t n){
    if(n>NET_BODY_LIMIT-r->size){r->error=NET_BODY_SIZE;return 0;}
    size_t need=r->size+n;
    if(need>r->capacity){
        size_t capacity=r->capacity?r->capacity:16384;
        while(capacity<need)capacity*=2;
        if(capacity>NET_BODY_LIMIT)capacity=NET_BODY_LIMIT;
        void *next=realloc(r->body,capacity);if(!next){r->error=NET_ERROR;return 0;}
        r->body=next;r->capacity=capacity;
    }
    if(n)memcpy(r->body+r->size,data,n);
    r->size=need;return n;
}
/* HTTP field bytes are ISO-8859-1; convert to Bhash UTF-8, retaining duplicates.
 * Ignore trailers, matching the managed response header APIs. */
static size_t net_header_append(net_response *r,const char *data,size_t n){
    if(n>=5&&!memcmp(data,"HTTP/",5)){net_fields_clear(r);return n;}
    if(n==2&&data[0]=='\r'&&data[1]=='\n'){r->headers_done=1;return n;}
    if(r->headers_done)return n;
    if(n>NET_HEADER_LIMIT-r->header_size||r->field_count>=512){r->error=NET_HEADER_SIZE;return 0;}
    r->header_size+=n;
    const char *colon=memchr(data,':',n);if(!colon||colon==data||memchr(data,0,n)){r->error=NET_ERROR;return 0;}
    size_t key_size=(size_t)(colon-data),a=key_size+1,b=n;
    while(a<b&&(data[a]==' '||data[a]=='\t'))a++;
    while(b>a&&(data[b-1]==' '||data[b-1]=='\t'||data[b-1]=='\r'||data[b-1]=='\n'))b--;
    char *key=malloc(key_size+1),*value=malloc((b-a)*2+1);
    if(!key||!value){free(key);free(value);r->error=NET_ERROR;return 0;}
    for(size_t i=0;i<key_size;i++){unsigned char c=(unsigned char)data[i];
        if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||strchr("!#$%&'*+-.^_`|~",c))){free(key);free(value);r->error=NET_ERROR;return 0;}
        key[i]=(char)(c>='A'&&c<='Z'?c+32:c);
    }
    key[key_size]=0;size_t used=0;
    for(size_t i=a;i<b;i++){unsigned char c=(unsigned char)data[i];
        if((c<32&&c!=9)||c==127){free(key);free(value);r->error=NET_ERROR;return 0;}
        if(c<128)value[used++]=(char)c;else {value[used++]=(char)(0xc0|(c>>6));value[used++]=(char)(0x80|(c&63));}
    }
    value[used]=0;r->fields[r->field_count++]=key;r->fields[r->field_count++]=value;return n;
}
static int net_request_fields(const bh_collection *headers){
    if(headers->size>132||headers->size%2)return 0;
    uint64_t total=0;
    for(uint64_t i=0;i<headers->size;i++){
        const void *value=(void *)(uintptr_t)headers->data[i];if(!value)return 0;
        uint64_t n=text_length(value);if(n>8192||(!(i&1)&&(!n||n>256)))return 0;
        const unsigned char *bytes=text_data(value);total+=n+2;
        for(uint64_t j=0;j<n;j++){
            unsigned char c=bytes[j];
            if(i&1){if(c!=9&&(c<32||c>126))return 0;}
            else if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||(c&&strchr("!#$%&'*+-.^_`|~",c))))return 0;
        }
    }
    return total<=NET_HEADER_LIMIT;
}

#ifdef _WIN32
#include <winhttp.h>
typedef struct net_windows {
    volatile LONG references,status,tls;
    DWORD error,used;
    HANDLE event;
    unsigned char buffer[16384];
    unsigned char *upload;
} net_windows;
static void net_windows_release(net_windows *c){if(InterlockedDecrement(&c->references)==0){CloseHandle(c->event);free(c->upload);free(c);}}
static void CALLBACK net_windows_status(HINTERNET handle,DWORD_PTR context,DWORD status,void *data,DWORD size){
    (void)handle;net_windows *c=(net_windows *)context;if(!c)return;
    if(status==WINHTTP_CALLBACK_STATUS_HANDLE_CLOSING){net_windows_release(c);return;}
    if(status==WINHTTP_CALLBACK_STATUS_SECURE_FAILURE){InterlockedExchange(&c->tls,1);return;}
    if(status==WINHTTP_CALLBACK_STATUS_REQUEST_ERROR){c->error=((WINHTTP_ASYNC_RESULT *)data)->dwError;}
    else if(status==WINHTTP_CALLBACK_STATUS_READ_COMPLETE)c->used=size;
    else if(status!=WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE&&status!=WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE)return;
    InterlockedExchange(&c->status,(LONG)status);SetEvent(c->event);
}
static int net_windows_error(DWORD error,int tls){
    if(error==ERROR_WINHTTP_TIMEOUT)return NET_TIMEOUT;
    if(tls||error==ERROR_WINHTTP_SECURE_FAILURE)return NET_TLS;
    if(error==ERROR_WINHTTP_HEADER_SIZE_OVERFLOW||error==ERROR_WINHTTP_HEADER_COUNT_EXCEEDED)return NET_HEADER_SIZE;
    return NET_ERROR;
}
static int net_windows_wait(net_windows *c,BOOL initiated,DWORD expected,ULONGLONG deadline){
    if(!initiated&&GetLastError()!=ERROR_IO_PENDING){
        DWORD error=GetLastError();return net_windows_error(error,(int)c->tls);
    }
    ULONGLONG now=GetTickCount64();if(now>=deadline)return NET_TIMEOUT;
    DWORD result=WaitForSingleObject(c->event,(DWORD)(deadline-now));
    if(result==WAIT_TIMEOUT)return NET_TIMEOUT;
    if(result!=WAIT_OBJECT_0)return NET_ERROR;
    if((DWORD)c->status==expected)return 0;
    return net_windows_error(c->error,(int)c->tls);
}
static wchar_t *net_wide(const char *text){
    size_t n=strlen(text);wchar_t *value=malloc((n+1)*sizeof *value);if(!value)return NULL;
    for(size_t i=0;i<=n;i++)value[i]=(unsigned char)text[i];return value;
}
static int net_transport(net_response *r,const char *method,const char *url,const bh_collection *headers,const bh_collection *body,uint64_t timeout){
    int error=NET_ERROR;HINTERNET session=NULL,connection=NULL,request=NULL;
    ULONGLONG deadline=GetTickCount64()+timeout;
    wchar_t *address=net_wide(url),*verb=net_wide(method),*host=NULL,*path=NULL,*fields=NULL,*raw=NULL;
    net_windows *c=calloc(1,sizeof *c);
    if(!c||!address||!verb)goto done;
    c->references=1;c->event=CreateEventW(NULL,TRUE,FALSE,NULL);if(!c->event)goto done;
    if(body->size){c->upload=malloc((size_t)body->size);if(!c->upload)goto done;memcpy(c->upload,body->data,(size_t)body->size);}
    URL_COMPONENTS parts;memset(&parts,0,sizeof parts);parts.dwStructSize=sizeof parts;
    parts.dwSchemeLength=(DWORD)-1;parts.dwHostNameLength=(DWORD)-1;parts.dwUrlPathLength=(DWORD)-1;parts.dwExtraInfoLength=(DWORD)-1;
    if(!WinHttpCrackUrl(address,0,0,&parts)||(parts.nScheme!=INTERNET_SCHEME_HTTP&&parts.nScheme!=INTERNET_SCHEME_HTTPS))goto done;
    host=calloc((size_t)parts.dwHostNameLength+1,sizeof *host);path=calloc((size_t)parts.dwUrlPathLength+parts.dwExtraInfoLength+2,sizeof *path);
    if(!host||!path)goto done;
    memcpy(host,parts.lpszHostName,(size_t)parts.dwHostNameLength*sizeof *host);
    if(parts.dwUrlPathLength)memcpy(path,parts.lpszUrlPath,(size_t)parts.dwUrlPathLength*sizeof *path);else path[0]=L'/';
    if(parts.dwExtraInfoLength)memcpy(path+(parts.dwUrlPathLength?parts.dwUrlPathLength:1),parts.lpszExtraInfo,(size_t)parts.dwExtraInfoLength*sizeof *path);
    session=WinHttpOpen(NULL,WINHTTP_ACCESS_TYPE_NO_PROXY,NULL,NULL,WINHTTP_FLAG_ASYNC);if(!session)goto done;
    DWORD protocols=WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
#ifdef WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3
    protocols|=WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3;
#endif
    if(!WinHttpSetOption(session,WINHTTP_OPTION_SECURE_PROTOCOLS,&protocols,sizeof protocols)){
        protocols=WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;if(!WinHttpSetOption(session,WINHTTP_OPTION_SECURE_PROTOCOLS,&protocols,sizeof protocols))goto done;
    }
    if(!WinHttpSetTimeouts(session,(int)timeout,(int)timeout,(int)timeout,(int)timeout))goto done;
    connection=WinHttpConnect(session,host,parts.nPort,0);if(!connection)goto done;
    request=WinHttpOpenRequest(connection,verb,path,L"HTTP/1.1",NULL,NULL,parts.nScheme==INTERNET_SCHEME_HTTPS?WINHTTP_FLAG_SECURE:0);if(!request)goto done;
    DWORD disabled=WINHTTP_DISABLE_COOKIES|WINHTTP_DISABLE_REDIRECTS|WINHTTP_DISABLE_AUTHENTICATION;
    DWORD max_headers=NET_HEADER_LIMIT;
    if(!WinHttpSetOption(request,WINHTTP_OPTION_DISABLE_FEATURE,&disabled,sizeof disabled)||!WinHttpSetOption(request,WINHTTP_OPTION_MAX_RESPONSE_HEADER_SIZE,&max_headers,sizeof max_headers))goto done;
    if(WinHttpSetStatusCallback(request,net_windows_status,WINHTTP_CALLBACK_FLAG_ALL_COMPLETIONS|WINHTTP_CALLBACK_FLAG_HANDLES|WINHTTP_CALLBACK_FLAG_SECURE_FAILURE,0)==WINHTTP_INVALID_STATUS_CALLBACK)goto done;
    DWORD_PTR context=(DWORD_PTR)c;
    if(!WinHttpSetOption(request,WINHTTP_OPTION_CONTEXT_VALUE,&context,sizeof context))goto done;
    InterlockedIncrement(&c->references);
    size_t field_size=0;for(uint64_t i=0;i<headers->size;i++)field_size+=(size_t)text_length((void *)(uintptr_t)headers->data[i])+2;
    fields=calloc(field_size+1,sizeof *fields);if(!fields)goto done;
    size_t at=0;
    for(uint64_t i=0;i<headers->size;i+=2){
        for(uint64_t j=0;j<2;j++){
            const void *value=(void *)(uintptr_t)headers->data[i+j];for(uint64_t k=0;k<text_length(value);k++)fields[at++]=text_data(value)[k];
            if(!j){fields[at++]=L':';fields[at++]=L' ';}else {fields[at++]=L'\r';fields[at++]=L'\n';}
        }
    }
    if(at&&!WinHttpAddRequestHeaders(request,fields,(DWORD)at,WINHTTP_ADDREQ_FLAG_ADD))goto done;
    error=net_windows_wait(c,WinHttpSendRequest(request,NULL,0,c->upload,(DWORD)body->size,(DWORD)body->size,(DWORD_PTR)c),WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE,deadline);if(error)goto done;
    ResetEvent(c->event);error=net_windows_wait(c,WinHttpReceiveResponse(request,NULL),WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE,deadline);if(error)goto done;
    DWORD status=0,bytes=sizeof status;
    error=NET_ERROR;
    if(!WinHttpQueryHeaders(request,WINHTTP_QUERY_STATUS_CODE|WINHTTP_QUERY_FLAG_NUMBER,NULL,&status,&bytes,NULL))goto done;
    r->status=(long)status;bytes=0;
    WinHttpQueryHeaders(request,WINHTTP_QUERY_RAW_HEADERS_CRLF,NULL,NULL,&bytes,NULL);
    if(GetLastError()!=ERROR_INSUFFICIENT_BUFFER)goto done;
    if(bytes>NET_HEADER_LIMIT*2+1024){error=NET_HEADER_SIZE;goto done;}
    raw=malloc(bytes);if(!raw)goto done;
    if(!WinHttpQueryHeaders(request,WINHTTP_QUERY_RAW_HEADERS_CRLF,NULL,raw,&bytes,NULL))goto done;
    size_t count=wcslen(raw);char *latin=malloc(count+1);if(!latin)goto done;
    for(size_t i=0;i<count;i++){if(raw[i]>255){free(latin);goto done;}latin[i]=(char)raw[i];}latin[count]=0;
    char *line=latin,*end;
    while((end=strstr(line,"\r\n"))!=NULL){size_t n=(size_t)(end-line)+2;if(!net_header_append(r,line,n)){free(latin);error=r->error;goto done;}line=end+2;}
    free(latin);error=0;
    for(;;){
        ResetEvent(c->event);error=net_windows_wait(c,WinHttpReadData(request,c->buffer,(DWORD)sizeof c->buffer,NULL),WINHTTP_CALLBACK_STATUS_READ_COMPLETE,deadline);
        if(error)break;if(!c->used)break;
        if(!net_body_append(r,c->buffer,c->used)){error=r->error;break;}
    }
done:
    if(request)WinHttpCloseHandle(request);
    if(connection)WinHttpCloseHandle(connection);
    if(session)WinHttpCloseHandle(session);
    if(c){if(c->event)net_windows_release(c);else {free(c->upload);free(c);}}
    free(address);free(verb);free(host);free(path);free(fields);free(raw);return error;
}
static uint64_t net_clock(void){return GetTickCount64();}
#else
#include <dlfcn.h>
/* Stable libcurl ABI declarations, not vendored implementation code. Option
 * numbers/types follow https://curl.se/libcurl/c/curl_easy_setopt.html. */
enum {
    BH_CURL_URL=10002, BH_CURL_PROXY=10004, BH_CURL_PROTOCOLS=181, BH_CURL_REDIR_PROTOCOLS=182,
    BH_CURL_FOLLOWLOCATION=52, BH_CURL_NOSIGNAL=99, BH_CURL_TIMEOUT_MS=155, BH_CURL_CONNECTTIMEOUT_MS=156,
    BH_CURL_SSL_VERIFYPEER=64, BH_CURL_SSL_VERIFYHOST=81, BH_CURL_SSLVERSION=32, BH_CURL_HTTP_VERSION=84,
    BH_CURL_CAINFO=10065, BH_CURL_WRITEFUNCTION=20011, BH_CURL_WRITEDATA=10001,
    BH_CURL_HEADERFUNCTION=20079, BH_CURL_HEADERDATA=10029, BH_CURL_HTTPHEADER=10023,
    BH_CURL_POSTFIELDS=10015, BH_CURL_POSTFIELDSIZE=60, BH_CURL_NOBODY=44, BH_CURL_CUSTOMREQUEST=10036,
    BH_CURL_HTTP_CONTENT_DECODING=158, BH_CURL_RESPONSE_CODE=0x200002
};
typedef struct net_curl_version {
    int age;const char *version;unsigned version_num;const char *host;int features;
} net_curl_version;
static void *net_curl_library;
static void (*net_curl_global_free)(void);
static void net_curl_shutdown(void){net_curl_global_free();}
static void *(*net_curl_init)(void);
static int (*net_curl_setopt)(void *,int,...);
static int (*net_curl_perform)(void *);
static int (*net_curl_getinfo)(void *,int,...);
static void (*net_curl_cleanup)(void *);
static void *(*net_curl_slist_append)(void *,const char *);
static void (*net_curl_slist_free)(void *);
static int net_curl_ready(void){
    if(net_curl_library)return 1;
#ifdef __APPLE__
    void *library=dlopen("/usr/lib/libcurl.4.dylib",RTLD_NOW|RTLD_LOCAL);
#else
    void *library=dlopen("libcurl.so.4",RTLD_NOW|RTLD_LOCAL);
#endif
    if(!library)return 0;
    int (*initialize)(long)=(int (*)(long))dlsym(library,"curl_global_init");
    net_curl_global_free=(void (*)(void))dlsym(library,"curl_global_cleanup");
    net_curl_version *(*version)(int)=(net_curl_version *(*)(int))dlsym(library,"curl_version_info");
    net_curl_init=(void *(*)(void))dlsym(library,"curl_easy_init");
    net_curl_setopt=(int (*)(void *,int,...))dlsym(library,"curl_easy_setopt");
    net_curl_perform=(int (*)(void *))dlsym(library,"curl_easy_perform");
    net_curl_getinfo=(int (*)(void *,int,...))dlsym(library,"curl_easy_getinfo");
    net_curl_cleanup=(void (*)(void *))dlsym(library,"curl_easy_cleanup");
    net_curl_slist_append=(void *(*)(void *,const char *))dlsym(library,"curl_slist_append");
    net_curl_slist_free=(void (*)(void *))dlsym(library,"curl_slist_free_all");
    if(!initialize||!net_curl_global_free||!version||!net_curl_init||!net_curl_setopt||!net_curl_perform||!net_curl_getinfo||!net_curl_cleanup||!net_curl_slist_append||!net_curl_slist_free){dlclose(library);return 0;}
    net_curl_version *v=version(0);
    if(!v||v->version_num<0x074400||!(v->features&4)||!(v->features&128)||initialize(3)){dlclose(library);return 0;}
    if(atexit(net_curl_shutdown)){net_curl_global_free();dlclose(library);return 0;}
    net_curl_library=library;return 1;
}
static size_t net_curl_body(char *data,size_t size,size_t count,void *context){
    net_response *r=context;if(size&&count>SIZE_MAX/size){r->error=NET_BODY_SIZE;return 0;}return net_body_append(r,data,size*count);
}
static size_t net_curl_header(char *data,size_t size,size_t count,void *context){
    net_response *r=context;if(size&&count>SIZE_MAX/size){r->error=NET_HEADER_SIZE;return 0;}return net_header_append(r,data,size*count);
}
static int net_transport(net_response *r,const char *method,const char *url,const bh_collection *headers,const bh_collection *body,uint64_t timeout){
    uint64_t deadline=net_clock()+timeout;
    if(!net_curl_ready())return NET_UNAVAILABLE;
    void *curl=net_curl_init(),*fields=NULL;char *line=NULL;unsigned char *upload=NULL;int error=NET_ERROR;
    if(!curl)return NET_ERROR;
    if(body->size){upload=malloc((size_t)body->size);if(!upload){net_curl_cleanup(curl);return NET_ERROR;}memcpy(upload,body->data,(size_t)body->size);}
#define NET_OPT(option,value) do { if(net_curl_setopt(curl,(option),(value)))goto done; } while(0)
    NET_OPT(BH_CURL_URL,url);NET_OPT(BH_CURL_PROXY,"");NET_OPT(BH_CURL_PROTOCOLS,3L);NET_OPT(BH_CURL_REDIR_PROTOCOLS,3L);
    NET_OPT(BH_CURL_FOLLOWLOCATION,0L);NET_OPT(BH_CURL_NOSIGNAL,1L);NET_OPT(BH_CURL_TIMEOUT_MS,(long)timeout);NET_OPT(BH_CURL_CONNECTTIMEOUT_MS,(long)timeout);
    NET_OPT(BH_CURL_HTTP_CONTENT_DECODING,0L);
    NET_OPT(BH_CURL_SSL_VERIFYPEER,1L);NET_OPT(BH_CURL_SSL_VERIFYHOST,2L);NET_OPT(BH_CURL_SSLVERSION,6L);NET_OPT(BH_CURL_HTTP_VERSION,2L);
    const char *ca=getenv("SSL_CERT_FILE");if(ca&&*ca)NET_OPT(BH_CURL_CAINFO,ca);
    NET_OPT(BH_CURL_WRITEFUNCTION,net_curl_body);NET_OPT(BH_CURL_WRITEDATA,r);NET_OPT(BH_CURL_HEADERFUNCTION,net_curl_header);NET_OPT(BH_CURL_HEADERDATA,r);
    fields=net_curl_slist_append(NULL,"Expect:");if(!fields)goto done;
    int content_type=0;
    for(uint64_t i=0;i<headers->size;i+=2){
        const void *key=(void *)(uintptr_t)headers->data[i],*value=(void *)(uintptr_t)headers->data[i+1];
        size_t a=(size_t)text_length(key),b=(size_t)text_length(value);
        line=malloc(a+b+3);if(!line)goto done;memcpy(line,text_data(key),a);if(b){line[a]=':';line[a+1]=' ';memcpy(line+a+2,text_data(value),b);line[a+b+2]=0;}else {line[a]=';';line[a+1]=0;}
        if(a==12){const char *wanted="content-type";int matches=1;for(size_t j=0;j<a;j++){unsigned char c=(unsigned char)line[j];if((c>='A'&&c<='Z'?c+32:c)!=(unsigned char)wanted[j])matches=0;}if(matches)content_type=1;}
        void *next=net_curl_slist_append(fields,line);free(line);line=NULL;if(!next)goto done;fields=next;
    }
    if(!content_type){void *next=net_curl_slist_append(fields,"Content-Type:");if(!next)goto done;fields=next;}
    NET_OPT(BH_CURL_HTTPHEADER,fields);
    if(body->size||(!strcmp(method,"POST")||!strcmp(method,"PUT")||!strcmp(method,"PATCH"))){NET_OPT(BH_CURL_POSTFIELDS,body->size?(const void *)upload:(const void *)"");NET_OPT(BH_CURL_POSTFIELDSIZE,(long)body->size);}
    if(!strcmp(method,"HEAD"))NET_OPT(BH_CURL_NOBODY,1L);
    NET_OPT(BH_CURL_CUSTOMREQUEST,method);
    uint64_t now=net_clock();if(now>=deadline){error=NET_TIMEOUT;goto done;}
    NET_OPT(BH_CURL_TIMEOUT_MS,(long)(deadline-now));NET_OPT(BH_CURL_CONNECTTIMEOUT_MS,(long)(deadline-now));
    int result=net_curl_perform(curl);
    if(r->error)error=r->error;
    else if(result==28)error=NET_TIMEOUT;
    else if(result==35||result==60||result==77||result==83)error=NET_TLS;
    else if(!result&&!net_curl_getinfo(curl,BH_CURL_RESPONSE_CODE,&r->status))error=0;
done:
    free(line);free(upload);if(fields)net_curl_slist_free(fields);net_curl_cleanup(curl);return error;
#undef NET_OPT
}
static uint64_t net_clock(void){struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now))return 0;return (uint64_t)now.tv_sec*1000+(uint64_t)now.tv_nsec/1000000;}
#endif

uint64_t bh_network_call(uint32_t op,uint64_t a,uint64_t b,uint64_t c,uint64_t d,uint64_t e,const bh_collection_info *info,const void *location){
    if(op==5)return net_clock();
    if(op==0){
        size_t slot=0;while(slot<32&&net_responses[slot])slot++;
        if(slot==32)runtime_error(location,"बहुत अधिक खुले HTTP उत्तर।");
        const void *verb=(void *)(uintptr_t)a,*address=(void *)(uintptr_t)b;
        bh_collection *headers=bh_instance((void *)(uintptr_t)c,location),*body=bh_instance((void *)(uintptr_t)d,location);
        if(e<1||e>120000)runtime_error(location,"HTTP समयसीमा १ से १२०००० मिलीसेकंड होनी चाहिए।");
        if(body->size>NET_BODY_LIMIT)runtime_error(location,"अमान्य HTTP सामग्री।");
        if(!net_request_fields(headers))runtime_error(location,"अमान्य HTTP शीर्ष।");
        char *method=net_string(verb,location);
        bh_cleanup method_cleanup={cleanups,method,free};cleanups=&method_cleanup;
        char *url=net_string(address,location);
        cleanups=method_cleanup.previous;
        if(strncmp(url,"http://",7)&&strncmp(url,"https://",8)){free(method);free(url);runtime_error(location,"अमान्य HTTP पता।");}
        net_response *r=calloc(1,sizeof *r);if(!r){free(method);free(url);runtime_error(location,"HTTP अनुरोध असफल।");}
        int error=net_transport(r,method,url,headers,body,e);free(method);free(url);
        if(error){net_dispose(r);runtime_error(location,net_message(error));}
        r->id=++net_next;net_responses[slot]=r;return r->id;
    }
    size_t slot=0;while(slot<32&&(!net_responses[slot]||net_responses[slot]->id!=a))slot++;
    if(op==4){
        if(!a||a>net_next)runtime_error(location,"अमान्य HTTP उत्तर पहचान।");
        if(slot<32){net_dispose(net_responses[slot]);net_responses[slot]=NULL;}return 0;
    }
    if(slot==32)runtime_error(location,"HTTP उत्तर बंद है।");
    net_response *r=net_responses[slot];
    if(op==1)return (uint64_t)r->status;
    if(op==3){bh_collection *buffer=bh_collection_new(info,(int64_t)r->size,location);if(r->size)memcpy(buffer->data,r->body,r->size);return (uint64_t)(uintptr_t)buffer;}
    if(op==2){
        void *slots[2]={NULL,NULL};bh_root_frame frame;bh_roots_push(&frame,slots,2);slots[0]=bh_collection_new(info,0,location);
        for(size_t i=0;i<r->field_count;i++){
            size_t n=strlen(r->fields[i]);slots[1]=new_text((uint64_t)n,location);memcpy(text_data(slots[1]),r->fields[i],n);
            (void)bh_collection_call(slots[0],BH_ADD,(uint64_t)(uintptr_t)slots[1],0,NULL,location);slots[1]=NULL;
        }
        uint64_t result=(uint64_t)(uintptr_t)slots[0];bh_roots_pop(&frame);return result;
    }
    runtime_error(location,"अमान्य HTTP क्रिया।");return 0;
}
