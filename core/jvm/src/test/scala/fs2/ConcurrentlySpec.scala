package fs2

import scala.concurrent.duration._
import cats.effect.IO
import cats.syntax.all._
import fs2.async.Promise
import TestUtil._
import org.scalatest.concurrent.PatienceConfiguration.Timeout

class ConcurrentlySpec extends Fs2Spec with EventuallySupport {

  "concurrently" - {

    "when background stream terminates, overall stream continues" in forAll {
      (s1: PureStream[Int], s2: PureStream[Int]) =>
        runLog(
          Scheduler[IO](1).flatMap(scheduler =>
            (scheduler.sleep_[IO](25.millis) ++ s1.get)
              .concurrently(s2.get))) shouldBe s1.get.toVector
    }

    "when background stream fails, overall stream fails" in forAll {
      (s: PureStream[Int], f: Failure) =>
        val prg = Scheduler[IO](1).flatMap(scheduler =>
          (scheduler.sleep_[IO](25.millis) ++ s.get).concurrently(f.get))
        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err.type] should be thrownBy runLog(prg)
        else runLog(prg)
    }

    "when primary stream fails, overall stream fails and background stream is terminated" in forAll {
      (f: Failure) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prg = Scheduler[IO](1).flatMap(scheduler =>
          (scheduler.sleep_[IO](25.millis) ++ f.get).concurrently(bg))
        an[Err.type] should be thrownBy runLog(prg)
        eventually(Timeout(3 seconds)) { bgDone shouldBe true }
    }

    "when primary stream termiantes, background stream is terminated" in forAll {
      (s: PureStream[Int]) =>
        var bgDone = false
        val bg = Stream.repeatEval(IO(1)).onFinalize(IO { bgDone = true })
        val prg = Scheduler[IO](1).flatMap(scheduler =>
          (scheduler.sleep_[IO](25.millis) ++ s.get).concurrently(bg))
        runLog(prg)
        bgDone shouldBe true
    }

    "when background stream fails, primary stream fails even when hung" in forAll {
      (s: PureStream[Int], f: Failure) =>
        val promise = Promise.unsafeCreate[IO, Unit]
        val prg = Scheduler[IO](1).flatMap { scheduler =>
          (scheduler.sleep_[IO](25.millis) ++ (Stream(1) ++ s.get))
            .concurrently(f.get)
            .flatMap { i =>
              Stream.eval(promise.get).map { _ =>
                i
              }
            }
        }

        val throws = f.get.compile.drain.attempt.unsafeRunSync.isLeft
        if (throws) an[Err.type] should be thrownBy runLog(prg)
        else runLog(prg)
    }

    "run finalizers of background stream and properly handle exception" in forAll {
      s: PureStream[Int] =>
        val prg = Stream
          .eval(async.signalOf[IO, Boolean](false))
          .flatMap { halt =>
            val bracketed =
              Stream.bracket(IO(new java.util.concurrent.atomic.AtomicBoolean(true)))(
                Stream(_),
                b => IO(b.set(false))
              )

            bracketed
              .flatMap { b =>
                s.get
                  .covary[IO]
                  .concurrently(
                    (Stream.eval_(IO.sleep(20.millis)) ++
                      Stream
                        .eval_(halt.set(true)))
                      .onFinalize(
                        IO.sleep(100.millis) >>
                          (if (b.get) IO.raiseError(Err) else IO(()))
                      ))
              }
              .interruptWhen(halt)
          }

        an[Err.type] should be thrownBy runLog(prg)

    }
  }
}
