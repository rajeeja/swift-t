
#include <builtins.swift>
#include <swift/unistd.swift>
#include "../util/bench.swift"

/*
  delay in milliseconds
*/
main {
  argv_accept("N", "delay");
  int   N     = toint(argv("N"));
  float delay = tofloat(argv("delay"));

  float A[];

  // @sync
  // @splitdegree // STC test 587
  // Make
  // No actual container

  foreach i in [0:N-1]
  {
    A[i] = set1_float(delay);
  }
}
