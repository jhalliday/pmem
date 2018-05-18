#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include <libpmemlog.h>

int main()
{
    int size = 800*1024*1024;
    const char path[] = "/mnt/pmem/test/pmemlogc";
	PMEMlogpool *plp;
	size_t nbyte;
	char *str;

	plp = pmemlog_create(path, size, 0666);

	if (plp == NULL)
		plp = pmemlog_open(path);

	if (plp == NULL) {
		perror(path);
		exit(1);
	}

    char* data = malloc(50);

    for(int i = 0; i < 20000000; i++) {

        sprintf(data, "This is the %ith string appended", i);

        if (pmemlog_append(plp, data, strlen(data)) < 0) {
		    perror("pmemlog_append");
		    exit(1);
	    }
	}

    free((void*)data);
	pmemlog_close(plp);

    return 0;
}