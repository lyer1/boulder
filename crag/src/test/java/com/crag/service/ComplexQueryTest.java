package com.crag.service;

import com.boulder.state.ChalkBag;
import com.crag.entity.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ComplexQueryTest {

    private static EntityManagerFactory emf;

    @BeforeAll
    public static void setUp() throws Exception {
        emf = Persistence.createEntityManagerFactory("crag-pu");

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS user_address");
                stmt.execute("DROP TABLE IF EXISTS hr_employee");
                stmt.execute("DROP TABLE IF EXISTS sys_user");
                stmt.execute("DROP TABLE IF EXISTS org_user");
                stmt.execute("DROP TABLE IF EXISTS department");
                stmt.execute("DROP TABLE IF EXISTS organization");
                stmt.execute("DROP TABLE IF EXISTS designation");
                stmt.execute("DROP TABLE IF EXISTS vpf_table");
                
                stmt.execute("CREATE TABLE organization (organizationid BIGINT PRIMARY KEY, name VARCHAR(255))");
                stmt.execute("CREATE TABLE department (departmentid BIGINT PRIMARY KEY, name VARCHAR(255), org_id BIGINT)");
                stmt.execute("CREATE TABLE designation (designationid BIGINT PRIMARY KEY, designationname VARCHAR(255))");
                stmt.execute("CREATE TABLE sys_user (userid BIGINT PRIMARY KEY, username VARCHAR(255), enabled BOOLEAN)");
                stmt.execute("CREATE TABLE user_address (addressid BIGINT PRIMARY KEY, city VARCHAR(255), state VARCHAR(255), user_id BIGINT)");
                stmt.execute("CREATE TABLE hr_employee (employeeid BIGINT PRIMARY KEY, systenantid INT, hrorganizationid INT, designation_id BIGINT, dept_id BIGINT, user_id BIGINT)");
                stmt.execute("CREATE TABLE org_user (userid BIGINT PRIMARY KEY, username VARCHAR(255), organizationid INT)");
                stmt.execute("CREATE TABLE vpf_table (id INT AUTO_INCREMENT PRIMARY KEY, emp_id INT, percent DOUBLE, status VARCHAR(255))");
                
                // Initial data
                stmt.execute("INSERT INTO organization (organizationid, name) VALUES (1, 'TechCorp')");
                stmt.execute("INSERT INTO department (departmentid, name, org_id) VALUES (10, 'Engineering', 1)");
                stmt.execute("INSERT INTO designation (designationid, designationname) VALUES (500, 'Senior Engineer')");
                stmt.execute("INSERT INTO sys_user (userid, username, enabled) VALUES (1, 'user1', true)");
                stmt.execute("INSERT INTO user_address (addressid, city, state, user_id) VALUES (1, 'Bangalore', 'Karnataka', 1)");
                stmt.execute("INSERT INTO hr_employee (employeeid, systenantid, hrorganizationid, designation_id, dept_id, user_id) VALUES (101, 10, 1, 500, 10, 1)");

                stmt.execute("INSERT INTO sys_user (userid, username, enabled) VALUES (2, 'user2', false)");
                stmt.execute("INSERT INTO hr_employee (employeeid, systenantid, hrorganizationid, designation_id, dept_id, user_id) VALUES (102, 10, 1, 500, 10, 2)");

                stmt.execute("INSERT INTO org_user (userid, username, organizationid) VALUES (1, 'org_user_1', 100)");
                stmt.execute("INSERT INTO org_user (userid, username, organizationid) VALUES (2, 'org_user_2', 100)");
            }
        }
    }

    @AfterAll
    public static void tearDown() {
        if (emf != null) emf.close();
    }

    @BeforeEach
    public void clearProxy() {
        ChalkBag.clear();
    }

    private void assertPhysicalDatabaseCounts() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM hr_employee"); rs.next(); assertEquals(2, rs.getInt(1), "Physical DB modified: hr_employee count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM sys_user"); rs.next(); assertEquals(2, rs.getInt(1), "Physical DB modified: sys_user count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM designation"); rs.next(); assertEquals(1, rs.getInt(1), "Physical DB modified: designation count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM department"); rs.next(); assertEquals(1, rs.getInt(1), "Physical DB modified: department count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM organization"); rs.next(); assertEquals(1, rs.getInt(1), "Physical DB modified: organization count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM user_address"); rs.next(); assertEquals(1, rs.getInt(1), "Physical DB modified: user_address count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM org_user"); rs.next(); assertEquals(2, rs.getInt(1), "Physical DB modified: org_user count");
                rs = stmt.executeQuery("SELECT COUNT(*) FROM vpf_table"); rs.next(); assertEquals(0, rs.getInt(1), "Physical DB modified: vpf_table count");
            }
        }
    }

    @Test
    public void testProjectingColumnsFromJoinedTables() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Virtual designation for physical employee
        Designation vDesig = new Designation();
        vDesig.setDesignationName("Lead Architect");
        vDesig = em.merge(vDesig);

        HrEmployee e101 = em.find(HrEmployee.class, 101L);
        e101.setDesignation(vDesig);
        em.flush();
        em.clear();

        // Query projecting columns from both tables
        String hql = "SELECT e.employeeId, d.designationName FROM HrEmployee e JOIN e.designation d WHERE e.employeeId = 101";
        List<Object[]> results = em.createQuery(hql).getResultList();

        assertEquals(1, results.size());
        assertEquals(101L, ((Number) results.get(0)[0]).longValue());
        assertEquals("Lead Architect", results.get(0)[1]);

        em.getTransaction().rollback();
        em.close();
        
        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT designation_id FROM hr_employee WHERE employeeid = 101");
             rs.next();
             assertEquals(500, rs.getInt(1), "Physical DB modified: employee 101 designation should remain 500");
        }
    }

    @Test
    public void testDistinctQuery() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Create 2 virtual employees in same department
        Department dept = em.find(Department.class, 10L);
        
        HrEmployee v1 = new HrEmployee();
        v1.setSysTenantId(10);
        v1.setDepartment(dept);
        em.merge(v1);

        HrEmployee v2 = new HrEmployee();
        v2.setSysTenantId(10);
        v2.setDepartment(dept);
        em.merge(v2);

        em.flush();
        em.clear();

        // Query for distinct department names from employees
        String hql = "SELECT DISTINCT d.name FROM HrEmployee e JOIN e.department d WHERE e.sysTenantId = 10";
        List<String> names = em.createQuery(hql, String.class).getResultList();

        assertEquals(1, names.size());
        assertEquals("Engineering", names.get(0));

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
    }

    @Test
    public void testMultiLevelJoin_EmployeeToOrganization() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // 1. Create a virtual organization, department, and employee in proxy
        Organization vOrg = new Organization();
        vOrg.setName("VirtualCorp");
        vOrg = em.merge(vOrg);

        Department vDept = new Department();
        vDept.setName("V-Sales");
        vDept.setOrganization(vOrg);
        vDept = em.merge(vDept);

        HrEmployee vEmp = new HrEmployee();
        vEmp.setSysTenantId(10);
        vEmp.setDepartment(vDept);
        vEmp = em.merge(vEmp);

        em.flush();
        em.clear();

        // 2. Query with multiple joins across virtual and physical rows
        String hql = "SELECT e.employeeId, d.name, o.name FROM HrEmployee e JOIN e.department d JOIN d.organization o WHERE o.name = 'VirtualCorp'";
        List<Object[]> results = em.createQuery(hql).getResultList();

        assertEquals(1, results.size());
        assertEquals(vEmp.getEmployeeId(), ((Number) results.get(0)[0]).longValue());
        assertEquals("V-Sales", results.get(0)[1]);
        assertEquals("VirtualCorp", results.get(0)[2]);

        // 3. Query with join across physical rows
        hql = "SELECT e.employeeId, d.name, o.name FROM HrEmployee e JOIN e.department d JOIN d.organization o WHERE o.name = 'TechCorp'";
        results = em.createQuery(hql).getResultList();
        assertEquals(2, results.size());

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
    }

    @Test
    public void testNestedJoin_UserAddresses() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Add a virtual address to physical user 1
        SysUser user1 = em.find(SysUser.class, 1L);
        UserAddress vAddr = new UserAddress();
        vAddr.setCity("Mumbai");
        vAddr.setState("Maharashtra");
        vAddr.setSysUser(user1);
        em.merge(vAddr);

        em.flush();
        em.clear();

        // Query for user by address city (Bangalore - physical, Mumbai - virtual)
        String hql = "SELECT u.username, a.city FROM SysUser u JOIN u.addresses a WHERE a.city = 'Mumbai'";
        List<Object[]> results = em.createQuery(hql).getResultList();

        assertEquals(1, results.size());
        assertEquals("user1", results.get(0)[0]);
        assertEquals("Mumbai", results.get(0)[1]);

        hql = "SELECT u.username, a.city FROM SysUser u JOIN u.addresses a WHERE a.city = 'Bangalore'";
        results = em.createQuery(hql).getResultList();
        assertEquals(1, results.size());
        assertEquals("user1", results.get(0)[0]);
        assertEquals("Bangalore", results.get(0)[1]);

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT city FROM user_address WHERE user_id = 1");
             rs.next();
             assertEquals("Bangalore", rs.getString(1), "Physical DB modified: user 1 address should remain Bangalore");
             assertFalse(rs.next(), "Physical DB modified: user 1 should only have 1 physical address");
        }
    }

    @Test
    public void testJoinFetchWithVirtualRows() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Create virtual designation and assign to employee 101
        Designation vDesig = new Designation();
        vDesig.setDesignationName("Lead Architect");
        vDesig = em.merge(vDesig);

        HrEmployee e101 = em.find(HrEmployee.class, 101L);
        e101.setDesignation(vDesig);
        em.flush();
        em.clear();

        // Join Fetch should work correctly even with virtual entities
        String hql = "SELECT e FROM HrEmployee e JOIN FETCH e.designation d WHERE e.employeeId = 101";
        HrEmployee result = em.createQuery(hql, HrEmployee.class).getSingleResult();

        assertNotNull(result.getDesignation());
        assertEquals("Lead Architect", result.getDesignation().getDesignationName());

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT designation_id FROM hr_employee WHERE employeeid = 101");
             rs.next();
             assertEquals(500, rs.getInt(1), "Physical DB modified: employee 101 designation should remain 500");
        }
    }

    @Test
    public void testCountAggregate_WithProxyChanges() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        HrEmployee e1 = em.find(HrEmployee.class, 101L);
        em.remove(e1);
        
        HrEmployee e3 = new HrEmployee();
        e3.setSysTenantId(10);
        em.merge(e3);
        
        em.flush();

        Long count = em.createQuery("SELECT COUNT(e.employeeId) FROM HrEmployee e WHERE e.sysTenantId = 10", Long.class)
                       .getSingleResult();

        assertEquals(2L, count, "Count should reflect tombstones and virtual inserts");

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT employeeid FROM hr_employee WHERE employeeid = 101");
             assertTrue(rs.next(), "Physical DB modified: removed employee 101 should still exist physically");
        }
    }

    @Test
    public void testChainedProxyOperations() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        OrgUser newUser = new OrgUser();
        newUser.setUserName("initial_name");
        newUser.setOrganizationID(999);
        newUser = em.merge(newUser);
        em.flush();

        newUser.setUserName("chained_update");
        newUser = em.merge(newUser);
        em.flush();

        OrgUser fetched = em.find(OrgUser.class, newUser.getUserID());
        assertNotNull(fetched, "Should find the virtual row");
        assertEquals("chained_update", fetched.getUserName(), "Proxy should support chained virtual operations");

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM org_user WHERE organizationid = 999");
             rs.next();
             assertEquals(0, rs.getInt(1), "Physical DB modified: chained virtual user should not exist physically");
        }
    }

    @Test
    public void testDeleteByNonPkFilter() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        Query q = em.createQuery("DELETE FROM OrgUser u WHERE u.organizationID = 100");
        int deleted = q.executeUpdate();
        assertEquals(2, deleted);
        
        em.flush();
        em.clear();

        List<OrgUser> remaining = em.createQuery("SELECT u FROM OrgUser u WHERE u.organizationID = 100", OrgUser.class)
                                    .getResultList();
        
        assertEquals(0, remaining.size(), "Non-PK DELETE should tombstone all matching physical rows");

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM org_user WHERE organizationid = 100");
             rs.next();
             assertEquals(2, rs.getInt(1), "Physical DB modified: physically deleted users should still exist");
        }
    }

    @Test
    public void testJoinWithBothVirtualRows() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        SysUser vUser = new SysUser();
        vUser.setUsername("virtual_user");
        vUser.setEnabled(true);
        vUser = em.merge(vUser);

        HrEmployee vEmp = new HrEmployee();
        vEmp.setSysTenantId(10);
        vEmp.setSysUser(vUser);
        vEmp = em.merge(vEmp);

        em.flush();
        em.clear();

        List<Object[]> results = em.createQuery("SELECT e.employeeId, u.username FROM HrEmployee e JOIN e.sysUser u WHERE e.employeeId = " + vEmp.getEmployeeId())
                                   .getResultList();

        assertEquals(1, results.size());
        assertEquals(vEmp.getEmployeeId(), ((Number) results.get(0)[0]).longValue());
        assertEquals("virtual_user", results.get(0)[1]);

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
    }

    @Test
    public void testFilterReevaluation_PatchToExclude() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // 101 is physically tenant 10. Change it to 20 in proxy.
        HrEmployee e101 = em.find(HrEmployee.class, 101L);
        e101.setSysTenantId(20);
        em.flush();

        // Query for tenant 10 should now EXCLUDE 101 because of the patch
        List<HrEmployee> results = em.createQuery("SELECT e FROM HrEmployee e WHERE e.sysTenantId = 10", HrEmployee.class)
                                     .getResultList();

        for (HrEmployee e : results) {
            assertNotEquals(101L, e.getEmployeeId(), "Patched row should be excluded if it no longer matches WHERE clause");
        }

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT systenantid FROM hr_employee WHERE employeeid = 101");
             rs.next();
             assertEquals(10, rs.getInt(1), "Physical DB modified: employee 101 tenantId should remain 10 physically");
        }
    }

    @Test
    public void testFilterReevaluation_PatchToInclude() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // 102 is physically tenant 10. Change it to 30 in proxy.
        HrEmployee e102 = em.find(HrEmployee.class, 102L);
        e102.setSysTenantId(30);
        em.flush();

        // Query for tenant 30 should now INCLUDE 102 even though physically it's 10.
        // This is the "Pull-through" problem.
        List<HrEmployee> results = em.createQuery("SELECT e FROM HrEmployee e WHERE e.sysTenantId = 30", HrEmployee.class)
                                     .getResultList();

        boolean found = false;
        for (HrEmployee e : results) {
            if (e.getEmployeeId().equals(102L)) { found = true; break; }
        }
        assertTrue(found, "Proxy should pull in rows that match filters ONLY after patching");

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT systenantid FROM hr_employee WHERE employeeid = 102");
             rs.next();
             assertEquals(10, rs.getInt(1), "Physical DB modified: employee 102 tenantId should remain 10 physically");
        }
    }

    @Test
    public void testLeftJoin_PhysicalPrimary_VirtualJoined() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Employee 102 exists physically but its User 2 is disabled.
        // Let's create a NEW virtual user and link Employee 102 to it in proxy.
        SysUser vUser = new SysUser();
        vUser.setUsername("new_virtual_user");
        vUser.setEnabled(true);
        vUser = em.merge(vUser);

        HrEmployee e102 = em.find(HrEmployee.class, 102L);
        e102.setSysUser(vUser);
        em.flush();

        // Left join query
        Object[] row = em.createQuery("SELECT e.employeeId, u.username FROM HrEmployee e LEFT JOIN e.sysUser u WHERE e.employeeId = 102", Object[].class)
                         .getSingleResult();

        assertEquals(102L, row[0]);
        assertEquals("new_virtual_user", row[1], "Should see virtual user linked to physical employee");

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT user_id FROM hr_employee WHERE employeeid = 102");
             rs.next();
             assertEquals(2, rs.getInt(1), "Physical DB modified: employee 102 user_id should remain 2 physically");
        }
    }

    @Test
    public void testOrderBy_VirtualInterleaving() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Physical OrgUsers: 1 (org_user_1), 2 (org_user_2)
        // Insert virtual OrgUser: 1.5 (org_user_1.5)
        OrgUser vUser = new OrgUser();
        vUser.setUserName("org_user_1.5");
        vUser.setOrganizationID(100);
        vUser = em.merge(vUser);
        em.flush();

        // Query with Order By
        List<String> names = em.createQuery("SELECT u.userName FROM OrgUser u WHERE u.organizationID = 100 ORDER BY u.userName ASC", String.class)
                               .getResultList();

        assertEquals(3, names.size());
        assertEquals("org_user_1", names.get(0));
        assertEquals("org_user_1.5", names.get(1), "Virtual rows must be correctly interleaved in sorted results");
        assertEquals("org_user_2", names.get(2));

        em.getTransaction().rollback();
        em.close();

        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM org_user WHERE username = 'org_user_1.5'");
             rs.next();
             assertEquals(0, rs.getInt(1), "Physical DB modified: virtual user should not exist physically");
        }
    }

    @Test
    public void testGroupByWithVirtualData() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Setup: We physically have Employee 101 and 102 in Dept 10.
        // We will virtually delete 102 from Dept 10.
        HrEmployee e102 = em.find(HrEmployee.class, 102L);
        em.remove(e102);

        // We will virtually insert a new employee into a new Dept 20.
        Department vDept20 = new Department();
        vDept20.setName("Virtual HR");
        vDept20 = em.merge(vDept20);

        HrEmployee vEmpNew = new HrEmployee();
        vEmpNew.setDepartment(vDept20);
        vEmpNew.setSysTenantId(10);
        vEmpNew = em.merge(vEmpNew);

        em.flush();
        em.clear();

        // The query: Group by department, expecting Dept 10 = 1 (101 remains), Dept 20 = 1 (vEmpNew added)
        String hql = "SELECT e.department.departmentId, COUNT(e.employeeId) FROM HrEmployee e GROUP BY e.department.departmentId";
        List<Object[]> results = em.createQuery(hql).getResultList();

        assertEquals(2, results.size(), "Should have exactly 2 groups returned");
        
        boolean foundDept10 = false;
        boolean foundDept20 = false;

        for (Object[] row : results) {
            Long deptId = ((Number) row[0]).longValue();
            Long count = ((Number) row[1]).longValue();

            if (deptId.equals(10L)) {
                foundDept10 = true;
                assertEquals(1L, count, "Dept 10 should have 1 employee left after virtual delete");
            } else if (deptId.equals(vDept20.getDepartmentId())) {
                foundDept20 = true;
                assertEquals(1L, count, "Virtual Dept 20 should have 1 virtual employee");
            }
        }

        assertTrue(foundDept10, "Dept 10 group is missing");
        assertTrue(foundDept20, "Virtual Dept 20 group is missing");

        em.getTransaction().rollback();
        em.close();
        
        assertPhysicalDatabaseCounts();
    }

    @Test
    public void testComplexFederatedQuery_WithFunctionsJoinsAndMixedState() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // 1. Create Pure Virtual Entities
        Organization vOrg = new Organization();
        vOrg.setName("AcmeCorp");
        vOrg = em.merge(vOrg);

        Department vDept = new Department();
        vDept.setName("Sales");
        vDept.setOrganization(vOrg);
        vDept = em.merge(vDept);

        Designation vDesig = new Designation();
        vDesig.setDesignationName("Manager");
        vDesig = em.merge(vDesig);

        SysUser vUser = new SysUser();
        vUser.setUsername("v_user");
        vUser.setEnabled(true);
        vUser = em.merge(vUser);

        HrEmployee vEmp = new HrEmployee();
        vEmp.setSysTenantId(50);
        vEmp.setDepartment(vDept);
        vEmp.setDesignation(vDesig);
        vEmp.setSysUser(vUser);
        vEmp = em.merge(vEmp);

        // 2. Patch an existing Physical Entity (Mixed State)
        HrEmployee e101 = em.find(HrEmployee.class, 101L);
        e101.setDesignation(vDesig); // Virtual relation on physical row
        e101.setSysTenantId(20);     // Scalar patch on physical row
        em.flush();
        em.clear();

        // 3. The Complex Query
        // - Uses SQL string functions: CONCAT, LOWER
        // - Joins 5 tables: HrEmployee -> Department -> Organization, HrEmployee -> Designation, HrEmployee -> SysUser
        // - Filters on patched value and physical value
        // - Orders by patched/physical values
        String hql = "SELECT e.employeeId, " +
                     "CONCAT(LOWER(d.name), '_', LOWER(o.name)), " +
                     "desig.designationName, " +
                     "u.username, " +
                     "e.sysTenantId " +
                     "FROM HrEmployee e " +
                     "JOIN e.department d " +
                     "JOIN d.organization o " +
                     "JOIN e.designation desig " +
                     "JOIN e.sysUser u " +
                     "WHERE e.sysTenantId >= 10 " +
                     "ORDER BY e.sysTenantId DESC, e.employeeId DESC";

        List<Object[]> results = em.createQuery(hql).getResultList();

        // We expect 3 rows:
        // 1. vEmp (tenant 50)
        // 2. e101 (tenant 20 - patched)
        // 3. e102 (tenant 10 - pure physical)
        assertEquals(3, results.size(), "Should return exactly 3 rows combining physical, patched, and virtual data");

        // Assert Virtual Employee
        Object[] row1 = results.get(0);
        assertEquals(vEmp.getEmployeeId(), ((Number) row1[0]).longValue());
        assertEquals("sales_acmecorp", row1[1], "SQL Functions should evaluate perfectly on pure virtual rows");
        assertEquals("Manager", row1[2]);
        assertEquals("v_user", row1[3]);
        assertEquals(50, ((Number) row1[4]).intValue());

        // Assert Patched Physical Employee (e101)
        Object[] row2 = results.get(1);
        assertEquals(101L, ((Number) row2[0]).longValue());
        assertEquals("engineering_techcorp", row2[1], "SQL Functions should evaluate perfectly on physical row components");
        assertEquals("Manager", row2[2], "Virtual join patch should reflect in result");
        assertEquals("user1", row2[3]);
        assertEquals(20, ((Number) row2[4]).intValue(), "Scalar patch should reflect in projection and ordering");

        // Assert Pure Physical Employee (e102)
        Object[] row3 = results.get(2);
        assertEquals(102L, ((Number) row3[0]).longValue());
        assertEquals("engineering_techcorp", row3[1]);
        assertEquals("Senior Engineer", row3[2]);
        assertEquals("user2", row3[3]);
        assertEquals(10, ((Number) row3[4]).intValue());

        em.getTransaction().rollback();
        em.close();
        
        assertPhysicalDatabaseCounts();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "");
             Statement stmt = conn.createStatement()) {
             ResultSet rs = stmt.executeQuery("SELECT designation_id, systenantid FROM hr_employee WHERE employeeid = 101");
             rs.next();
             assertEquals(500, rs.getInt(1), "Physical DB modified: employee 101 designation should remain 500");
             assertEquals(10, rs.getInt(2), "Physical DB modified: employee 101 tenant should remain 10");
        }
    }
}