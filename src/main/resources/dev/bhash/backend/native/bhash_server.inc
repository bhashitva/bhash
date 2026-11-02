/* SPDX-License-Identifier: Apache-2.0
 * Bounded synchronous transport: no threads, callbacks, or retained Bhash heap pointers.
 * HTTP grammar and application dispatch live in web.bhash on the mutator thread. */
#ifndef _WIN32
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/select.h>
typedef int srv_socket;
#define SRV_INVALID (-1)
#define srv_close_socket close
#else
typedef SOCKET srv_socket;
#define SRV_INVALID INVALID_SOCKET
#define srv_close_socket closesocket
#endif
typedef struct {uint64_t id,deadline;srv_socket socket;int listener;} srv_state;
static srv_state srv_states[32];
static uint64_t srv_next;
#ifdef _WIN32
static volatile LONG srv_stopping;
static BOOL WINAPI srv_signal(DWORD code){if(code==CTRL_C_EVENT||code==CTRL_BREAK_EVENT){InterlockedExchange(&srv_stopping,1);return TRUE;}return FALSE;}
#else
#include <signal.h>
static volatile sig_atomic_t srv_stopping;
static struct sigaction srv_old_term,srv_old_int;
static void srv_signal(int code){(void)code;srv_stopping=1;}
#endif
static int srv_hooked;
static void srv_hooks(void){if(srv_hooked)return;srv_stopping=0;
#ifdef _WIN32
    SetConsoleCtrlHandler(srv_signal,TRUE);
#else
    struct sigaction action;memset(&action,0,sizeof action);action.sa_handler=srv_signal;sigemptyset(&action.sa_mask);
    sigaction(SIGTERM,&action,&srv_old_term);sigaction(SIGINT,&action,&srv_old_int);
#endif
    srv_hooked=1;
}
static void srv_unhook(void){
    for(size_t i=0;i<32;i++)if(srv_states[i].id)return;
    if(!srv_hooked)return;
#ifdef _WIN32
    SetConsoleCtrlHandler(srv_signal,FALSE);
#else
    sigaction(SIGTERM,&srv_old_term,NULL);sigaction(SIGINT,&srv_old_int,NULL);
#endif
    srv_hooked=0;
}
static uint64_t srv_clock(void){
#ifdef _WIN32
    return GetTickCount64();
#else
    struct timespec now;if(clock_gettime(CLOCK_MONOTONIC,&now))return 0;
    return (uint64_t)now.tv_sec*1000+(uint64_t)now.tv_nsec/1000000;
#endif
}
static int srv_nonblocking(srv_socket socket){
#ifdef _WIN32
    u_long value=1;return ioctlsocket(socket,FIONBIO,&value)==0;
#else
    int flags=fcntl(socket,F_GETFL,0);if(flags<0||fcntl(socket,F_SETFL,flags|O_NONBLOCK))return 0;
#ifdef SO_NOSIGPIPE
    int yes=1;if(setsockopt(socket,SOL_SOCKET,SO_NOSIGPIPE,&yes,sizeof yes))return 0;
#endif
    return 1;
#endif
}
static int srv_wait(srv_socket socket,int write,uint64_t deadline){
    for(;;){uint64_t now=srv_clock();if(now>=deadline)return 0;
        struct timeval t;t.tv_sec=(long)((deadline-now)/1000);t.tv_usec=(long)((deadline-now)%1000)*1000;
        fd_set set;FD_ZERO(&set);FD_SET(socket,&set);
        int result=select((int)socket+1,write?NULL:&set,write?&set:NULL,NULL,&t);
#ifndef _WIN32
        if(result<0&&errno==EINTR)continue;
#endif
        return result;
    }
}
static srv_state *srv_find(uint64_t id,const void *location){
    for(size_t i=0;i<32;i++)if(srv_states[i].id==id&&id)return &srv_states[i];
    runtime_error(location,"HTTP संबंध बंद है।");return NULL;
}
static srv_state *srv_slot(const void *location){
    for(size_t i=0;i<32;i++)if(!srv_states[i].id)return &srv_states[i];
    runtime_error(location,"बहुत अधिक HTTP संबंध।");return NULL;
}
static void srv_ready(srv_state *state,int write,const void *location){
    int result=srv_wait(state->socket,write,state->deadline);
    if(result==0)runtime_error(location,"HTTP संबंध का समय समाप्त।");
    if(result<0)runtime_error(location,"HTTP संबंध असफल।");
}
uint64_t bh_server_call(uint32_t op,uint64_t a,uint64_t b,const bh_collection_info *info,const void *location){
    if(op==6)return (uint64_t)srv_stopping;
    if(op==0){
        const void *address=bh_instance((void *)(uintptr_t)a,location);
        uint64_t n=text_length(address);
        int local=n==9&&!memcmp(text_data(address),"127.0.0.1",9),all=n==7&&!memcmp(text_data(address),"0.0.0.0",7);
        if((!local&&!all)||b>65535)runtime_error(location,"अमान्य HTTP सुनने का पता या पोर्ट।");
        srv_state *state=srv_slot(location);
#ifdef _WIN32
        static int initialized;if(!initialized){WSADATA data;if(WSAStartup(MAKEWORD(2,2),&data))runtime_error(location,"HTTP सर्वर खोला नहीं जा सका।");initialized=1;}
#endif
        srv_socket socket_value=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
        if(socket_value==SRV_INVALID)runtime_error(location,"HTTP सर्वर खोला नहीं जा सका।");
#ifndef _WIN32
        if(socket_value>=FD_SETSIZE){srv_close_socket(socket_value);runtime_error(location,"HTTP सर्वर खोला नहीं जा सका।");}
#endif
        /* INADDR_LOOPBACK is a BSD extension hidden by Darwin's POSIX headers.
         * The public API accepts exactly 127.0.0.1 or 0.0.0.0, so use its IPv4 value. */
        struct sockaddr_in addr;memset(&addr,0,sizeof addr);addr.sin_family=AF_INET;addr.sin_port=htons((uint16_t)b);addr.sin_addr.s_addr=htonl(local?UINT32_C(0x7f000001):INADDR_ANY);
        if(bind(socket_value,(struct sockaddr *)&addr,sizeof addr)||listen(socket_value,16)){srv_close_socket(socket_value);runtime_error(location,"HTTP सर्वर खोला नहीं जा सका।");}
        if(!srv_nonblocking(socket_value)){srv_close_socket(socket_value);runtime_error(location,"HTTP सर्वर खोला नहीं जा सका।");}srv_hooks();*state=(srv_state){++srv_next,0,socket_value,1};return state->id;
    }
    if(op==4){
        if(!a||a>srv_next)runtime_error(location,"अमान्य HTTP संबंध पहचान।");
        for(size_t i=0;i<32;i++)if(srv_states[i].id==a){srv_close_socket(srv_states[i].socket);memset(&srv_states[i],0,sizeof srv_states[i]);}
        srv_unhook();return 0;
    }
    srv_state *state=srv_find(a,location);
    if(op==1){
        if(!state->listener)runtime_error(location,"अमान्य HTTP सर्वर।");
        srv_state *child=srv_slot(location);int result=srv_wait(state->socket,0,srv_clock()+250);
        if(!result)return 0;if(result<0)runtime_error(location,"HTTP संबंध असफल।");
        srv_socket socket_value=accept(state->socket,NULL,NULL);
        if(socket_value==SRV_INVALID)runtime_error(location,"HTTP संबंध असफल।");
#ifndef _WIN32
        if(socket_value>=FD_SETSIZE){srv_close_socket(socket_value);runtime_error(location,"HTTP संबंध असफल।");}
#endif
        if(!srv_nonblocking(socket_value)){srv_close_socket(socket_value);runtime_error(location,"HTTP संबंध असफल।");}*child=(srv_state){++srv_next,srv_clock()+10000,socket_value,0};return child->id;
    }
    if(op==5){
        if(!state->listener)runtime_error(location,"अमान्य HTTP सर्वर।");
        struct sockaddr_in addr;
#ifdef _WIN32
        int length=sizeof addr;
#else
        socklen_t length=sizeof addr;
#endif
        if(getsockname(state->socket,(struct sockaddr *)&addr,&length))runtime_error(location,"HTTP संबंध असफल।");return ntohs(addr.sin_port);
    }
    if(state->listener)runtime_error(location,"अमान्य HTTP संबंध।");
    if(op==2){
        unsigned char bytes[8192];srv_ready(state,0,location);int n=(int)recv(state->socket,(char *)bytes,sizeof bytes,0);
        if(n<0)runtime_error(location,"HTTP संबंध असफल।");
        bh_collection *buffer=bh_collection_new(info,(int64_t)n,location);if(n)memcpy(buffer->data,bytes,(size_t)n);return (uint64_t)(uintptr_t)buffer;
    }
    if(op==3){
        bh_collection *body=bh_instance((void *)(uintptr_t)b,location);
        if(body->size>1114112)runtime_error(location,"अमान्य HTTP संबंध या उत्तर।");
        size_t at=0;while(at<body->size){srv_ready(state,1,location);
#ifdef MSG_NOSIGNAL
            int flags=MSG_NOSIGNAL;
#else
            int flags=0;
#endif
            int n=(int)send(state->socket,(const char *)body->data+at,(int)(body->size-at),flags);
            if(n<=0)runtime_error(location,"HTTP संबंध असफल।");at+=(size_t)n;
        }return 0;
    }
    runtime_error(location,"अमान्य HTTP क्रिया।");return 0;
}
