package com.robotrac.recruitment.security;

import java.io.Serializable;

/**
 * Identity of a logged-in candidate, stored in the server-side session. Every candidate endpoint derives the
 * candidate and drive from this object - never from IDs sent by the browser - so one candidate cannot reach
 * another candidate's attempt.
 */
public record CandidatePrincipal(Long candidateId, Long driveId, String email, String driveCode) implements Serializable {
}
