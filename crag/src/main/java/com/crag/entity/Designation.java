package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "designation")
public class Designation {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long designationID;
    private String designationName;

    public Long getDesignationID() { return designationID; }
    public void setDesignationID(Long designationID) { this.designationID = designationID; }
    public String getDesignationName() { return designationName; }
    public void setDesignationName(String designationName) { this.designationName = designationName; }
}
