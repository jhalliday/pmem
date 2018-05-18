#include <stdio.h>
#include <fcntl.h>
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <jni.h>
#include <libpmemlog.h>

#include "PmemLogJNIImpl.h"

JNIEXPORT jlong JNICALL Java_PmemLogJNIImpl_pmemlogCreate
  (JNIEnv *env, jobject thisObj, jbyteArray name, jint size) {

    jboolean is_copy;
    jbyte* namebytes = env->GetByteArrayElements(name, &is_copy);

  	PMEMlogpool *plp;

  	plp = pmemlog_create((char*)namebytes, (size_t)size, 0666);

    if (plp == NULL) {
        plp = pmemlog_open((char*)namebytes);
     }

  	return (jlong)plp;
}


JNIEXPORT void JNICALL Java_PmemLogJNIImpl_pmemlogAppend
  (JNIEnv *env, jobject thisObj, jlong plp, jbyteArray array) {

    jsize length = env->GetArrayLength(array);
    jboolean is_copy;
    jbyte* bytes = env->GetByteArrayElements(array, &is_copy);

    pmemlog_append((PMEMlogpool*)plp, bytes, length);
}
