package com.energy.monitoring.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SimulationProfileHouseholdMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        ensureHouseholdColumn();
        dropUserUniqueConstraints();
        retireGarageHouseholdType();
        backfillHouseholdLinks();
    }

    private void ensureHouseholdColumn() {
        jdbcTemplate.execute("""
                ALTER TABLE simulation_profiles
                ADD COLUMN IF NOT EXISTS household_id BIGINT
                """);
    }

    private void dropUserUniqueConstraints() {
        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT c.conname
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = current_schema()
                AND t.relname = 'simulation_profiles'
                AND c.contype = 'u'
                AND pg_get_constraintdef(c.oid) LIKE '%(user_id)%'
                """, String.class);

        for (String constraint : constraints) {
            jdbcTemplate.execute("ALTER TABLE simulation_profiles DROP CONSTRAINT IF EXISTS \"" + constraint + "\"");
            log.info("SimulationProfile migration: dropped unique constraint {}", constraint);
        }
    }

    private void backfillHouseholdLinks() {
        int updated = jdbcTemplate.update("""
                UPDATE simulation_profiles sp
                SET household_id = first_household.id
                FROM (
                    SELECT DISTINCT ON (user_id) id, user_id
                    FROM households
                    ORDER BY user_id, created_at ASC, id ASC
                ) first_household
                WHERE sp.user_id = first_household.user_id
                AND sp.household_id IS NULL
                """);

        if (updated > 0) {
            log.info("SimulationProfile migration: linked {} legacy profiles to households", updated);
        }
    }

    private void retireGarageHouseholdType() {
        int updated = jdbcTemplate.update("""
                UPDATE households
                SET type = 'OTHER'
                WHERE type = 'GARAGE'
                """);

        if (updated > 0) {
            log.info("SimulationProfile migration: converted {} garage groups to other", updated);
        }
    }
}
