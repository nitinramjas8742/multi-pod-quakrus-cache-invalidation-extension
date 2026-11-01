package com.example;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;

// Same as Agency: READ_WRITE is served by the extension's Redis
// version/epoch invalidation rather than Hibernate's per-pod soft locks.
@Entity
@Cacheable
@NaturalIdCache
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Agent extends PanacheEntity {

    @NaturalId
    @Column(name = "agent_code", nullable = false, unique = true, length = 50)
    public String agentCode;

    @Column(nullable = false, length = 150)
    public String name;

    @Column(length = 100)
    public String email;
}
