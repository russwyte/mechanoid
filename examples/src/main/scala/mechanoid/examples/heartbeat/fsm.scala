package mechanoid.examples.heartbeat

import zio.*
import mechanoid.*

/** A simulated health check service that randomly reports service health.
  *
  * In a real application, this would check actual service metrics, database connections, external API health, etc.
  */
object HealthChecker:
  import ServiceEvent.*

  /** Perform a health check. Returns a health event after a simulated delay.
    *
    * @param checkDuration
    *   How long the health check takes (simulates real async work)
    * @param healthyChance
    *   Probability of returning Healthy (0.0 to 1.0)
    * @param unstableChance
    *   Probability of returning Unstable (remainder goes to Failed)
    */
  def check(
      checkDuration: Duration,
      healthyChance: Double,
      unstableChance: Double,
  ): ZIO[Any, Nothing, ServiceEvent] =
    for
      _      <- ZIO.sleep(checkDuration)
      random <- Random.nextDouble
      result =
        if random < healthyChance then Healthy
        else if random < healthyChance + unstableChance then Unstable
        else Failed
    yield result

  /** Health check for normal operation: 80% healthy, 15% unstable, 5% failed */
  val normalCheck: ZIO[Any, Nothing, ServiceEvent] =
    check(checkDuration = 500.millis, healthyChance = 0.80, unstableChance = 0.15)

  /** Health check during degraded state: 60% recovers, 30% stays unstable, 10% fails */
  val degradedCheck: ZIO[Any, Nothing, ServiceEvent] =
    check(checkDuration = 200.millis, healthyChance = 0.60, unstableChance = 0.30)
end HealthChecker

object ServiceFSM:
  import ServiceState.*
  import ServiceEvent.*

  val machine: Machine[ServiceState, ServiceEvent] =
    Machine(
      assemblyAll[ServiceState, ServiceEvent]:
        // ========================================
        // STOPPED STATE
        // ========================================

        // Start the service → enters Started with normal heartbeat timeout
        (Stopped via Start to Started)
          .onEntry { (_, _) =>
            ZIO.logInfo("🚀 Service starting...")
          } @@ Aspect.timeout(10.seconds, Heartbeat)

        // ========================================
        // STARTED STATE (normal operation)
        // ========================================

        // Heartbeat fires → run health check that produces next event
        (Started via Heartbeat to Started)
          .onEntry { (_, _) =>
            Clock.instant.flatMap(now => ZIO.logInfo(s"💓 Heartbeat at $now - running health check..."))
          }
          .producing { (_, _) =>
            HealthChecker.normalCheck
          } @@ Aspect.timeout(10.seconds, Heartbeat)

        // Health check returned Healthy → stay in Started (timeout already set by transition above)
        (Started via Healthy to Started)
          .onEntry { (_, _) =>
            ZIO.logInfo("✅ Health check: HEALTHY - continuing normal operation")
          } @@ Aspect.timeout(10.seconds, Heartbeat)

        // Health check returned Unstable → enter Degraded with faster checks
        (Started via Unstable to Degraded)
          .onEntry { (_, _) =>
            ZIO.logWarning("⚠️  Health check: UNSTABLE - entering degraded mode with faster checks")
          } @@ Aspect.timeout(3.seconds, DegradedCheck)

        // Health check returned Failed → enter Critical state (human intervenes after 15s)
        (Started via Failed to Critical)
          .onEntry { (_, _) =>
            ZIO.logError("🚨 Health check: FAILED - entering critical state, awaiting human intervention...")
          } @@ Aspect.timeout(15.seconds, ManualReset)

        // Can always stop
        Started via Stop to Stopped

        // ========================================
        // DEGRADED STATE (experiencing issues)
        // ========================================

        // Fast check fires → run health check
        (Degraded via DegradedCheck to Degraded)
          .onEntry { (_, _) =>
            Clock.instant.flatMap(now => ZIO.logWarning(s"⚡ Degraded check at $now - checking recovery..."))
          }
          .producing { (_, _) =>
            HealthChecker.degradedCheck
          } @@ Aspect.timeout(3.seconds, DegradedCheck)

        // Recovered! → back to normal operation
        (Degraded via Healthy to Started)
          .onEntry { (_, _) =>
            ZIO.logInfo("🎉 Recovery check: HEALTHY - returning to normal operation!")
          } @@ Aspect.timeout(10.seconds, Heartbeat)

        // Still unstable → stay degraded with fast checks
        (Degraded via Unstable to Degraded)
          .onEntry { (_, _) =>
            ZIO.logWarning("⚠️  Recovery check: STILL UNSTABLE - continuing degraded monitoring")
          } @@ Aspect.timeout(3.seconds, DegradedCheck)

        // Got worse → critical (human intervenes after 15s)
        (Degraded via Failed to Critical)
          .onEntry { (_, _) =>
            ZIO.logError("🚨 Recovery check: FAILED - situation worsened, awaiting human intervention...")
          } @@ Aspect.timeout(15.seconds, ManualReset)

        // Can stop from degraded
        Degraded via Stop to Stopped

        // ========================================
        // CRITICAL STATE (needs manual intervention)
        // ========================================

        // Human intervention (or timeout) resets us back to normal operation
        (Critical via ManualReset to Started)
          .onEntry { (_, _) =>
            ZIO.logInfo("👨‍🔧 Human intervened! Returning to normal operation")
          } @@ Aspect.timeout(10.seconds, Heartbeat)

        // Can stop from critical
        Critical via Stop to Stopped
    )
end ServiceFSM
