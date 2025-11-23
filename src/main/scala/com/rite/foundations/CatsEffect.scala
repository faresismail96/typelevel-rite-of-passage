package com.rite.foundations

import cats.{Defer, MonadError}
import cats.effect.{Concurrent, Deferred, GenSpawn, IOApp, MonadCancel, Ref, Spawn, Sync, Temporal}
import cats.effect.kernel.{Async, Fiber, Resource}

import java.io.{File, FileWriter, PrintWriter}
import scala.concurrent.ExecutionContext
import scala.io.BufferedSource
import scala.util.Random

object CatsEffect extends IOApp.Simple {

  /*

      describe computations as values

   */

  // IO --> data structure for arbitrary computations including ones that can perform side effects
  import cats.effect.IO
  val firstIO: IO[Int] = IO.pure(42)
  val delayedIO: IO[Int] = IO.apply {
    println("this will not be performed until that IO is evaluated")
    42
  }

  def evaluateIO[A](io: IO[A]): Unit = {
    import cats.effect.unsafe.implicits.global // platforms on top of which IO can be evaluated
    io.unsafeRunSync()                         // the IO will be evaluated here
  }

  // IO has a lot of transformation: map, flatMap

  // fibers = lightweight threads. computations that can be run in // and there's an internal schedulers that will schedule them on os threads
  import scala.concurrent.duration.*
  private val delayedPrint = IO.sleep(1.second) *> IO(println(Random.nextInt(100)))

  val manyPrints: IO[Unit] = for {
    fib1 <- delayedPrint.start // spin off a fiber
    fib2 <- delayedPrint.start // both those computations will run in //
    _ <-
      fib1.join // Fibers can contain information like db connection so we should not leave them hanging
    _ <- fib2.join
  } yield ()

  // We can cancel fibers or define what happens if the fiber is cancelled. Here we have two fibers where one cancel the other
  val cancelFibers: IO[Unit] = for {
    fib <- delayedPrint.onCancel(IO.println("cancelled")).start
    _   <- IO.sleep(2.second) *> IO.println("cancelling fiber") *> fib.cancel
    _   <- fib.join
  } yield ()

  // We can mark IOs as uncancellable with an uncancellable wrapper. even if we call cancel on it, it will not be cancelled.
  // the cancellation signal will be ignored
  val ignoredCancellation: IO[Unit] = for {
    fib <- IO.uncancelable(_ => delayedPrint.onCancel(IO.println("cancelled"))).start
    _   <- IO.sleep(2.second) *> IO.println("cancelling fiber") *> fib.cancel
    _   <- fib.join
  } yield ()

  // resources --> IOs for finishing up resources with db connections etc... when the effect is finished/canceled

  // make takes an acquire and release params
  val readingResource: Resource[IO, BufferedSource] = Resource.make(
    IO(scala.io.Source.fromFile("src/main/scala/com/rite/foundations/CatsEffect.scala"))
  )(source => IO.println("closing source") *> IO(source.close()))

  // To use a resource: use. once the effect is finished/canceled --> release the effect. In the above example, it prints and closes.
  val readingEffect: IO[Unit] =
    readingResource.use(source => IO(source.getLines().foreach(println)))

  // resources can be composed
  val copiedFileResource: Resource[IO, PrintWriter] =
    Resource.make(IO(new PrintWriter(new FileWriter(new File("/tmp/myfile.scala")))))(writer =>
      IO.println("closing file") *> IO(writer.close())
    )

  val compositeResouce: Resource[IO, (BufferedSource, PrintWriter)] = for {
    source      <- readingResource
    destination <- copiedFileResource
  } yield (source, destination)

  val copyFileEffect: IO[Unit] = compositeResouce.use { case (source, destination) =>
    IO(source.getLines().foreach(destination.println))
  }
  // ^ once the above effect is done it will handle closing both resources

  // cats effect has type classes for abstract kind of computations

  // MonadCancel --> for cancellable computations
  trait MyMonadCancel[F[_], E] extends MonadError[F, E] {

    trait CancellationFlagResetter {
      def apply[A](fa: F[A]): F[A] // returns F[A] with the cancellation flag reset.
    }

    def canceled: F[Unit]
    def uncancelable[A](poll: CancellationFlagResetter => F[A]): F[A] // same as IO.uncancelable
  }

  // Spawn --> ability to create fibers
  trait MyGenSpawn[F[_], E] extends MonadCancel[F, E] {
    def start[A](fa: F[A]): F[Fiber[F, E, A]] // creates a fiber
    // neve, cede, racePair
  }
  trait MySpawn[F[_]] extends GenSpawn[F, Throwable] // MySpawn now fixes the Error to Throwable

  val spawnIO: Spawn[IO] = Spawn[IO] // fetches the implicit instance for GenSpawn[IO, Throwable]
  val fiber: IO[Fiber[IO, Throwable, Unit]] =
    spawnIO.start(delayedPrint) // same as delayedPrint.start

  // Concurrent --> creates 2 concurrency primitives: atomic references and promises

  trait MyConcurrent[F[_]] extends Spawn[F] {
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  }

  // Temporal --> ability to suspend computations for a given time
  trait MyTemporal[F[_]] extends Concurrent[F] {
    def sleep(tile: FiniteDuration): F[Unit]
  }

  // Sync --> ability to suspend synchronous arbitrary computations in an effect
  trait MySync[F[_]] extends MonadCancel[F, Throwable] with Defer[F] {
    def delay[A](expression: => A): F[A]
    def blocking[A](expression: => A): F[A] // but this runs on a dedicated blocking thread pool
  }

  // Async --> ability to suspend async arbitrary computations that are running on other thread pool into an effect managed my CE
  trait MyAsync[F[_]] extends Sync[F] with Temporal[F] {
    def executionContext: F[ExecutionContext]
    def async[A](callback: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
  }

  // We can extend IOApp since it has a run method that returns an IO that will be evaluated internally in a main function
  override def run: IO[Unit] = ???

}
