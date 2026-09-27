package com.josephinealinea.planner.flights;

public final class MultiLegFixtures {
    private MultiLegFixtures() {}

    /** AV 105 on 2026-10-31, trimmed from the real answer: Bogota to Cusco, then Cusco to La Paz. */
    public static final String AV105 = """
            [{"departure":{"airport":{"icao":"SKBO","iata":"BOG","name":"Bogota","timeZone":"America/Bogota"},
                "scheduledTime":{"utc":"2026-10-31 13:00Z","local":"2026-10-31 08:00-05:00"}},
              "arrival":{"airport":{"icao":"SPZO","iata":"CUZ","name":"Cusco","timeZone":"America/Lima"},
                "scheduledTime":{"utc":"2026-10-31 16:25Z","local":"2026-10-31 11:25-05:00"}},
              "number":"AV 105","status":"Expected"},
             {"departure":{"airport":{"icao":"SPZO","iata":"CUZ","name":"Cusco","timeZone":"America/Lima"},
                "scheduledTime":{"utc":"2026-10-31 17:30Z","local":"2026-10-31 12:30-05:00"}},
              "arrival":{"airport":{"icao":"SLLP","iata":"LPB","name":"La Paz","timeZone":"America/La_Paz"},
                "scheduledTime":{"utc":"2026-10-31 18:45Z","local":"2026-10-31 14:45-04:00"}},
              "number":"AV 105","status":"Expected"}]
            """;
}
