package com.unscathed.monitor.telemetry

/** Packets used across the telemetry tests, based on the documented schema. */
internal object TelemetryFixtures {
    fun state(sequence: Long = 1253, inGame: Boolean = true, schemaVersion: String = "1"): String = """
        {
          "schemaVersion": $schemaVersion,
          "sequence": $sequence,
          "serverTime": 1789324000.12,
          "player": { "inGame": $inGame, "health": 100 },
          "glider": { "ready": true, "remaining": 0, "cooldownEndsAt": 1789324000 },
          "innkeeper": {
            "rotationId": 1789322400,
            "stockLoaded": true,
            "stock": { "FreshWater": 1, "ToughHunkOfBread": 6 }
          },
          "darkArts": { "active": false },
          "weather": { "name": "Normal", "timeLeft": 480 }
        }
    """.trimIndent()
}
