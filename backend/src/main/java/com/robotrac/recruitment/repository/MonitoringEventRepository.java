package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.MonitoringEvent;
import com.robotrac.recruitment.domain.MonitoringEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MonitoringEventRepository extends JpaRepository<MonitoringEvent, Long> {

    boolean existsByAttemptIdAndClientEventId(Long attemptId, String clientEventId);

    Optional<MonitoringEvent> findFirstByAttemptIdOrderByServerTimeDescIdDesc(Long attemptId);

    long countByAttemptIdAndEventTypeIn(Long attemptId, Collection<MonitoringEventType> types);

    List<MonitoringEvent> findByAttemptIdOrderByServerTimeAscIdAsc(Long attemptId);

    /** [eventType, count] for one attempt. */
    @Query("select e.eventType, count(e) from MonitoringEvent e where e.attempt.id = :attemptId group by e.eventType")
    List<Object[]> countByType(@Param("attemptId") Long attemptId);
}
