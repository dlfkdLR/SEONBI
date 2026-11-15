package com.seonbi.repository;

import com.seonbi.domain.TransferCode;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TransferCodeRepository extends JpaRepository<TransferCode, UUID> {
  /** 같은 코드를 두 기기가 동시에 쓰지 못하게 행을 잠그고 읽는다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from TransferCode t where t.codeHash = :hash")
  Optional<TransferCode> findByCodeHashForUpdate(@Param("hash") String hash);

  /** 새 코드를 만들 때 이 기기의 아직 안 쓴 옛 코드를 없앤다(코드는 기기당 하나만 유효). */
  @Modifying
  @Query("delete from TransferCode t where t.clientId = :clientId or t.expiresAt < :now")
  int deleteStale(@Param("clientId") UUID clientId, @Param("now") Instant now);
}
