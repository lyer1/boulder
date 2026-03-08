package com.crag.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "sys_user")
public class SysUser {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long userId;

    private String username;
    private boolean enabled;

    @OneToMany(mappedBy = "sysUser", cascade = CascadeType.ALL)
    private java.util.List<UserAddress> addresses;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public java.util.List<UserAddress> getAddresses() { return addresses; }
    public void setAddresses(java.util.List<UserAddress> addresses) { this.addresses = addresses; }
}
