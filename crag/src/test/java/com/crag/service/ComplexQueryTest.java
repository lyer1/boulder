package com.crag.service;

import com.boulder.state.ChalkBag;
import com.crag.entity.HrEmployee;
import com.crag.entity.SysUser;
import com.crag.entity.OrgUser;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DriverManager;
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
                stmt.execute("DROP TABLE IF EXISTS hr_employee");
                stmt.execute("DROP TABLE IF EXISTS sys_user");
                stmt.execute("DROP TABLE IF EXISTS org_user");
                
                stmt.execute("CREATE TABLE sys_user (userId BIGINT PRIMARY KEY, username VARCHAR(255), enabled BOOLEAN)");
                stmt.execute("CREATE TABLE hr_employee (employeeId BIGINT PRIMARY KEY, sysTenantId INT, hrOrganizationId INT, designationID INT, user_id BIGINT)");
                stmt.execute("CREATE TABLE org_user (userID BIGINT PRIMARY KEY, userName VARCHAR(255), organizationID INT)");
                
                // Initial data for joins
                stmt.execute("INSERT INTO sys_user (userId, username, enabled) VALUES (1, 'user1', true)");
                stmt.execute("INSERT INTO hr_employee (employeeId, sysTenantId, hrOrganizationId, designationID, user_id) VALUES (101, 10, 1, 500, 1)");
                stmt.execute("INSERT INTO sys_user (userId, username, enabled) VALUES (2, 'user2', false)");
                stmt.execute("INSERT INTO hr_employee (employeeId, sysTenantId, hrOrganizationId, designationID, user_id) VALUES (102, 10, 1, 500, 2)");

                // Initial data for OrgUser
                stmt.execute("INSERT INTO org_user (userID, userName, organizationID) VALUES (1, 'org_user_1', 100)");
                stmt.execute("INSERT INTO org_user (userID, userName, organizationID) VALUES (2, 'org_user_2', 100)");
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

    @Test
    public void testCountAggregate_WithProxyChanges() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        HrEmployee e1 = em.find(HrEmployee.class, 101L);
        em.remove(e1);
        
        HrEmployee e3 = new HrEmployee();
        e3.setEmployeeId(103L);
        e3.setSysTenantId(10);
        em.merge(e3);
        
        em.flush();

        Long count = em.createQuery("SELECT COUNT(e.employeeId) FROM HrEmployee e WHERE e.sysTenantId = 10", Long.class)
                       .getSingleResult();

        assertEquals(2L, count, "Count should reflect tombstones and virtual inserts");

        em.getTransaction().rollback();
        em.close();
    }

    @Test
    public void testChainedProxyOperations() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        OrgUser newUser = new OrgUser();
        newUser.setUserID(500L);
        newUser.setUserName("initial_name");
        newUser.setOrganizationID(999);
        em.merge(newUser);
        em.flush();

        newUser.setUserName("chained_update");
        em.merge(newUser);
        em.flush();

        OrgUser fetched = em.find(OrgUser.class, 500L);
        assertNotNull(fetched, "Should find the virtual row");
        assertEquals("chained_update", fetched.getUserName(), "Proxy should support chained virtual operations");

        em.getTransaction().rollback();
        em.close();
    }

    @Test
    public void testDeleteByNonPkFilter() {
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
    }

    @Test
    public void testJoinWithBothVirtualRows() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        SysUser vUser = new SysUser();
        vUser.setUserId(99L);
        vUser.setUsername("virtual_user");
        vUser.setEnabled(true);
        em.merge(vUser);

        HrEmployee vEmp = new HrEmployee();
        vEmp.setEmployeeId(9999L);
        vEmp.setSysTenantId(10);
        vEmp.setSysUser(vUser);
        em.merge(vEmp);

        em.flush();
        em.clear();

        List<Object[]> results = em.createQuery("SELECT e.employeeId, u.username FROM HrEmployee e JOIN e.sysUser u WHERE e.employeeId = 9999")
                                   .getResultList();

        assertEquals(1, results.size());
        assertEquals(9999L, results.get(0)[0]);
        assertEquals("virtual_user", results.get(0)[1]);

        em.getTransaction().rollback();
        em.close();
    }

    @Test
    public void testFilterReevaluation_PatchToExclude() {
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
    }

    @Test
    public void testFilterReevaluation_PatchToInclude() {
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
    }

    @Test
    public void testLeftJoin_PhysicalPrimary_VirtualJoined() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Employee 102 exists physically but its User 2 is disabled.
        // Let's create a NEW virtual user and link Employee 102 to it in proxy.
        SysUser vUser = new SysUser();
        vUser.setUserId(888L);
        vUser.setUsername("new_virtual_user");
        vUser.setEnabled(true);
        em.merge(vUser);

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
    }

    @Test
    public void testOrderBy_VirtualInterleaving() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Physical OrgUsers: 1 (org_user_1), 2 (org_user_2)
        // Insert virtual OrgUser: 1.5 (org_user_1.5)
        OrgUser vUser = new OrgUser();
        vUser.setUserID(15L);
        vUser.setUserName("org_user_1.5");
        vUser.setOrganizationID(100);
        em.merge(vUser);
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
    }
}
