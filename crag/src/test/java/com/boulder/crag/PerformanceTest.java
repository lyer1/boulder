package com.boulder.crag;

import com.crag.entity.HrEmployee;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.List;
import java.io.FileWriter;
import java.io.IOException;

public class PerformanceTest {

    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    public void setup() {
        emf = Persistence.createEntityManagerFactory("crag-pu");
        em = emf.createEntityManager();
        em.getTransaction().begin();
        for (int i = 0; i < 100; i++) {
            HrEmployee emp = new HrEmployee();
            emp.setSysTenantId(999);
            em.persist(emp);
        }
        em.getTransaction().commit();
    }

    @AfterEach
    public void teardown() {
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        em.close();
        emf.close();
        com.boulder.state.ChalkBag.clear();
    }

    @Test
    public void testCleanReadPerformance() throws IOException {
        long start = System.currentTimeMillis();
        em.getTransaction().begin();
        for (int i = 0; i < 100; i++) {
            List<HrEmployee> emps = em.createQuery("SELECT e FROM HrEmployee e WHERE e.sysTenantId = 999", HrEmployee.class).getResultList();
        }
        em.getTransaction().commit();
        long end = System.currentTimeMillis();
        long time = end - start;
        System.out.println("Clean Read Performance: " + time + "ms");
        appendMetric("Clean Read Performance (Bypass Enabled)", time);
    }

    @Test
    public void testDirtyReadPerformance() throws IOException {
        em.getTransaction().begin();
        HrEmployee emp = new HrEmployee();
        emp.setSysTenantId(999);
        em.persist(emp);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            List<HrEmployee> emps = em.createQuery("SELECT e FROM HrEmployee e WHERE e.sysTenantId = 999", HrEmployee.class).getResultList();
        }
        em.getTransaction().commit();
        long end = System.currentTimeMillis();
        long time = end - start;
        System.out.println("Dirty Read Performance: " + time + "ms");
        appendMetric("Dirty Read Performance (Federation Enabled)", time);
    }

    private void appendMetric(String testName, long timeMs) throws IOException {
        try (FileWriter fw = new FileWriter("performance_metrics.txt", true)) {
            fw.write(testName + ": " + timeMs + " ms\n");
        }
    }
}
