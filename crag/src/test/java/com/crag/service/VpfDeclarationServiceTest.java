package com.crag.service;

import com.boulder.state.ChalkBag;
import com.crag.ds.VpfDeclarationDS;
import com.crag.entity.VpfDeclaration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class VpfDeclarationServiceTest {

    private static EntityManagerFactory emf;

    @BeforeAll
    public static void setup() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS vpf_table (id INT AUTO_INCREMENT PRIMARY KEY, emp_id INT, percent DOUBLE, status VARCHAR(255))");
                stmt.execute("DELETE FROM vpf_table");
                stmt.execute("INSERT INTO vpf_table (id, emp_id, percent, status) VALUES (1, 1001, 12.0, 'ACTIVE')");
                stmt.execute("CREATE SEQUENCE IF NOT EXISTS hibernate_sequence START WITH 2");
            }
        }

        emf = Persistence.createEntityManagerFactory("crag-pu");
    }

    @AfterAll
    public static void teardown() {
        if (emf != null) {
            emf.close();
        }
    }

    @AfterEach
    public void cleanup() {
        ChalkBag.clear();
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM vpf_table");
                stmt.execute("INSERT INTO vpf_table (id, emp_id, percent, status) VALUES (1, 1001, 12.0, 'ACTIVE')");
            }
        } catch (Exception e) {}
    }

    @Test
    public void testReadUpdateReadFlow() throws Exception {
        EntityManager em = emf.createEntityManager();
        VpfDeclarationService service = new VpfDeclarationService(em);
        VpfDeclarationDS ds = new VpfDeclarationDS(em);

        int empId = 1001;

        em.getTransaction().begin();

        VpfDeclaration decl = ds.getVpfDetailsHQL(empId).get(0);
        assertEquals(12.0, decl.getContributionPercent(), "Initial value should be 12.0 from DB");

        decl.setContributionPercent(20.0);
        em.flush();
        em.clear();

        Object[] row = ds.getVpfDetailsNative(empId).get(0);
        double patchedPercent = ((Number) row[2]).doubleValue();
        assertEquals(20.0, patchedPercent, "Native Read should see the patched value of 20.0");

        em.getTransaction().commit();
        em.close();

        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT percent FROM vpf_table WHERE emp_id = 1001");
                rs.next();
                double physicalPercent = rs.getDouble(1);
                assertEquals(12.0, physicalPercent, "Physical DB must not be modified");
            }
        }
    }

    @Test
    public void testDeleteAndTombstone() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        Query q = em.createNativeQuery("DELETE FROM vpf_table WHERE id = 1");
        q.executeUpdate();

        em.flush();
        em.clear();

        VpfDeclarationDS ds = new VpfDeclarationDS(em);

        List<Object[]> rows = ds.getVpfDetailsNative(1001);

        int count = 0;
        for (Object[] row : rows) {
             if (((Number) row[1]).intValue() == 1001) count++;
        }
        assertEquals(0, count, "Deleted row should be tombstoned and skipped");

        em.getTransaction().commit();
        em.close();
    }

    @Test
    public void testPersistAndReadFlow() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // Testing true em.persist()
        VpfDeclaration newDecl = new VpfDeclaration();
        newDecl.setEmpId(3003);
        newDecl.setContributionPercent(18.0);
        newDecl.setStatus("NEW");

        em.persist(newDecl);
        em.flush();

        // Asserting the ID here since hibernate is supposed to extract it from the generated keys
        System.out.println("ID ASSIGNED: " + newDecl.getId());
        assertNotNull(newDecl.getId(), "Generated ID must be populated after persist and flush");
        assertTrue(newDecl.getId() > 0, "Generated ID must be valid");

        em.clear();

        // Use native query to fetch.
        VpfDeclarationDS ds = new VpfDeclarationDS(em);
        List<Object[]> rows = ds.getVpfDetailsNative(3003);

        assertEquals(1, rows.size(), "Should append the newly inserted virtual row");
        assertEquals(18.0, ((Number) rows.get(0)[2]).doubleValue());

        em.getTransaction().commit();
        em.close();

        // Verify physical database is untouched!
        try (Connection conn = DriverManager.getConnection("jdbc:h2:mem:cragdb;DB_CLOSE_DELAY=-1", "sa", "")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vpf_table WHERE emp_id = 3003");
                rs.next();
                int count = rs.getInt(1);
                assertEquals(0, count, "Physical DB must not be modified by insert");
            }
        }
    }
}
