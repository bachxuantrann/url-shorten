package com.auradev.url_shortener.repository;

import com.auradev.url_shortener.entity.BlockedDomain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Repository cho {@link BlockedDomain}.
 */
@Repository
public interface BlockedDomainRepository extends JpaRepository<BlockedDomain, Long> {

    boolean existsByDomain(String domain);

    /**
     * Trong số các domain ứng viên, trả về những domain nằm trong blocklist.
     * Một query duy nhất cho mọi ứng viên (host và các domain cha của nó).
     */
    @Query("SELECT b.domain FROM BlockedDomain b WHERE b.domain IN :domains")
    List<String> findBlockedDomainsIn(@Param("domains") Collection<String> domains);

    /** Tìm theo một phần tên domain (Spring Data tự escape ký tự đại diện). */
    Page<BlockedDomain> findByDomainContainingIgnoreCase(String keyword, Pageable pageable);
}
