#!/bin/bash
mvn test "$@" | tail -n 100
exit ${PIPESTATUS[0]}
