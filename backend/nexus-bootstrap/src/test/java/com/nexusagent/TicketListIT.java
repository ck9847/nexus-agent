package com.nexusagent;

import com.nexusagent.identity.api.BootstrapTenantRequest;
import com.nexusagent.identity.api.BootstrapTenantResponse;
import com.nexusagent.identity.api.LoginRequest;
import com.nexusagent.identity.api.LoginResponse;
import com.nexusagent.ticket.api.CreateTicketRequest;
import com.nexusagent.ticket.api.CreateTicketResponse;
import com.nexusagent.ticket.api.TicketListResponse;
import com.nexusagent.ticket.domain.TicketPriority;
import com.nexusagent.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = """
                nexus.security.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
                """
)
class TicketListIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4.11")
                    .withDatabaseName("nexus_agent")
                    .withUsername("nexus_app")
                    .withPassword(
                            "integration-test-password"
                    );

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPageFilterAndIsolateTickets() {
        String password = "StrongPassword123!";

        bootstrapTenant(
                "ticket-list-owner",
                "owner-list-admin@integration.example",
                password
        );

        bootstrapTenant(
                "ticket-list-outsider",
                "outsider-list-admin@integration.example",
                password
        );

        LoginResponse ownerLogin = login(
                "ticket-list-owner",
                password
        );

        LoginResponse outsiderLogin = login(
                "ticket-list-outsider",
                password
        );

        HttpHeaders ownerHeaders =
                bearerHeaders(ownerLogin);

        HttpHeaders outsiderHeaders =
                bearerHeaders(outsiderLogin);

        CreateTicketResponse first = createTicket(
                ownerHeaders,
                "Owner high ticket one",
                TicketPriority.HIGH
        );

        CreateTicketResponse second = createTicket(
                ownerHeaders,
                "Owner low ticket",
                TicketPriority.LOW
        );

        CreateTicketResponse third = createTicket(
                ownerHeaders,
                "Owner high in-progress ticket",
                TicketPriority.HIGH
        );

        CreateTicketResponse fourth = createTicket(
                ownerHeaders,
                "Owner urgent ticket",
                TicketPriority.URGENT
        );

        CreateTicketResponse outsider = createTicket(
                outsiderHeaders,
                "Outsider newest ticket",
                TicketPriority.HIGH
        );

        Instant firstTime = Instant.parse(
                "2026-08-08T02:00:00Z"
        );
        Instant secondTime = Instant.parse(
                "2026-08-08T02:00:00Z"
        );
        Instant thirdTime = Instant.parse(
                "2026-08-08T03:00:00Z"
        );
        Instant fourthTime = Instant.parse(
                "2026-08-08T01:00:00Z"
        );
        Instant outsiderTime = Instant.parse(
                "2026-08-08T04:00:00Z"
        );

        updateTicketState(
                first,
                TicketStatus.OPEN,
                firstTime
        );

        updateTicketState(
                second,
                TicketStatus.OPEN,
                secondTime
        );

        updateTicketState(
                third,
                TicketStatus.IN_PROGRESS,
                thirdTime
        );

        updateTicketState(
                fourth,
                TicketStatus.OPEN,
                fourthTime
        );

        updateTicketState(
                outsider,
                TicketStatus.OPEN,
                outsiderTime
        );

        /*
         * Expected owner order:
         *
         * third  - 03:00
         * second - 02:00, larger Snowflake ID
         * first  - 02:00, smaller Snowflake ID
         * fourth - 01:00
         *
         * The outsider ticket is newer than all owner
         * tickets, but must never appear.
         */
        TicketListResponse firstPage = requireBody(
                list(
                        ownerHeaders,
                        "/api/v1/tickets?limit=2"
                ),
                HttpStatus.OK
        );

        assertAll(
                () -> assertEquals(
                        List.of(
                                third.ticketId(),
                                second.ticketId()
                        ),
                        ticketIds(firstPage)
                ),
                () -> assertTrue(
                        firstPage.hasMore()
                ),
                () -> assertNotNull(
                        firstPage.nextCursor()
                )
        );

        TicketListResponse secondPage = requireBody(
                list(
                        ownerHeaders,
                        "/api/v1/tickets?limit=2"
                                + "&cursor="
                                + firstPage.nextCursor()
                ),
                HttpStatus.OK
        );

        assertAll(
                () -> assertEquals(
                        List.of(
                                first.ticketId(),
                                fourth.ticketId()
                        ),
                        ticketIds(secondPage)
                ),
                () -> assertFalse(
                        secondPage.hasMore()
                ),
                () -> assertNull(
                        secondPage.nextCursor()
                )
        );

        List<String> allOwnerTicketIds =
                new ArrayList<>();

        allOwnerTicketIds.addAll(
                ticketIds(firstPage)
        );
        allOwnerTicketIds.addAll(
                ticketIds(secondPage)
        );

        assertAll(
                () -> assertEquals(
                        List.of(
                                third.ticketId(),
                                second.ticketId(),
                                first.ticketId(),
                                fourth.ticketId()
                        ),
                        allOwnerTicketIds
                ),
                () -> assertEquals(
                        4,
                        new HashSet<>(
                                allOwnerTicketIds
                        ).size()
                ),
                () -> assertFalse(
                        allOwnerTicketIds.contains(
                                outsider.ticketId()
                        )
                )
        );

        TicketListResponse statusFiltered =
                requireBody(
                        list(
                                ownerHeaders,
                                "/api/v1/tickets"
                                        + "?status=IN_PROGRESS"
                                        + "&limit=10"
                        ),
                        HttpStatus.OK
                );

        assertEquals(
                List.of(third.ticketId()),
                ticketIds(statusFiltered)
        );

        TicketListResponse priorityFiltered =
                requireBody(
                        list(
                                ownerHeaders,
                                "/api/v1/tickets"
                                        + "?priority=HIGH"
                                        + "&limit=10"
                        ),
                        HttpStatus.OK
                );

        assertEquals(
                List.of(
                        third.ticketId(),
                        first.ticketId()
                ),
                ticketIds(priorityFiltered)
        );

        TicketListResponse combinedFiltered =
                requireBody(
                        list(
                                ownerHeaders,
                                "/api/v1/tickets"
                                        + "?status=OPEN"
                                        + "&priority=HIGH"
                                        + "&limit=10"
                        ),
                        HttpStatus.OK
                );

        assertEquals(
                List.of(first.ticketId()),
                ticketIds(combinedFiltered)
        );

        ResponseEntity<String> invalidCursor =
                getText(
                        ownerHeaders,
                        "/api/v1/tickets"
                                + "?cursor=invalid-cursor"
                );

        ResponseEntity<String> invalidLimit =
                getText(
                        ownerHeaders,
                        "/api/v1/tickets?limit=101"
                );

        ResponseEntity<String> unauthorized =
                restTemplate.getForEntity(
                        "/api/v1/tickets",
                        String.class
                );

        assertAll(
                () -> assertEquals(
                        HttpStatus.BAD_REQUEST,
                        invalidCursor.getStatusCode()
                ),
                () -> assertNotNull(
                        invalidCursor.getBody()
                ),
                () -> assertTrue(
                        invalidCursor.getBody().contains(
                                "INVALID_TICKET_QUERY"
                        )
                ),
                () -> assertEquals(
                        HttpStatus.BAD_REQUEST,
                        invalidLimit.getStatusCode()
                ),
                () -> assertNotNull(
                        invalidLimit.getBody()
                ),
                () -> assertTrue(
                        invalidLimit.getBody().contains(
                                "INVALID_TICKET_QUERY"
                        )
                ),
                () -> assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        unauthorized.getStatusCode()
                )
        );

        Long queryIndexCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(DISTINCT index_name)
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE()
                          AND table_name = 'tickets'
                          AND index_name IN
                          (
                              'idx_tickets_tenant_created_id',
                              'idx_tickets_tenant_status_created_id',
                              'idx_tickets_tenant_priority_created_id',
                              'idx_tickets_tenant_status_priority_created_id'
                          )
                        """,
                        Long.class
                );

        assertEquals(
                4L,
                queryIndexCount
        );
    }

    private void bootstrapTenant(
            String tenantCode,
            String email,
            String password
    ) {
        ResponseEntity<BootstrapTenantResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/tenants/bootstrap",
                        new BootstrapTenantRequest(
                                tenantCode,
                                tenantCode,
                                "admin",
                                email,
                                password
                        ),
                        BootstrapTenantResponse.class
                );

        requireBody(
                response,
                HttpStatus.CREATED
        );
    }

    private LoginResponse login(
            String tenantCode,
            String password
    ) {
        ResponseEntity<LoginResponse> response =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new LoginRequest(
                                tenantCode,
                                "admin",
                                password
                        ),
                        LoginResponse.class
                );

        return requireBody(
                response,
                HttpStatus.OK
        );
    }

    private CreateTicketResponse createTicket(
            HttpHeaders headers,
            String title,
            TicketPriority priority
    ) {
        ResponseEntity<CreateTicketResponse> response =
                restTemplate.exchange(
                        "/api/v1/tickets",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateTicketRequest(
                                        title,
                                        "Integration test description",
                                        priority
                                ),
                                headers
                        ),
                        CreateTicketResponse.class
                );

        return requireBody(
                response,
                HttpStatus.CREATED
        );
    }

    private void updateTicketState(
            CreateTicketResponse ticket,
            TicketStatus status,
            Instant createdAt
    ) {
        int affectedRows = jdbcTemplate.update(
                """
                UPDATE tickets
                SET status = ?,
                    created_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                status.name(),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt),
                Long.parseLong(ticket.ticketId())
        );

        assertEquals(1, affectedRows);
    }

    private ResponseEntity<TicketListResponse> list(
            HttpHeaders headers,
            String path
    ) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                TicketListResponse.class
        );
    }

    private ResponseEntity<String> getText(
            HttpHeaders headers,
            String path
    ) {
        return restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private static List<String> ticketIds(
            TicketListResponse response
    ) {
        return response.items()
                .stream()
                .map(item -> item.ticketId())
                .toList();
    }

    private static <T> T requireBody(
            ResponseEntity<T> response,
            HttpStatus expectedStatus
    ) {
        assertEquals(
                expectedStatus,
                response.getStatusCode()
        );
        assertNotNull(response.getBody());

        return response.getBody();
    }

    private static HttpHeaders bearerHeaders(
            LoginResponse login
    ) {
        HttpHeaders headers = new HttpHeaders();

        headers.setBearerAuth(
                login.accessToken()
        );

        return headers;
    }
}