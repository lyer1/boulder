package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "hr_employee")
public class HrEmployee {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long employeeId;

    private Integer sysTenantId;
    private Integer hrOrganizationId;

    @ManyToOne
    @JoinColumn(name = "designation_id")
    private Designation designation;

    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id")
    private SysUser sysUser;

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }

    public Integer getSysTenantId() { return sysTenantId; }
    public void setSysTenantId(Integer sysTenantId) { this.sysTenantId = sysTenantId; }

    public Integer getHrOrganizationId() { return hrOrganizationId; }
    public void setHrOrganizationId(Integer hrOrganizationId) { this.hrOrganizationId = hrOrganizationId; }

    public Designation getDesignation() { return designation; }
    public void setDesignation(Designation designation) { this.designation = designation; }

    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }

    public SysUser getSysUser() { return sysUser; }
    public void setSysUser(SysUser sysUser) { this.sysUser = sysUser; }
}
