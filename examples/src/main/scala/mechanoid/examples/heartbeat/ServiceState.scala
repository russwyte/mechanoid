package mechanoid.examples.heartbeat

import mechanoid.Finite

/** Service states with health monitoring.
  *
  * The service can be in one of several states:
  *   - Stopped: Not running
  *   - Started: Running normally (10s heartbeat → health check)
  *   - Degraded: Running but experiencing issues (3s checks for faster recovery)
  *   - Critical: Needs manual intervention (no automatic timeout)
  */
enum ServiceState derives Finite:
  case Stopped
  case Started
  case Degraded
  case Critical
