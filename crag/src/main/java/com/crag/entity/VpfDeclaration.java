package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vpf_table")
public class VpfDeclaration {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int id;

    @Column(name = "emp_id")
    private int empId;

    @Column(name = "percent")
    private double contributionPercent;

    @Column(name = "status")
    private String status;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getEmpId() { return empId; }
    public void setEmpId(int empId) { this.empId = empId; }

    public double getContributionPercent() { return contributionPercent; }
    public void setContributionPercent(double contributionPercent) { this.contributionPercent = contributionPercent; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
