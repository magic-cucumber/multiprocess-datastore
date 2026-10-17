#pragma once
#include <unistd.h>
extern char **environ;
static inline char **observer_test_environ(void) { return environ; }
