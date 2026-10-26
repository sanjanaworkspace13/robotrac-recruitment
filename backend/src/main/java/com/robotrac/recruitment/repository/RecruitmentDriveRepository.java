package com.robotrac.recruitment.repository;

import com.robotrac.recruitment.domain.DriveStatus;
import com.robotrac.recruitment.domain.RecruitmentDrive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface RecruitmentDriveRepository extends JpaRepository<RecruitmentDrive, Long> {

    /** Codes are stored upper-case. */
    Optional<RecruitmentDrive> findByCode(String code);

    boolean existsByCode(String code);

    long countByStatus(DriveStatus status);

    List<RecruitmentDrive> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RecruitmentDrive d where d.id = :id")
    Optional<RecruitmentDrive> findByIdForUpdate(@Param("id") Long id);
}
