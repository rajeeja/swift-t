#include <builtins.swift>

// Regression test for compiler bug

// THIS-TEST-SHOULD-NOT-RUN
main {
    int A[];
    foreach i in [1:10] {
        // Assign array multiple times - should not be hoistedout of loop
        A[0] = 0;
    }
    trace(A[0]);
}
