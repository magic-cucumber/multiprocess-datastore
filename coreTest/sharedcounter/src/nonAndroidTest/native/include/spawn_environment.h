#pragma once
#include <unistd.h>
extern char **environ;
static inline char **counter_test_environ(void) { return environ; }
