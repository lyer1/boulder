package com.crag.ds;

import com.crag.entity.VpfDeclaration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.List;

public class VpfDeclarationDS {

    private EntityManager em;

    public VpfDeclarationDS(EntityManager em) {
        this.em = em;
    }

    public List<VpfDeclaration> getVpfDetailsHQL(int empId) {
        Query query = em.createQuery("SELECT v FROM VpfDeclaration v WHERE v.empId = :id", VpfDeclaration.class);
        query.setParameter("id", empId);
        return query.getResultList();
    }

    public List<Object[]> getVpfDetailsNative(int empId) {
        Query query = em.createNativeQuery("SELECT id, emp_id, percent, status FROM vpf_table WHERE emp_id = ?");
        query.setParameter(1, empId);
        return query.getResultList();
    }
}
