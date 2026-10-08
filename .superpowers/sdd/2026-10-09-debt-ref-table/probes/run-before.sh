#!/usr/bin/env bash
# 「改动前」运行器：/tmp/debtref-before = git HEAD(359098e3) 现编的 class 树；探针字节同一份
set -u
M2=/home/cna/.m2/repository
PREFIX="/tmp/debtref/out"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-api/2.26.1/log4j-api-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-core/2.26.1/log4j-core-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-slf4j2-impl/2.26.1/log4j-slf4j2-impl-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/slf4j/slf4j-api/2.0.19/slf4j-api-2.0.19.jar"
exec java -Xmx2g -cp "$PREFIX:$(cat /tmp/debtref/cp-before.txt)" "$@"
