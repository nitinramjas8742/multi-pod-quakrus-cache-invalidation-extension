package com.example;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.NaturalId;
import org.hibernate.annotations.NaturalIdCache;

import java.util.ArrayList;
import java.util.List;

// READ_WRITE is served by the extension's own region: Hibernate's stock
// read-write access needs a shared soft-lock store, which a per-pod value
// cache cannot provide. The extension maps READ_WRITE regions onto the Redis
// version/epoch protocol (loads through putFromLoad, invalidations through
// removeFromCache), so writes on any pod are visible on the next read.
@Entity
@Cacheable
@NaturalIdCache
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Agency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @NaturalId
    @Column(name = "agency_code", nullable = false, unique = true, length = 50)
    public String agencyCode;

    @Column(nullable = false, length = 150)
    public String name;

    @Column(length = 100)
    public String email;

    @Column(length = 30)
    public String status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "agency_agent_codes", joinColumns = @JoinColumn(name = "agency_id"))
    @Column(name = "agent_code", length = 50)
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    @BatchSize(size = 20)
    public List<String> agentCodes = new ArrayList<>();
}
