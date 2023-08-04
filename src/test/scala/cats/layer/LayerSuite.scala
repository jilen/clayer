package cats.layer

import cats.syntax.all._
import cats.effect._
import cats.effect.std._
import munit.CatsEffectSuite
import scala.concurrent.duration._

class LayerSuite extends CatsEffectSuite {

  implicit class ResDebug[A](ra: Resource[IO, A]) {
    def debug(label: String) = {
      ra.flatTap { a =>
        Resource.make(
          IO.println(s"[$label] Allocated ${a}@(${System.identityHashCode(a)})")
        )(_ =>
          IO.println(s"[$label] Releasing ${a}@(${System.identityHashCode(a)})")
        )
      }
    }
  }

  test("refCount") {
    val resRef = Resource.make(Ref.of[IO, Boolean](true))(_.set(true))
    for {
      pair <- Layer.RefCount(resRef)
      released <- Deferred[IO, Unit]
      (refCount, finalizer) = pair
      stop <- Deferred[IO, Unit]
      _ <- refCount.acquireRef.use_.parReplicateA(100)
      _ <- refCount.acquireRef.use(_ => stop.get).start
      _ <- (finalizer >> released.complete(())).start
      r1 <- released.tryGet
      _ <- stop.complete(())
      _ <- released.get
    } yield {
      assert(r1.isEmpty)
    }
  }

  test("memoize / referred") {
    def incrLayer() = Layer.function { (ref: Ref[IO, Int]) =>
      Resource.eval(ref.updateAndGet(_ + 1))
    }
    val refLayer: ULayer[Ref[IO, Int]] = Layer.eval(Ref.of[IO, Int](0))

    val refCount = 10

    val appLayer =
      Seq.fill(refCount)(incrLayer()).foldLeft(refLayer) { (r, i) =>
        r >+> i
      }
    appLayer.build(ZEnv(())).use { env =>
      env.get[Ref[IO, Int]].get.map(r => assertEquals(r, refCount))
    }
  }

  test("memoize / usage") {
    trait Counter {
      def incrAndGet(): IO[Int]
    }
    val refLayer: ULayer[Ref[IO, Int]] = Layer.eval(Ref.of[IO, Int](0))
    val useRefLayer: Layer[Ref[IO, Int], Counter] = Layer.function {
      (ref: Ref[IO, Int]) =>
        IO(new Counter {
          def incrAndGet() = ref.updateAndGet(_ + 1)
        }).toResource
    }
    (refLayer >>> useRefLayer).build(ZEnv(())).use { env =>
      val counter = env.get[Counter]
      (1 to 10).toVector.traverse_ { i =>
        counter.incrAndGet().map(c => assertEquals(c, i))
      }
    }
  }

  test("retain resource") {

    val stopSignal = Layer.resource(
      Resource
        .make(Deferred[IO, Unit])(r => r.complete(()).void)
        .debug("stopSignal")
    )

    trait Check {
      def underlying: Deferred[IO, Unit]
      def stopped: IO[Boolean]
      def stopSelf: IO[Unit]
    }

    def makeCheck(ss: Deferred[IO, Unit]): Resource[IO, Check] = {
      Resource
        .make(Deferred[IO, Unit])(_.complete(()).void)
        .debug("checkStopSignal")
        .map { defer =>
          new Check {
            def underlying = ss
            def stopped = ss.tryGet.map(_.nonEmpty)
            def stopSelf = defer.complete(()).void
          }
        }

    }

    val useSignal: Layer[Deferred[IO, Unit], Check] = Layer.function {
      (defer: Deferred[IO, Unit]) =>
        makeCheck(defer)
    }

    (stopSignal >>> useSignal)
      .build(ZEnv(()))
      .use { env =>
        val check = env.get[Check]
        (
          check.stopped, // stopSignal 未设置
          check.stopSelf.as(check)
        ).tupled.start
      }
      .flatMap { fib =>
        fib.joinWithNever.flatMap { // stopSignal 已设置
          case (s1, check) =>
            check.stopped.map(f => assertEquals(f, true))
              >> IO(assertEquals(s1, false))
        }
      }
  }
}
