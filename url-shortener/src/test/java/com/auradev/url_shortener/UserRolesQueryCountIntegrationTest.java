package com.auradev.url_shortener;

import com.auradev.url_shortener.dto.response.PageResponse;
import com.auradev.url_shortener.dto.response.UserResponse;
import com.auradev.url_shortener.entity.User;
import com.auradev.url_shortener.enums.RoleEnum;
import com.auradev.url_shortener.repository.UserRepository;
import com.auradev.url_shortener.service.AdminService;
import com.auradev.url_shortener.support.TestcontainersConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.TestInstance;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Danh sách user của admin không được tải {@code roles} từng user một (N+1):
 * số câu SQL phải như nhau dù trang có 3 hay 40 user.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRolesQueryCountIntegrationTest {

    private static final int SMALL_PAGE = 3;
    private static final int LARGE_PAGE = 40;

    @Autowired
    private AdminService adminService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private String prefix;

    @BeforeAll
    void createUsers() {
        prefix = "nplus" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        for (int i = 0; i < LARGE_PAGE + 5; i++) {
            userRepository.save(User.builder()
                    .username(prefix + "_" + i)
                    .email(prefix + "_" + i + "@test.local")
                    .password("x")
                    // luân phiên 1 và 2 role để cả hai dạng đều có trong dữ liệu
                    .roles(i % 2 == 0 ? Set.of(RoleEnum.ROLE_USER) : Set.of(RoleEnum.ROLE_USER, RoleEnum.ROLE_ADMIN))
                    .build());
        }
    }

    @Test
    void unfilteredListingIssuesTheSameNumberOfStatementsForAnyPageSize() {
        Supplier<PageResponse<UserResponse>> small = () -> adminService.getAllUsers(null, null, null, page(SMALL_PAGE));
        Supplier<PageResponse<UserResponse>> large = () -> adminService.getAllUsers(null, null, null, page(LARGE_PAGE));

        long smallStatements = countStatements(small);
        long largeStatements = countStatements(large);

        assertThat(largeStatements).as("statements for %d users vs %d users", LARGE_PAGE, SMALL_PAGE)
                .isEqualTo(smallStatements);
        assertThat(largeStatements).isLessThanOrEqualTo(4);
    }

    @Test
    void filteredListingIssuesTheSameNumberOfStatementsForAnyPageSize() {
        Supplier<PageResponse<UserResponse>> small =
                () -> adminService.getAllUsers(prefix, null, RoleEnum.ROLE_USER, page(SMALL_PAGE));
        Supplier<PageResponse<UserResponse>> large =
                () -> adminService.getAllUsers(prefix, null, RoleEnum.ROLE_USER, page(LARGE_PAGE));

        long smallStatements = countStatements(small);
        long largeStatements = countStatements(large);

        assertThat(largeStatements).as("statements for %d users vs %d users", LARGE_PAGE, SMALL_PAGE)
                .isEqualTo(smallStatements);
        assertThat(largeStatements).isLessThanOrEqualTo(4);
    }

    @Test
    void everyListedUserStillCarriesItsRoles() {
        PageResponse<UserResponse> result = adminService.getAllUsers(prefix, null, null, page(LARGE_PAGE));

        assertThat(result.getContent()).hasSize(LARGE_PAGE);
        assertThat(result.getTotalElements()).isEqualTo(LARGE_PAGE + 5L);
        result.getContent().forEach(user -> {
            int index = Integer.parseInt(user.getUsername().substring(user.getUsername().lastIndexOf('_') + 1));
            Set<RoleEnum> expected = index % 2 == 0
                    ? Set.of(RoleEnum.ROLE_USER)
                    : Set.of(RoleEnum.ROLE_USER, RoleEnum.ROLE_ADMIN);
            assertThat(user.getRoles()).as(user.getUsername()).isEqualTo(expected);
        });
    }

    // ===========================
    //  HELPERS
    // ===========================

    private PageRequest page(int size) {
        return PageRequest.of(0, size, Sort.by("createdAt").descending());
    }

    /** Số câu SQL được chuẩn bị khi chạy {@code action}. */
    private long countStatements(Supplier<?> action) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            action.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
