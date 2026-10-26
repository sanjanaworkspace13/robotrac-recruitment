package com.robotrac.recruitment.service;

import com.robotrac.recruitment.domain.ActorType;
import com.robotrac.recruitment.domain.AuditLog;
import com.robotrac.recruitment.repository.AuditLogRepository;
import com.robotrac.recruitment.security.AdminPrincipal;
import com.robotrac.recruitment.web.dto.PageResponse;
import com.robotrac.recruitment.web.dto.ResultDtos.AuditLogDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Locale;

/**
 * Writes the audit trail in its OWN transaction so that failed actions - e.g. rejected logins - are still
 * recorded even when the surrounding operation rolls back, and so that an audit failure never breaks the
 * business operation (it is logged for operators instead).
 * Callers must never pass passwords, tokens or answer keys in {@code details}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final Clock clock;
    private final TransactionTemplate newTransaction;

    public AuditService(AuditLogRepository repository, Clock clock, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        this.newTransaction = new TransactionTemplate(txManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(ActorType actorType, Long actorId, String actorName, String action,
                       String entityType, Object entityId, String details, String ip) {
        AuditLog entry = new AuditLog(clock.instant(), actorType, actorId, truncate(actorName, 254), action,
                entityType, entityId == null ? null : truncate(entityId.toString(), 60),
                truncate(details, 4000), truncate(ip, 64));
        try {
            newTransaction.executeWithoutResult(status -> repository.save(entry));
        } catch (RuntimeException e) {
            log.error("Could not write audit entry {}: {}", action, e.getMessage());
        }
    }

    public void admin(AdminPrincipal admin, String action, String entityType, Object entityId, String details, String ip) {
        record(ActorType.ADMIN, admin.adminId(), admin.username(), action, entityType, entityId, details, ip);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogDto> search(String action, String q, int page, int size) {
        String like = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        String act = (action == null || action.isBlank()) ? null : action.trim();
        return PageResponse.of(repository.search(act, like,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                                Sort.by(Sort.Direction.DESC, "createdAt", "id"))),
                l -> new AuditLogDto(l.getId(), l.getCreatedAt(), l.getActorType().name(), l.getActorName(),
                        l.getAction(), l.getEntityType(), l.getEntityId(), l.getDetails(), l.getIpAddress()));
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }
}
