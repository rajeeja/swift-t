#!/bin/zsh

# Generate linear-adlb-*.tcl, a tcl-turbine-adlb test case

COUNT=$1

if [[ ${COUNT} == "" ]]
then
  print "Not given: COUNT"
  return 1
fi

OUTPUT="linear-adlb-${COUNT}.tcl"

# Header
{
  print
  print "# Generated by mk-linear-adlb.zsh"
  print "package require turbine 0.1"
  print
} > ${OUTPUT}

# Turbine data/rules section
{
  print "proc rules { } {"

  # Data declarations
  for (( i=0 ; i<COUNT ; i++ ))
  do
    print "\t turbine_file ${i} test/data/${i}.txt"
  done >> ${OUTPUT}

  # Task dependencies
  print "\t turbine_rule 0 0 {   } { 0 } { touch test/data/0.txt }"
  for (( i=1 ; i<COUNT ; i++ ))
  do
    PREV=$(( i-1 ))
    printf "\t turbine_rule ${i} ${i} { ${PREV} } { ${i} } "
    print "{ touch test/data/${i}.txt }"
  done
  print "}"
} >> ${OUTPUT}

# Footer
{
  print
  print "namespace import turbine::adlb::*"
  print "init"
  print "start rules"
  print "finalize"
  print "puts OK"
} >> ${OUTPUT}

print "wrote: ${OUTPUT}"

return 0
