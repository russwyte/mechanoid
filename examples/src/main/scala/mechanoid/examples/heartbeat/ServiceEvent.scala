package mechanoid.examples.heartbeat

import mechanoid.Finite

/** Service events for the health monitoring FSM.
  *
  * Events are categorized as:
  *   - Control: Start, Stop, ManualReset (user-initiated)
  *   - Timeout: Heartbeat, DegradedCheck (automatically fired by timeout sweeper)
  *   - Health Results: Healthy, Degraded, Critical (produced by health check effects)
  */
enum ServiceEvent derives Finite:
  // Control events (user-initiated)
  case Start
  case Stop
  case ManualReset // Admin resets from Critical state

  // Timeout events (auto-fired by sweeper)
  case Heartbeat     // Normal heartbeat in Started state (10s)
  case DegradedCheck // Fast check in Degraded state (3s)

  // Health check results (produced by health check effects)
  case Healthy  // Health check passed
  case Unstable // Health check shows issues
  case Failed   // Health check critical failure
end ServiceEvent
