package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.AuthorizationStatus;
import com.robotrac.recruitment.domain.DriveAuthorization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface DriveAuthorizationRepository extends JpaRepository<DriveAuthorization, Long> {

    Optional<DriveAuthorization> findByDriveIdAndCandidateId(Long driveId, Long candidateId);

    @Query("select a from DriveAuthorization a join fetch a.candidate c where a.drive.id = :driveId and c.email = :email")
    Optional<DriveAuthorization> findByDriveIdAndEmail(@Param("driveId") Long driveId, @Param("email") String email);

    /** Row lock used to serialise "start attempt" requests of the same candidate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DriveAuthorization a where a.drive.id = :driveId and a.candidate.id = :candidateId")
    Optional<DriveAuthorization> findForUpdate(@Param("driveId") Long driveId, @Param("candidateId") Long candidateId);

    long countByStatus(AuthorizationStatus status);

    long countByDriveIdAndStatus(Long driveId, AuthorizationStatus status);

    @Query(value = "select a from DriveAuthorization a join fetch a.candidate c where a.drive.id = :driveId "
            + "and (:q is null or c.email like :q or lower(c.fullName) like :q)",
            countQuery = "select count(a) from DriveAuthorization a join a.candidate c where a.drive.id = :driveId "
                    + "and (:q is null or c.email like :q or lower(c.fullName) like :q)")
    Page<DriveAuthorization> search(@Param("driveId") Long driveId, @Param("q") String q, Pageable pageable);

    /** [driveId, count] of AUTHORIZED rows per drive. */
    @Query("select a.drive.id, count(a) from DriveAuthorization a where a.status = :status group by a.drive.id")
    List<Object[]> countByDrive(@Param("status") AuthorizationStatus status);
}
