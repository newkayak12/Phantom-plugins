-- SQL from JdbcTemplate strings in the project (TimeTableJdbcItemWriter, named lock repositories)
INSERT INTO timetable (
                id, restaurant_id, date, day,
                start_time, end_time, table_number, table_size,
                table_status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
SELECT GET_LOCK(?, 0);
SELECT RELEASE_LOCK(?);
