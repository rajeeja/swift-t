#!/bin/zsh -f
set -eu

# ADLB/X FILE LIST
# Produces list of files for make-release-pkg and mk-upstream-tgz
# Also makes a soft link to the Debian package files,
# either for the bin or dev package

print bootstrap configure configure.ac Makefile.in install-sh
print config.h.in
print maint/*.sh maint/*.c
print maint/{debian,version}.mkf
print src/*.[ch]
print src/{adlb-version.h.in,mpe-settings.h.in}
print {src,tests}/module.mk.in
print version.txt NOTICE

if [[ ${PKG_TYPE} == deb-* ]]
then
  ln -sfT maint/debian-${PKG_TYPE} debian
  print debian/*[^~]
fi
