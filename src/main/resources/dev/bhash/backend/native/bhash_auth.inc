/* SPDX-License-Identifier: Apache-2.0
 * Cryptographic primitives are delegated to maintained platform providers.
 * No provider retains a Bhash reference or calls the collector. The caller roots
 * all arguments; only the final text allocation may collect. */
#define AUTH_PREFIX "$bhash$pbkdf2-sha256$600000$"
static void auth_wipe(void *p,size_t n){volatile unsigned char *v=p;while(n--)*v++=0;}
static void auth_hex(char *out,const unsigned char *bytes,size_t n){
    const char *digits="0123456789abcdef";
    for(size_t i=0;i<n;i++){out[i*2]=digits[bytes[i]>>4];out[i*2+1]=digits[bytes[i]&15];}
}
static int auth_unhex(unsigned char *out,const unsigned char *in,size_t n){
    for(size_t i=0;i<n;i++){
        unsigned a=in[i*2],b=in[i*2+1];
        if(!((a>='0'&&a<='9')||(a>='a'&&a<='f'))||!((b>='0'&&b<='9')||(b>='a'&&b<='f')))return 0;
        out[i]=(unsigned char)(((a<='9'?a-'0':a-'a'+10)<<4)|(b<='9'?b-'0':b-'a'+10));
    }return 1;
}
static int auth_equal(const unsigned char *a,const unsigned char *b,size_t n){
    volatile unsigned diff=0;for(size_t i=0;i<n;i++)diff|=(unsigned)(a[i]^b[i]);return diff==0;
}
#ifdef _WIN32
#include <bcrypt.h>
static int auth_random(unsigned char *bytes,size_t n){return BCryptGenRandom(NULL,bytes,(ULONG)n,BCRYPT_USE_SYSTEM_PREFERRED_RNG)>=0;}
static int auth_derive(const unsigned char *password,size_t n,const unsigned char *salt,unsigned char *out){
    BCRYPT_ALG_HANDLE algorithm=NULL;
    if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,NULL,BCRYPT_ALG_HANDLE_HMAC_FLAG)<0)return 0;
    NTSTATUS status=BCryptDeriveKeyPBKDF2(algorithm,(PUCHAR)password,(ULONG)n,(PUCHAR)salt,16,600000,out,32,0);
    (void)BCryptCloseAlgorithmProvider(algorithm,0);return status>=0;
}
#elif defined(__APPLE__)
#include <CommonCrypto/CommonCryptor.h>
#include <CommonCrypto/CommonKeyDerivation.h>
#include <CommonCrypto/CommonRandom.h>
static int auth_random(unsigned char *bytes,size_t n){return CCRandomGenerateBytes(bytes,n)==kCCSuccess;}
static int auth_derive(const unsigned char *password,size_t n,const unsigned char *salt,unsigned char *out){
    return CCKeyDerivationPBKDF(kCCPBKDF2,(const char *)password,n,salt,16,kCCPRFHmacAlgSHA256,600000,out,32)==kCCSuccess;
}
#else
#include <sys/random.h>
#include <dlfcn.h>
static int (*auth_pbkdf)(const char *,int,const unsigned char *,int,int,const void *,int,unsigned char *);
static const void *(*auth_sha256)(void);
static void *auth_library;
static int auth_random(unsigned char *bytes,size_t n){
    size_t used=0;while(used<n){ssize_t r=getrandom(bytes+used,n-used,0);if(r<0&&errno==EINTR)continue;if(r<=0)return 0;used+=(size_t)r;}return 1;
}
static int auth_derive(const unsigned char *password,size_t n,const unsigned char *salt,unsigned char *out){
    if(!auth_library){
        void *library=dlopen("libcrypto.so.3",RTLD_NOW|RTLD_LOCAL);if(!library)return 0;
        /* POSIX defines dlsym function addresses; memcpy avoids ISO C function
         * pointer casts and keeps strict standalone runtime builds warning-free. */
        void *pb=dlsym(library,"PKCS5_PBKDF2_HMAC"),*sha=dlsym(library,"EVP_sha256");
        if(!pb||!sha){dlclose(library);return 0;}
        _Static_assert(sizeof(auth_pbkdf)==sizeof(pb),"POSIX function pointer size");
        memcpy(&auth_pbkdf,&pb,sizeof(pb));memcpy(&auth_sha256,&sha,sizeof(sha));auth_library=library;
    }
    return auth_pbkdf((const char *)password,(int)n,salt,16,600000,auth_sha256(),32,out)==1;
}
#endif
static uint64_t auth_call(uint32_t op,uint64_t a,uint64_t b,const void *location){
    if(op==18){
#ifdef _WIN32
        return GetTickCount64();
#else
        struct timespec time;if(clock_gettime(CLOCK_MONOTONIC,&time))runtime_error(location,"सुरक्षा प्रदाता उपलब्ध नहीं है।");
        return (uint64_t)time.tv_sec*1000+(uint64_t)time.tv_nsec/1000000;
#endif
    }
    if(op==16){
        unsigned char random[32];char hex[64];int ok=auth_random(random,sizeof(random));
        if(ok){auth_hex(hex,random,sizeof(random));}auth_wipe(random,sizeof(random));
        if(!ok)runtime_error(location,"सुरक्षा प्रदाता उपलब्ध नहीं है।");
        void *result=new_text(sizeof(hex),location);memcpy(text_data(result),hex,sizeof(hex));auth_wipe(hex,sizeof(hex));return (uint64_t)(uintptr_t)result;
    }
    void *first=(void *)(uintptr_t)a;bh_instance(first,location);uint64_t n=text_length(first);
    if(op==17){
        void *second=(void *)(uintptr_t)b;bh_instance(second,location);uint64_t m=text_length(second);
        if(n>8192||m>8192)runtime_error(location,"सुरक्षा तुलना की सीमा ८१९२ UTF-8 बाइट है।");
        return n==m&&auth_equal(text_data(first),text_data(second),(size_t)n);
    }
    if(n<1||n>1024)runtime_error(location,"कूटशब्द १ से १०२४ UTF-8 बाइट होना चाहिए।");
    unsigned char salt[16]={0},derived[32]={0},expected[32]={0};
    if(op==15){
        void *encoded=(void *)(uintptr_t)b;bh_instance(encoded,location);const unsigned char *hash=text_data(encoded);size_t p=sizeof(AUTH_PREFIX)-1;
        if(text_length(encoded)!=p+97||memcmp(hash,AUTH_PREFIX,p)||hash[p+32]!='$'||!auth_unhex(salt,hash+p,16)||!auth_unhex(expected,hash+p+33,32))return 0;
    }else if(op!=14)runtime_error(location,"सुरक्षा प्रदाता उपलब्ध नहीं है।");
    int ok=op==15||auth_random(salt,sizeof(salt));
    if(ok)ok=auth_derive(text_data(first),(size_t)n,salt,derived);
    int equal=auth_equal(derived,expected,sizeof(derived));
    char encoded[sizeof(AUTH_PREFIX)-1+97];
    if(ok&&op==14){size_t p=sizeof(AUTH_PREFIX)-1;memcpy(encoded,AUTH_PREFIX,p);auth_hex(encoded+p,salt,16);encoded[p+32]='$';auth_hex(encoded+p+33,derived,32);}
    auth_wipe(salt,sizeof(salt));auth_wipe(derived,sizeof(derived));auth_wipe(expected,sizeof(expected));
    if(!ok)runtime_error(location,"सुरक्षा प्रदाता उपलब्ध नहीं है।");
    if(op==15)return (uint64_t)equal;
    void *result=new_text(sizeof(encoded),location);memcpy(text_data(result),encoded,sizeof(encoded));auth_wipe(encoded,sizeof(encoded));return (uint64_t)(uintptr_t)result;
}
