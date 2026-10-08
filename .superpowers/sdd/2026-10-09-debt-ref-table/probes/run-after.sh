#!/usr/bin/env bash
# 「改动后」运行器：本仓 target/classes（含 2026-10-09 选项 A 的改动）
set -u
M2=/home/cna/.m2/repository
PREFIX="/tmp/debtref/out"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-api/2.26.1/log4j-api-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-core/2.26.1/log4j-core-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/apache/logging/log4j/log4j-slf4j2-impl/2.26.1/log4j-slf4j2-impl-2.26.1.jar"
PREFIX="$PREFIX:$M2/org/slf4j/slf4j-api/2.0.19/slf4j-api-2.0.19.jar"
exec java -Xmx2g -cp "$PREFIX:$(cat /tmp/debtref/cp-after.txt)" "$@"
