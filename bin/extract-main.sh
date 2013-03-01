#!/bin/sh

grep "<package-and-document"  build.xml | perl -ne 'print "\"$2\"->\"$1\",\n" if(/main-class=\"(\S+\.(\S+))\"/)'
