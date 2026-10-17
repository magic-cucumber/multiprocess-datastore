#pragma once
#include <unistd.h>
extern char **environ;
static inline char **datastore_test_environ(void) { return environ; }
