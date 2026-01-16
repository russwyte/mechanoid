package mechanoid.persistence

import zio.*
import zio.test.*

object ScheduleExperimentSpec extends ZIOSpecDefault:

  def spec = suite("Schedule experiments")(
    test("basic repeat with spaced schedule using TestClock") {
      for
        counter <- Ref.make(0)
        // Start the repeating effect
        fiber <- counter
          .update(_ + 1)
          .repeat(Schedule.spaced(50.millis) && Schedule.recurs(2))
          .fork

        // Advance time to trigger repetitions
        _ <- TestClock.adjust(50.millis)
        _ <- TestClock.adjust(50.millis)
        _ <- fiber.join

        count <- counter.get
      yield assertTrue(count == 3) // Initial + 2 repeats
    },
    test("repeat with jitter using TestClock") {
      for
        counter <- Ref.make(0)
        schedule = Schedule.spaced(50.millis).jittered(0.0, 0.2) && Schedule.recurs(2)

        fiber <- counter.update(_ + 1).repeat(schedule).fork

        // Advance time enough for jittered delays (max jitter = 50 * 0.2 = 10ms)
        _ <- TestClock.adjust(60.millis)
        _ <- TestClock.adjust(60.millis)
        _ <- fiber.join

        count <- counter.get
      yield assertTrue(count == 3)
    },
    test("stop repeat when promise completes using race") {
      for
        counter    <- Ref.make(0)
        stopSignal <- Promise.make[Nothing, Unit]

        // Start repeating in background - note: forever doesn't use Schedule
        fiber <- (counter.update(_ + 1) *> ZIO.sleep(20.millis)).forever
          .race(stopSignal.await)
          .fork

        // Advance time to let it run several iterations
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)

        countBefore <- counter.get

        // Signal stop
        _ <- stopSignal.succeed(())
        _ <- fiber.join

        // Try to advance more - should not increase counter
        _          <- TestClock.adjust(100.millis)
        countAfter <- counter.get
      yield assertTrue(
        countBefore >= 3,
        countAfter == countBefore, // No more increments after stop
      )
    },
    test("repeat with schedule and race for stopping") {
      for
        counter    <- Ref.make(0)
        stopSignal <- Promise.make[Nothing, Unit]

        schedule = Schedule.spaced(20.millis)

        // Start repeating with schedule in background
        fiber <- counter
          .update(_ + 1)
          .repeat(schedule)
          .race(stopSignal.await)
          .fork

        // Advance time
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)

        countBefore <- counter.get

        // Signal stop
        _ <- stopSignal.succeed(())
        _ <- fiber.join

        // Try to advance more
        _          <- TestClock.adjust(100.millis)
        countAfter <- counter.get
      yield assertTrue(
        countBefore >= 3,
        countAfter == countBefore,
      )
    },
    test("recurWhile stops based on effect output") {
      for
        counter <- Ref.make(0)

        // recurWhile continues while the predicate is true on the INPUT (effect output)
        // This is the key insight: use recurWhile to check the effect's output!
        schedule = Schedule.spaced(10.millis) && Schedule.recurWhile[Int](_ < 5)

        fiber <- counter.updateAndGet(_ + 1).repeat(schedule).fork

        // Advance time enough for all iterations
        _ <- TestClock.adjust(10.millis)
        _ <- TestClock.adjust(10.millis)
        _ <- TestClock.adjust(10.millis)
        _ <- TestClock.adjust(10.millis)
        _ <- TestClock.adjust(10.millis)
        _ <- fiber.join

        count <- counter.get
      yield assertTrue(count == 5) // Stops when effect outputs 5
    },
    test("interruptible schedule - effect returns running status with recurWhile") {
      for
        counter <- Ref.make(0)
        running <- Ref.make(true)

        // KEY PATTERN: Effect returns the "should continue" status
        // recurWhile[Boolean](identity) continues while effect returns true
        schedule = Schedule.spaced(20.millis) && Schedule.recurWhile[Boolean](identity)

        // Effect: increment counter, return running status
        effect = counter.update(_ + 1) *> running.get

        fiber <- effect.repeat(schedule).fork

        // Advance time for several iterations
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)

        countBefore <- counter.get

        // Set running to false - next iteration should stop
        _ <- running.set(false)
        _ <- TestClock.adjust(20.millis)
        _ <- fiber.join

        countAfter <- counter.get
      yield assertTrue(
        countBefore >= 3,
        countAfter == countBefore + 1, // One more iteration then stops
      )
    },
    test("combining spaced with recurWhileZIO for effectful condition check") {
      for
        counter <- Ref.make(0)
        running <- Ref.make(true)

        // recurWhileZIO allows effectful condition checking
        schedule = Schedule.spaced(20.millis) && Schedule.recurWhileZIO[Any, Int](_ => running.get)

        fiber <- counter.updateAndGet(_ + 1).repeat(schedule).fork

        // Advance time
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)
        _ <- TestClock.adjust(20.millis)

        countBefore <- counter.get

        // Stop
        _ <- running.set(false)
        _ <- TestClock.adjust(20.millis)
        _ <- fiber.join

        countAfter <- counter.get
      yield assertTrue(
        countBefore >= 2,
        countAfter <= countBefore + 1,
      )
    },
  )
end ScheduleExperimentSpec
