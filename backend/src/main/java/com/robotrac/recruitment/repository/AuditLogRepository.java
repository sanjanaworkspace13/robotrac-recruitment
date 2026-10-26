package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("select l from AuditLog l where (:action is null or l.action = :action) "
            + "and (:q is null or lower(l.actorName) like :q or lower(l.details) like :q or lower(l.entityId) like :q)")
    Page<AuditLog> search(@Param("action") String action, @Param("q") String q, Pageable pageable);
}
