-- Demo sales history for the demo merchant seeded by V2 (00000000-0000-7000-8000-000000000001).
-- Loaded only where SPRING_FLYWAY_LOCATIONS adds classpath:db/demo (root docker-compose, k3d overlays/local).
-- Repeatable: Flyway re-runs it whenever this file changes, so it removes its own rows first.
-- Marker: applicant ids 00000000-0000-7000-8000-0000000de000 .. de039. Ids are UUIDv4 (Postgres 16 has no v7).
-- Rows are flagged is_demo, which keeps them out of the ops queue; no MANUAL_REVIEW rows either. About 18% are
-- already-decided manual reviews (decided_by ops1 or ops2; ops2 exists only here, not in Keycloak).

DELETE FROM application.merchant_payouts p
USING application.applications a
WHERE p.application_id = a.id
  AND a.applicant_id::text LIKE '00000000-0000-7000-8000-0000000de0%';

DELETE FROM application.applications
WHERE applicant_id::text LIKE '00000000-0000-7000-8000-0000000de0%';

SELECT setseed(0.42);

DROP TABLE IF EXISTS demo_orders;

CREATE TEMP TABLE demo_orders AS
WITH days AS (
    SELECT d, current_date - d AS day FROM generate_series(0, 89) AS d
), per_day AS (
    -- ~2.1 orders a day: older days fewer (gentle growth), weekends half as busy
    SELECT day,
           floor((1.7 + 1.6 * (89 - d) / 89.0)
                 * CASE WHEN extract(isodow FROM day) >= 6 THEN 0.5 ELSE 1 END
                 + random())::int AS orders
    FROM days
), raw AS (
    SELECT gen_random_uuid() AS id,
           row_number() OVER (ORDER BY day, i) AS n,
           -- a time between 08:00 and 22:00 UTC, never in the future
           least((day + time '08:00' + random() * interval '14 hours') AT TIME ZONE 'UTC',
                 now() - interval '5 minutes') AS created_at,
           1 + floor(random() * 4)::int AS product,
           random() AS outcome,
           random() AS payout_roll,
           random() AS jitter,
           random() AS review_roll,
           random() AS review_time,
           random() AS reviewer_roll,
           random() AS note_roll
    FROM per_day, generate_series(1, orders) AS i
), routed AS (
    -- Orders from the last 20 hours are never routed to review: a review that recent could still be open,
    -- and demo rows can't sit in the queue.
    SELECT *,
           CASE WHEN outcome < 0.60 THEN 'MODEL_APPROVE'
                WHEN outcome < 0.78 AND created_at < now() - interval '20 hours' THEN 'REVIEW'
                WHEN outcome < 0.78 THEN 'MODEL_APPROVE'
                ELSE 'MODEL_DECLINE' END AS route
    FROM raw
), decided AS (
    SELECT *,
           route = 'MODEL_DECLINE' OR (route = 'REVIEW' AND review_roll >= 0.60) AS declined,
           CASE WHEN route = 'REVIEW'
                THEN created_at + interval '10 minutes' + review_time * interval '19 hours 50 minutes'
                ELSE created_at + interval '3 seconds' END AS decision_at
    FROM routed
)
SELECT id, n, created_at, decision_at, jitter, route, reviewer_roll, note_roll,
       (ARRAY[52.42, 69.74, 177.53, 214.34])[product] AS amount,
       (ARRAY[13.11, 17.44, 44.38, 53.59])[product] AS installment,
       CASE WHEN declined THEN 'DECLINED'
            WHEN payout_roll < 0.10 AND created_at < now() - interval '2 days' THEN 'CANCELLED'
            ELSE 'APPROVED' END AS status,
       CASE WHEN declined THEN NULL
            WHEN payout_roll < 0.10 AND created_at < now() - interval '2 days' THEN 'CANCELLED'
            WHEN payout_roll >= 0.85 AND created_at >= now() - interval '2 days' THEN 'PENDING'
            ELSE 'PAID' END AS payout_status
FROM decided;

INSERT INTO application.applications
    (id, applicant_id, merchant_id, amount, status, risk_score, score_factors, decision_at,
     installment_count, installment_amount, decision_source, decided_by, reviewer_note, is_demo,
     created_at, updated_at)
SELECT id,
       ('00000000-0000-7000-8000-0000000de0' || lpad((n % 40)::text, 2, '0'))::uuid,
       '00000000-0000-7000-8000-000000000001',
       amount,
       status,
       CASE route WHEN 'MODEL_DECLINE' THEN round((0.72 + jitter * 0.25)::numeric, 3)
                  WHEN 'REVIEW' THEN round((0.30 + jitter * 0.39)::numeric, 3)
                  ELSE round((0.05 + jitter * 0.22)::numeric, 3) END,
       '[]',
       decision_at,
       4,
       installment,
       CASE WHEN route = 'REVIEW' THEN 'OPS' ELSE 'MODEL' END,
       CASE WHEN route = 'REVIEW' THEN CASE WHEN reviewer_roll < 0.65 THEN 'ops1' ELSE 'ops2' END END,
       CASE WHEN route <> 'REVIEW' THEN NULL
            WHEN status = 'DECLINED' THEN (ARRAY['Recent late payments, amount too high',
                                                 'Debt ratio above policy for this amount',
                                                 'Thin file and high utilisation'])[1 + floor(note_roll * 3)::int]
            ELSE (ARRAY['Income verified against bureau',
                        'Good repayment history, small amount',
                        'Utilisation high but stable, approved'])[1 + floor(note_roll * 3)::int] END,
       true,
       created_at,
       decision_at
FROM demo_orders;

INSERT INTO application.merchant_payouts (id, application_id, merchant_id, amount, fee_amount, status, paid_at, created_at)
SELECT gen_random_uuid(), o.id, m.id, o.amount,
       round(o.amount * m.fee_rate_pct / 100, 2),
       o.payout_status,
       CASE WHEN o.payout_status = 'PAID' THEN least(o.decision_at + o.jitter * interval '20 hours', now()) END,
       o.decision_at
FROM demo_orders o
JOIN application.merchants m ON m.id = '00000000-0000-7000-8000-000000000001'
WHERE o.payout_status IS NOT NULL;

DROP TABLE demo_orders;
