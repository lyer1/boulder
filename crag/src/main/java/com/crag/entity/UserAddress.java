package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "user_address")
public class UserAddress {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long addressId;
    private String city;
    private String state;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private SysUser sysUser;

    public Long getAddressId() { return addressId; }
    public void setAddressId(Long addressId) { this.addressId = addressId; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public SysUser getSysUser() { return sysUser; }
    public void setSysUser(SysUser sysUser) { this.sysUser = sysUser; }
}
