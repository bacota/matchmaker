FLYWAY_URL=jdbc:postgresql://localhost:5432/matchmaker \
FLYWAY_USER=matchmaker FLYWAY_PASSWORD=matchmaker \
mill --ticker false matchmaker.flyway.runMain com.vivi.matchmaker.flyway.Migrate
