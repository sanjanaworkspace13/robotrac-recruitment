package com.robotrac.recruitment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One exam-integrity event. {@code serverTime} is authoritative; {@code clientTime} is what the browser
 * claimed and is kept only for comparison. Counts are always derived by counting rows - never accepted
 * from the browser.
 */
@Entity
@Table(name = "monitoring_event")
public class MonitoringEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private ExamAttempt attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private MonitoringEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_source", nullable = false, length = 10)
    private EventSource source;

    @Column(name = "server_time", nullable = false)
    private Instant serverTime;

    @Column(name = "client_time")
    private Instant clientTime;

    @Column(name = "client_event_id", length = 64)
    private String clientEventId;

    @Column(length = 255)
    private String detail;

    protected MonitoringEvent() {
    }

    public MonitoringEvent(ExamAttempt attempt, MonitoringEventType eventType, EventSource source,
                           Instant serverTime, Instant clientTime, String clientEventId, String detail) {
        this.attempt = attempt;
        this.eventType = eventType;
        this.source = source;
        this.serverTime = serverTime;
        this.clientTime = clientTime;
        this.clientEventId = clientEventId;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public ExamAttempt getAttempt() { return attempt; }
    public MonitoringEventType getEventType() { return eventType; }
    public EventSource getSource() { return source; }
    public Instant getServerTime() { return serverTime; }
    public Instant getClientTime() { return clientTime; }
    public String getClientEventId() { return clientEventId; }
    public String getDetail() { return detail; }
}
