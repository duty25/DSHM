/* SPDX-License-Identifier: MIT
 * DSHM contributors: asynchronous, fail-closed renameat2(RENAME_NOREPLACE).
 * Original file identity is preserved. No copy/overwrite fallback.
 */
#define _GNU_SOURCE
#include <node_api.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif
typedef struct {
  napi_env env; napi_ref callback; napi_async_work work;
  napi_async_cleanup_hook_handle cleanup;
  char *source; char *target; int error; bool closing;
} publish_request;
static void pub_check(napi_status s,const char *m) {
  if(s!=napi_ok)napi_fatal_error("dshm-publish",NAPI_AUTO_LENGTH,m,NAPI_AUTO_LENGTH);
}
static void pub_free(publish_request *r) {
  if(r->callback)(void)napi_delete_reference(r->env,r->callback);
  if(r->work)(void)napi_delete_async_work(r->env,r->work);
  if(r->cleanup)(void)napi_remove_async_cleanup_hook(r->cleanup);
  free(r->source);free(r->target);free(r);
}
static napi_value pub_throw(napi_env env,napi_status s,const char *m) {
  if(s!=napi_pending_exception)(void)napi_throw_error(env,"ERR_DSHM_ATOMIC_PUBLISH",m);
  return NULL;
}
static bool pub_path(napi_env env,napi_value value,char **out) {
  size_t size=0;
  if(napi_get_value_string_utf8(env,value,NULL,0,&size)!=napi_ok){
    (void)napi_throw_type_error(env,NULL,"Publication paths must be strings");return false;
  }
  if(size==0||size>1048576){(void)napi_throw_range_error(env,NULL,"Invalid publication path length");return false;}
  char *p=malloc(size+1);
  if(!p){(void)napi_throw_error(env,"ENOMEM","Cannot allocate publication path");return false;}
  size_t actual=0;
  if(napi_get_value_string_utf8(env,value,p,size+1,&actual)!=napi_ok||actual!=size||strlen(p)!=size||p[0]!='/'){
    free(p);(void)napi_throw_type_error(env,NULL,"Publication paths must be absolute and contain no NUL");return false;
  }
  *out=p;return true;
}
static void pub_execute(napi_env env,void *data) {
  (void)env;publish_request *r=data;
#ifdef __NR_renameat2
  r->error=syscall(__NR_renameat2,AT_FDCWD,r->source,AT_FDCWD,r->target,RENAME_NOREPLACE)==0?0:errno;
#else
  r->error=ENOSYS;
#endif
}
static void pub_complete(napi_env env,napi_status status,void *data) {
  publish_request *r=data;
  if(env!=NULL&&!r->closing){
    napi_value cb,receiver,result;pub_check(status,"Atomic publication work did not complete");
    pub_check(napi_get_reference_value(env,r->callback,&cb),"Cannot retrieve publication callback");
    pub_check(napi_get_undefined(env,&receiver),"Cannot create publication receiver");
    pub_check(napi_create_int32(env,r->error,&result),"Cannot create publication errno");
    status=napi_call_function(env,receiver,cb,1,&result,NULL);
  }else status=napi_ok;
  pub_free(r);
  if(status!=napi_pending_exception)pub_check(status,"Cannot invoke publication callback");
}
static void pub_cleanup(napi_async_cleanup_hook_handle h,void *data) {
  (void)h;publish_request *r=data;r->closing=true;(void)napi_cancel_async_work(r->env,r->work);
}
static napi_value pub_call(napi_env env,napi_callback_info info) {
  size_t argc=3;napi_value argv[3];napi_status status=napi_get_cb_info(env,info,&argc,argv,NULL,NULL);
  if(status!=napi_ok)return pub_throw(env,status,"Cannot read publication arguments");
  napi_valuetype type;
  if(argc!=3||napi_typeof(env,argv[2],&type)!=napi_ok||type!=napi_function){(void)napi_throw_type_error(env,NULL,"Expected source, target, callback");return NULL;}
  publish_request *r=calloc(1,sizeof(*r));if(!r){(void)napi_throw_error(env,"ENOMEM","Cannot allocate publication work");return NULL;}
  r->env=env;
  if(!pub_path(env,argv[0],&r->source)||!pub_path(env,argv[1],&r->target)){pub_free(r);return NULL;}
  status=napi_create_reference(env,argv[2],1,&r->callback);
  napi_value name;
  if(status==napi_ok)status=napi_create_string_utf8(env,"renameat2-noreplace",NAPI_AUTO_LENGTH,&name);
  if(status==napi_ok)status=napi_create_async_work(env,NULL,name,pub_execute,pub_complete,r,&r->work);
  if(status==napi_ok)status=napi_add_async_cleanup_hook(env,pub_cleanup,r,&r->cleanup);
  if(status==napi_ok)status=napi_queue_async_work(env,r->work);
  if(status!=napi_ok){pub_free(r);return pub_throw(env,status,"Cannot queue atomic publication");}
  return NULL;
}
napi_value dshm_register_publish(napi_env env,napi_value exports) {
  napi_value fn;
  if(napi_create_function(env,"renameNoReplace",NAPI_AUTO_LENGTH,pub_call,NULL,&fn)!=napi_ok||napi_set_named_property(env,exports,"renameNoReplace",fn)!=napi_ok)return NULL;
  return exports;
}
