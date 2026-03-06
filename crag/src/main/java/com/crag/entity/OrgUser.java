package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "org_user")
public class OrgUser {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long userID;

    private String userName;
    private Integer organizationID;

    public Long getUserID() { return userID; }
    public void setUserID(Long userID) { this.userID = userID; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public Integer getOrganizationID() { return organizationID; }
    public void setOrganizationID(Integer organizationID) { this.organizationID = organizationID; }
}
