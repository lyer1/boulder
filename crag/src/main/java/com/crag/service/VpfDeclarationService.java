package com.crag.service;

import com.crag.ds.VpfDeclarationDS;
import com.crag.entity.VpfDeclaration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import java.util.List;

public class VpfDeclarationService {

    private final EntityManager em;
    private final VpfDeclarationDS ds;

    public VpfDeclarationService(EntityManager em) {
        this.em = em;
        this.ds = new VpfDeclarationDS(em);
    }

    public double performReadUpdateReadFlow(int empId, double newPercent) {
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();

            // Read 1
            List<VpfDeclaration> declarations = ds.getVpfDetailsHQL(empId);
            if (declarations.isEmpty()) {
                throw new RuntimeException("No declaration found for empId: " + empId);
            }

            VpfDeclaration decl = declarations.get(0);

            // Update
            // This will execute an UPDATE via Hibernate
            // Wait, standard update might not be intercepted well enough unless we use native query,
            // but the instructions said "intercepted at JDBC layer", so Hibernate's UPDATE statement should be intercepted.
            decl.setContributionPercent(newPercent);
            em.flush(); // Force UPDATE to hit the JDBC driver
            em.clear(); // Clear L1 cache to force the next read to hit the DB

            // Read 2 via Native Query
            List<Object[]> nativeResults = ds.getVpfDetailsNative(empId);
            if (nativeResults.isEmpty()) {
                throw new RuntimeException("No native result found");
            }

            Object[] row = nativeResults.get(0);
            // row[2] should be 'percent' based on "SELECT id, emp_id, percent, status"
            double updatedPercent = ((Number) row[2]).doubleValue();

            tx.commit();
            return updatedPercent;
        } catch (Exception e) {
            if (tx.isActive()) tx.rollback();
            throw e;
        }
    }
}
