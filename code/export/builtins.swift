
// Swift/Turbine builtins defined here

// NYI = Not Yet Implemented at Turbine layer

// Data copy
(int o)    copy_integer(int i)    "turbine" "0.0.2" "copy_integer";
(float o)  copy_float  (float i)  "turbine" "0.0.2" "copy_float";
(string o) copy_string (string i) "turbine" "0.0.2" "copy_string";
(boolean o) copy_boolean (boolean i) "turbine" "0.0.2" "copy_integer";
(void o) copy_void (void i) "turbine" "0.0.2" "copy_void";
(blob o) copy_blob (blob i) "turbine" "0.0.2" "copy_blob";
(void o) make_void () "turbine" "0.0.2" "make_void";

// Arithmetic
(int o) plus_integer    (int i1, int i2) "turbine" "0.0.2" "plus_integer";
(int o) minus_integer   (int i1, int i2) "turbine" "0.0.2" "minus_integer";
(int o) multiply_integer(int i1, int i2) "turbine" "0.0.2" "multiply_integer";
(int o) divide_integer  (int i1, int i2) "turbine" "0.0.2" "divide_integer";
(int o) mod_integer     (int i1, int i2) "turbine" "0.0.2" "mod_integer";
(int o) negate_integer  (int i)          "turbine" "0.0.2" "negate_integer";
(int o) max_integer     (int i1, int i2) "turbine" "0.0.2" "max_integer";
(int o) min_integer     (int i1, int i2) "turbine" "0.0.2" "min_integer";
(float o) pow_integer     (int i1, int i2) "turbine" "0.0.2" "pow_integer";

(boolean o) and (boolean i1, boolean i2) "turbine" "0.0.2" "and";
(boolean o) or  (boolean i1, boolean i2) "turbine" "0.0.2" "or";

(boolean o) xor (boolean i1, boolean i2) "turbine" "0.0.2" "neq_integer";
(boolean o) not (boolean i)          "turbine" "0.0.2" "not";

(float o) plus_float    (float i1, float i2) "turbine" "0.0.2" "plus_float";
(float o) minus_float   (float i1, float i2) "turbine" "0.0.2" "minus_float";
(float o) multiply_float(float i1, float i2) "turbine" "0.0.2" "multiply_float";
(float o) divide_float  (float i1, float i2) "turbine" "0.0.2" "divide_float";
(float o) negate_float  (float i)            "turbine" "0.0.2" "negate_float";
(float o) max_float     (float i1, float i2) "turbine" "0.0.2" "max_float";
(float o) min_float     (float i1, float i2) "turbine" "0.0.2" "min_float";
(float o) pow_float     (float i1, float i2) "turbine" "0.0.2" "pow_float";

// Mathematical functions
(int o) abs_integer     (int i) "turbine"  "0.0.2" "abs_integer";
(float o) abs_float     (float i) "turbine"  "0.0.2" "abs_float";
(int o) floor           (float i) "turbine"  "0.0.2" "floor";
(int o) ceil            (float i) "turbine"  "0.0.2" "ceil";
(int o) round           (float i) "turbine"  "0.0.2" "round";
(float o) log           (float i) "turbine"  "0.0.2" "log_e";
(float o) exp           (float i) "turbine"  "0.0.2" "exp";
(float o) sqrt          (float i) "turbine"  "0.0.2" "sqrt";

// Relational

(boolean o) eq_integer (int i1, int i2) "turbine" "0.0.2" "eq_integer";
(boolean o) neq_integer(int i1, int i2) "turbine" "0.0.2" "neq_integer";
(boolean o) lt_integer (int i1, int i2) "turbine" "0.0.2" "lt_integer";
(boolean o) lte_integer(int i1, int i2) "turbine" "0.0.2" "lte_integer";
(boolean o) gt_integer (int i1, int i2) "turbine" "0.0.2" "gt_integer";
(boolean o) gte_integer(int i1, int i2) "turbine" "0.0.2" "gte_integer";

(boolean o) eq_float (float i1, float i2) "turbine" "0.0.2" "eq_float";
(boolean o) neq_float(float i1, float i2) "turbine" "0.0.2" "neq_float";
(boolean o) lt_float (float i1, float i2) "turbine" "0.0.2" "lt_float";
(boolean o) lte_float(float i1, float i2) "turbine" "0.0.2" "lte_float";
(boolean o) gt_float (float i1, float i2) "turbine" "0.0.2" "gt_float";
(boolean o) gte_float(float i1, float i2) "turbine" "0.0.2" "gte_float";

(boolean o) eq_string (string i1, string i2) "turbine" "0.0.2" "eq_string";
(boolean o) neq_string(string i1, string i2) "turbine" "0.0.2" "neq_string";
// String lexical sorting
// NYI (boolean o) lt_string (string i1, string i2) "turbine" "0.0.2" "lt_string";
// NYI (boolean o) lte_string(string i1, string i2) "turbine" "0.0.2" "lte_string";
// NYI (boolean o) gt_string (string i1, string i2) "turbine" "0.0.2" "gt_string";
// NYI (boolean o) gte_string(string i1, string i2) "turbine" "0.0.2" "gte_string";

(boolean o) eq_boolean (boolean i1, boolean i2) "turbine" "0.0.2" "eq_integer";
(boolean o) neq_boolean(boolean i1, boolean i2) "turbine" "0.0.2" "neq_integer";

// Random functions
(float o) random() "turbine" "0.0.2" "random";
(int o) randint(int start, int end) "turbine" "0.0.2" "randint";

// String operations
(string o) strcat(string i1, string i2) "turbine" "0.0.2" "strcat";
(string o) substring(string s, int start, int length) "turbine" "0.0.2" "substring";
(string t[]) split(string s, string delimiter) "turbine" "0.0.2" "split";

// Type conversion
(string o) fromint(int i)  "turbine" "0.0.2" "fromint";
(int o)    toint(string i) "turbine" "0.0.2" "toint";
(string o) fromfloat(float i)  "turbine" "0.0.2" "fromfloat";
(float o) tofloat(string i)  "turbine" "0.0.2" "tofloat";
(float o) itof    (int i) "turbine"  "0.0.2" "itof";

// I/O
() trace (...) "turbine" "0.0.2" "trace";

// Container operations
(int res[]) range(int start, int end) "turbine" "0.0.2" "range";
(int res[]) rangestep(int start, int end, int step) "turbine" "0.0.2" "rangestep";


// Container aggregate functions
(int res) sum_integer(int arr[]) "turbine" "0.0.2" "sum_integer";
(float res) avg_float(float arr[]) "turbine" "0.0.2" "avg_float";
(float res) avg_integer(int arr[]) "turbine" "0.0.2" "avg_float";
// Population standard deviation
(float res) std_float(float arr[]) "turbine" "0.0.2" "std_float";
(float res) std_integer(int arr[]) "turbine" "0.0.2" "std_float";
(float mean, float std) stats_float(float arr[]) "turbine" "0.0.2" "stats_float";
(float mean, float std) stats_integer(int arr[]) "turbine" "0.0.2" "stats_float";


// Aggregate partial statistics
type PartialStats {
  int n; /* number of samples */
  float mean;
  float M2; /* Variance * n_samples */
}
(int n, float mean, float M2) statagg_float(float arr[]) "turbine" "0.0.2" "statagg_float";
(int n, float mean, float std) stat_combine(PartialStats arr[]) "turbine" "0.0.2" "stat_combine";

// Misc
() assert(boolean condition, string msg) "turbine" "0.0.2" "assert";
