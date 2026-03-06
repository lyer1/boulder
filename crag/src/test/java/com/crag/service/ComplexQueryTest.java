package com.crag.service;

import com.boulder.state.ChalkBag;
import com.crag.entity.HrEmployee;
import com.crag.entity.SysUser;
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
                stmt.execute("CREATE TABLE sys_user (userId BIGINT PRIMARY KEY, username VARCHAR(255), enabled BOOLEAN)");
                stmt.execute("CREATE TABLE hr_employee (employeeId BIGINT PRIMARY KEY, sysTenantId INT, hrOrganizationId INT, designationID INT, user_id BIGINT)");
                
                stmt.execute("INSERT INTO sys_user (userId, username, enabled) VALUES (1, 'user1', true)");
                stmt.execute("INSERT INTO hr_employee (employeeId, sysTenantId, hrOrganizationId, designationID, user_id) VALUES (101, 10, 1, 500, 1)");

                stmt.execute("INSERT INTO sys_user (userId, username, enabled) VALUES (2, 'user2', false)");
                stmt.execute("INSERT INTO hr_employee (employeeId, sysTenantId, hrOrganizationId, designationID, user_id) VALUES (102, 10, 1, 500, 2)");
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
    public void testHrEmployeeSysUserJoin_PhysicalOnly() {
        EntityManager em = emf.createEntityManager();
        TypedQuery<Long> query = em.createQuery(
            "SELECT e.employeeId FROM HrEmployee e JOIN e.sysUser u WHERE e.sysTenantId = :tenantId AND u.enabled = true", 
            Long.class
        );
        query.setParameter("tenantId", 10);
        List<Long> results = query.getResultList();
        assertEquals(1, results.size());
        assertEquals(101L, results.get(0));
        em.close();
    }

    @Test
    public void testHrEmployeeSysUserJoin_WithProxyPatch() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Enable physical User 2 in proxy
        SysUser user2 = em.find(SysUser.class, 2L);
        user2.setEnabled(true);
        em.flush();

        // Query: Should find 101 (physical) and 102 (patched in proxy)
        TypedQuery<Long> query = em.createQuery(
            "SELECT e.employeeId FROM HrEmployee e JOIN e.sysUser u WHERE e.sysTenantId = :tenantId AND u.enabled = true", 
            Long.class
        );
        query.setParameter("tenantId", 10);
        List<Long> results = query.getResultList();
        
        // This fails if proxy doesn't resolve SysUser PK from Join row
        assertEquals(2, results.size(), "Should find both 101 and 102 now that 102 is enabled in proxy");
        
        em.getTransaction().commit();
        em.close();
    }

    @Test
    public void testHrEmployeeSysUserJoin_WithProxyTombstone() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Delete physical User 1 in proxy
        SysUser user1 = em.find(SysUser.class, 1L);
        em.remove(user1);
        em.flush();

        TypedQuery<Long> query = em.createQuery(
            "SELECT e.employeeId FROM HrEmployee e JOIN e.sysUser u WHERE e.sysTenantId = :tenantId AND u.enabled = true", 
            Long.class
        );
        query.setParameter("tenantId", 10);
        List<Long> results = query.getResultList();
        
        // This fails if proxy doesn't see that physical row 101's joined User 1 is tombstoned
        assertEquals(0, results.size(), "Should find no enabled users since the only enabled one (101) was deleted in proxy");

        em.getTransaction().commit();
        em.close();
    }

    @Test
    public void testHrEmployeeSysUserJoin_WithProxyInsert() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        SysUser newUser = new SysUser();
        newUser.setUserId(3L);
        newUser.setUsername("user3");
        newUser.setEnabled(true);
        
        HrEmployee newEmp = new HrEmployee();
        newEmp.setEmployeeId(103L);
        newEmp.setSysTenantId(10);
        newEmp.setHrOrganizationId(1);
        newEmp.setDesignationID(500);
        newEmp.setSysUser(newUser);
        
        em.merge(newEmp);
        em.flush();

        TypedQuery<Long> query = em.createQuery(
            "SELECT e.employeeId FROM HrEmployee e JOIN e.sysUser u WHERE e.sysTenantId = :tenantId AND u.enabled = true", 
            Long.class
        );
        query.setParameter("tenantId", 10);
        List<Long> results = query.getResultList();
        
        assertEquals(2, results.size(), "Should find 101 (physical) and 103 (virtual)");
        assertTrue(results.contains(101L));
        assertTrue(results.contains(103L));

        em.getTransaction().commit();
        em.close();
    }

    @Test
    public void testProjectionJoin_Segment2() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Patch user 1's username in proxy
        SysUser u1 = em.find(SysUser.class, 1L);
        u1.setUsername("patched_user1");
        em.flush();

        // HQL Segment 2 variation: select e.employeeId, u.username ...
        Query query = em.createQuery(
            "SELECT e.employeeId, u.username FROM HrEmployee e JOIN e.sysUser u WHERE e.sysTenantId = 10"
        );
        List<Object[]> results = query.getResultList();
        
        boolean foundPatched = false;
        for (Object[] row : results) {
            if (row[0].equals(101L) && "patched_user1".equals(row[1])) {
                foundPatched = true;
            }
        }
        assertTrue(foundPatched, "Should see patched username in joined projection results");

        em.getTransaction().commit();
        em.close();
    }
}
