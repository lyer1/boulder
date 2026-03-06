cd crag
mvn test -Dtest=ComplexQueryTest#testHrEmployeeSysUserJoin_WithProxyInsert -Dsurefire.useSystemClassLoader=false > debug_out.txt
grep "results" debug_out.txt
