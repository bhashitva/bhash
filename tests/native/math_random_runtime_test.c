/* SPDX-License-Identifier: Apache-2.0 */
#include "bhash_runtime.h"
#include <assert.h>
#include <float.h>
#include <limits.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#define TEXT(name, value) static const struct { uint64_t n; char data[sizeof(value)]; } name = {sizeof(value)-1, value}
TEXT(location,"math-random-test:1:1"); TEXT(message,"जड़😀");
static const size_t refs[] = {8,16};
static const bh_type exception_type = {"exception",24,2,refs,0,0,0};
static const int64_t ids[] = {-5,-4};
static const bh_class_info info = {NULL,NULL,2,ids};
typedef double (*unary)(double,const void *);
typedef double (*binary)(double,double,const void *);
static const unary unaries[] = {bh_numeric_abs_real,bh_numeric_sqrt,bh_numeric_floor,bh_numeric_ceil,bh_numeric_round,bh_numeric_sin,bh_numeric_cos,bh_numeric_log,bh_numeric_exp};
static const binary binaries[] = {bh_numeric_min_real,bh_numeric_max_real,bh_numeric_pow};
static double argument;
static size_t operation;
static int side;
static int64_t bad_unary(void *unused) {
    (void)unused; void *slot=bh_text_concat(&message,&message,&location);bh_root_frame frame;bh_roots_push(&frame,&slot,1);
    (void)unaries[operation](argument,&location);bh_roots_pop(&frame);return 0;
}
static int64_t bad_binary(void *unused) {
    (void)unused;(void)binaries[operation](side?1.0:argument,side?argument:1.0,&location);return 0;
}
static int64_t bad_domain(void *unused) {
    (void)unused;
    switch(operation) {
        case 0: (void)bh_numeric_abs_int(INT64_MIN,&location);break;
        case 1: (void)bh_numeric_sqrt(-1,&location);break;
        case 2: (void)bh_numeric_log(0,&location);break;
        case 3: (void)bh_numeric_log(-1,&location);break;
        case 4: (void)bh_numeric_pow(-1,0.5,&location);break;
        case 5: (void)bh_numeric_pow(0,-1,&location);break;
        case 6: (void)bh_numeric_pow(10,1000,&location);break;
        default: (void)bh_numeric_exp(1000,&location);break;
    }
    return 0;
}
static int64_t bad_range(void *unused) {
    (void)unused;
    const int64_t lower[] = {0,2,INT64_MIN,-1,INT64_MIN};
    const int64_t upper[] = {0,1,0,INT64_MAX,INT64_MAX};
    return bh_numeric_random_int(lower[operation],upper[operation],&location);
}
static void caught(bh_protected_callback callback) {
    assert(bh_protect(callback,NULL)==-1);assert(bh_exception_take()!=NULL);
    bh_collect();assert(bh_heap_objects()==0);
}
int main(void) {
    bh_runtime_init();bh_gc_stress(1);bh_exception_configure(&exception_type,&info);
    assert(bh_numeric_abs_int(-INT64_MAX,&location)==INT64_MAX);
    assert(bh_numeric_min_int(INT64_MIN,INT64_MAX,&location)==INT64_MIN);
    assert(bh_numeric_max_int(INT64_MIN,INT64_MAX,&location)==INT64_MAX);
    assert(!signbit(bh_numeric_abs_real(-0.0,&location)));
    assert(signbit(bh_numeric_min_real(0.0,-0.0,&location)));
    assert(signbit(bh_numeric_min_real(-0.0,0.0,&location)));
    assert(!signbit(bh_numeric_max_real(0.0,-0.0,&location)));
    assert(!signbit(bh_numeric_max_real(-0.0,0.0,&location)));
    assert(signbit(bh_numeric_max_real(-0.0,-0.0,&location)));
    assert(bh_numeric_sqrt(81,&location)==9);assert(signbit(bh_numeric_sqrt(-0.0,&location)));
    assert(bh_numeric_pow(2,10,&location)==1024);assert(bh_numeric_pow(0,0,&location)==1);
    assert(signbit(bh_numeric_pow(-0.0,3,&location)));
    assert(bh_numeric_floor(-2.25,&location)==-3);assert(bh_numeric_ceil(-2.25,&location)==-2);
    assert(bh_numeric_round(2.5,&location)==2);assert(bh_numeric_round(3.5,&location)==4);
    assert(bh_numeric_round(-2.5,&location)==-2);assert(bh_numeric_round(-3.5,&location)==-4);
    assert(signbit(bh_numeric_round(-0.5,&location)));assert(signbit(bh_numeric_ceil(-0.25,&location)));
    assert(signbit(bh_numeric_sin(-0.0,&location)));assert(bh_numeric_cos(0,&location)==1);
    assert(bh_numeric_log(1,&location)==0);assert(bh_numeric_exp(0,&location)==1);
    assert(bh_numeric_exp(-1000,&location)==0);
    assert(bh_numeric_pi(&location)==0x1.921fb54442d18p1);
    assert(fabs(bh_numeric_sin(bh_numeric_pi(&location)/2,&location)-1)<=DBL_EPSILON);
    assert(fabs(bh_numeric_log(2,&location)-0.6931471805599453)<=DBL_EPSILON);
    assert(fabs(bh_numeric_exp(1,&location)-2.718281828459045)<=2*DBL_EPSILON);
    struct vector { int64_t seed, draws[5]; uint64_t fraction; } vectors[] = {
        {0,{-3,INT64_C(3980143261097177850),-INT64_C(8979563527119002969),INT64_C(8954805688390271223),17},UINT64_C(2948288379523028)},
        {-1,{-2,INT64_C(2024363799162208500),-INT64_C(5292053134698036887),INT64_C(6507740593731417304),17},UINT64_C(8490315493163271)},
        {INT64_MIN,{7,INT64_C(3518229400716132512),-INT64_C(6010314004554994820),INT64_C(451557293221495402),17},UINT64_C(4701183890302185)},
        {INT64_MAX,{9,INT64_C(1157452369933151741),-INT64_C(6228279828770849947),INT64_C(1038435844542656650),17},UINT64_C(4927897165718764)}
    };
    const int64_t lower[]={-10,0,INT64_MIN,1,17},upper[]={10,INT64_C(4611686018427387905),-1,INT64_MAX,18};
    for(size_t v=0;v<sizeof vectors/sizeof *vectors;++v) {
        bh_numeric_seed(vectors[v].seed,&location);
        for(size_t i=0;i<5;++i)assert(bh_numeric_random_int(lower[i],upper[i],&location)==vectors[v].draws[i]);
        double unit=bh_numeric_random_unit(&location);assert(unit==(double)vectors[v].fraction*0x1p-53);
        for(int i=0;i<10000;++i) {
            int64_t x=bh_numeric_random_int(INT64_MIN,-1,&location);assert(x>=INT64_MIN&&x<-1);
            unit=bh_numeric_random_unit(&location);assert(unit>=0&&unit<1);
        }
    }
    for(operation=0;operation<sizeof unaries/sizeof *unaries;++operation)
        for(int i=0;i<3;++i){argument=i==0?NAN:i==1?INFINITY:-INFINITY;caught(bad_unary);}
    for(operation=0;operation<sizeof binaries/sizeof *binaries;++operation)
        for(side=0;side<2;++side)for(int i=0;i<3;++i){argument=i==0?NAN:i==1?INFINITY:-INFINITY;caught(bad_binary);}
    for(operation=0;operation<8;++operation)caught(bad_domain);
    for(operation=0;operation<5;++operation){
        bh_numeric_seed(42,&location);caught(bad_range);
        int64_t after=bh_numeric_random_int(0,100,&location);bh_numeric_seed(42,&location);
        assert(after==bh_numeric_random_int(0,100,&location)); /* invalid bounds consume no draw */
    }
    bh_shutdown();assert(bh_heap_objects()==0);
    bh_runtime_init();bh_numeric_seed(0,&location);assert(bh_numeric_random_int(-10,10,&location)==-3);bh_shutdown();
    puts("Native math/random checks passed.");return 0;
}
