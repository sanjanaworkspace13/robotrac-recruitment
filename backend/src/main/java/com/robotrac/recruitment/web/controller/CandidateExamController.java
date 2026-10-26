package com.robotrac.recruitment.web.controller;

import com.robotrac.recruitment.security.ClientInfo;
import com.robotrac.recruitment.security.CurrentUser;
import com.robotrac.recruitment.service.ExamService;
import com.robotrac.recruitment.service.MonitoringService;
import com.robotrac.recruitment.web.dto.ExamDtos.AttemptStateDto;
import com.robotrac.recruitment.web.dto.ExamDtos.ClockDto;
import com.robotrac.recruitment.web.dto.ExamDtos.InstructionsDto;
import com.robotrac.recruitment.web.dto.ExamDtos.MonitoringAckDto;
import com.robotrac.recruitment.web.dto.ExamDtos.MonitoringEventRequest;
import com.robotrac.recruitment.web.dto.ExamDtos.QuestionStateDto;
import com.robotrac.recruitment.web.dto.ExamDtos.ReviewRequest;
import com.robotrac.recruitment.web.dto.ExamDtos.SaveResponseRequest;
import com.robotrac.recruitment.web.dto.ExamDtos.SeqRequest;
import com.robotrac.recruitment.web.dto.ExamDtos.SubmissionDto;
import com.robotrac.recruitment.web.dto.ExamDtos.SubmitRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Candidate examination API (ROLE_CANDIDATE only). There are NO attempt or candidate ids in these URLs:
 * the server always works on the logged-in candidate's own attempt for their own drive.
 */
@RestController
@RequestMapping("/api/candidate")
public class CandidateExamController {

    private final ExamService exam;
    private final MonitoringService monitoring;

    public CandidateExamController(ExamService exam, MonitoringService monitoring) {
        this.exam = exam;
        this.monitoring = monitoring;
    }

    @GetMapping("/instructions")
    public InstructionsDto instructions() {
        return exam.instructions(CurrentUser.candidate());
    }

    @PostMapping("/attempt/start")
    public AttemptStateDto start(HttpServletRequest request) {
        return exam.start(CurrentUser.candidate(), ClientInfo.ip(request));
    }

    @GetMapping("/attempt")
    public AttemptStateDto state() {
        return exam.state(CurrentUser.candidate());
    }

    @GetMapping("/attempt/clock")
    public ClockDto clock() {
        return exam.clock(CurrentUser.candidate());
    }

    @PutMapping("/attempt/questions/{number}/response")
    public QuestionStateDto save(@PathVariable @Min(1) @Max(500) int number, @Valid @RequestBody SaveResponseRequest req) {
        return exam.saveAnswer(CurrentUser.candidate(), number, req.selectedIndex(), req.markedForReview(), req.seq());
    }

    @PostMapping("/attempt/questions/{number}/clear")
    public QuestionStateDto clear(@PathVariable @Min(1) @Max(500) int number, @Valid @RequestBody SeqRequest req) {
        return exam.clearAnswer(CurrentUser.candidate(), number, req.seq());
    }

    @PostMapping("/attempt/questions/{number}/review")
    public QuestionStateDto review(@PathVariable @Min(1) @Max(500) int number, @Valid @RequestBody ReviewRequest req) {
        return exam.setReview(CurrentUser.candidate(), number, req.markedForReview(), req.seq());
    }

    @PostMapping("/attempt/questions/{number}/visit")
    public QuestionStateDto visit(@PathVariable @Min(1) @Max(500) int number) {
        return exam.visit(CurrentUser.candidate(), number);
    }

    @PostMapping("/attempt/events")
    public MonitoringAckDto event(@Valid @RequestBody MonitoringEventRequest req) {
        return monitoring.recordClientEvent(CurrentUser.candidate(), req);
    }

    @PostMapping("/attempt/submit")
    public SubmissionDto submit(@Valid @RequestBody(required = false) SubmitRequest req, HttpServletRequest request) {
        return exam.submit(CurrentUser.candidate(), req == null ? "MANUAL" : req.reason(), ClientInfo.ip(request));
    }
}
