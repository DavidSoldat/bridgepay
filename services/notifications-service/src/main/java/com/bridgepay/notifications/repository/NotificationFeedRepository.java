package com.bridgepay.notifications.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The feed folds rows that share a group_key (installments paid by one Paddle transaction) into one entry.
 * Rows without a group_key are their own group (keyed by their id). Paging is over groups.
 */
@Repository
public class NotificationFeedRepository {

    private static final String GROUPS = """
            select coalesce(group_key, id::text) as gk, max(sent_at) as latest_at, count(*) as cnt,
                   min(sequence_number) as min_seq, max(sequence_number) as max_seq, sum(amount) as total
            from notifications.notification_log
            where applicant_id = :applicantId
            group by coalesce(group_key, id::text)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationFeedRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest row of a group, plus the group's aggregates. */
    public record FeedRow(UUID id, String type, String title, String body, UUID applicationId, Instant createdAt,
                          int count, Integer minSequence, Integer maxSequence, BigDecimal totalAmount) {
    }

    public List<FeedRow> groups(UUID applicantId, int limit, long offset) {
        String sql = "select n.id, n.type, n.title, n.body, n.application_id, g.latest_at, g.cnt, g.min_seq, g.max_seq, g.total "
                + "from (" + GROUPS + ") g "
                + "join lateral (select x.* from notifications.notification_log x "
                + "  where x.applicant_id = :applicantId and coalesce(x.group_key, x.id::text) = g.gk "
                + "  order by x.sent_at desc, x.id desc limit 1) n on true "
                + "order by g.latest_at desc, n.id desc "
                + "limit :limit offset :offset";
        return jdbc.query(sql, Map.of("applicantId", applicantId, "limit", limit, "offset", offset), (rs, i) -> new FeedRow(
                rs.getObject("id", UUID.class),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getObject("application_id", UUID.class),
                rs.getObject("latest_at", OffsetDateTime.class).toInstant(),
                rs.getInt("cnt"),
                rs.getObject("min_seq", Integer.class),
                rs.getObject("max_seq", Integer.class),
                rs.getBigDecimal("total")));
    }

    public long unreadGroups(UUID applicantId, Instant readThrough) {
        Long count = jdbc.queryForObject("select count(*) from (" + GROUPS + ") g where g.latest_at > :readThrough",
                Map.of("applicantId", applicantId, "readThrough", OffsetDateTime.ofInstant(readThrough, ZoneOffset.UTC)),
                Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Instant> readThrough(UUID applicantId) {
        return jdbc.query("select read_through from notifications.notification_reads where applicant_id = :applicantId",
                        Map.of("applicantId", applicantId), (rs, i) -> rs.getObject("read_through", OffsetDateTime.class).toInstant())
                .stream().findFirst();
    }

    /** Upsert; read_through only ever moves forward. */
    public void markRead(UUID applicantId, Instant at) {
        jdbc.update("""
                insert into notifications.notification_reads (applicant_id, read_through) values (:applicantId, :at)
                on conflict (applicant_id) do update
                  set read_through = greatest(notification_reads.read_through, excluded.read_through)""",
                Map.of("applicantId", applicantId, "at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)));
    }
}
