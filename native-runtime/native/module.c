#include <node_api.h>
napi_value dshm_register_flock(napi_env,napi_value);
napi_value dshm_register_publish(napi_env,napi_value);
NAPI_MODULE_INIT() { if(!dshm_register_flock(env,exports))return NULL;return dshm_register_publish(env,exports); }
