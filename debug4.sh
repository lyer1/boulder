cd crag
mvn test -Dtest=ComplexQueryTest#testHrEmployeeSysUserJoin_WithProxyInsert -Dsurefire.useSystemClassLoader=false > debug_out.txt
grep "TEST RESULT" debug_out.txt
