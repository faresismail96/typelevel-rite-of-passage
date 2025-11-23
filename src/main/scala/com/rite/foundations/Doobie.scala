package com.rite.foundations

import cats.effect.kernel.Resource
import cats.effect.{IO, IOApp, MonadCancelThrow}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

object Doobie extends IOApp.Simple {

  // Doobie is a library for interacting with databases. It is based on CE

  case class Student(id: Int, name: String)

  val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver",               // JDBC connector
    "jdbc:postgresql:localhost:5462/demo", // database url
    "docker",                              // user
    "docker"                               // password
  )

  // to query a list of strings from db
  def findAllStudentNames: IO[List[String]] = {
    val query  = sql"select name from students".query[String]
    val action = query.to[List]

    action.transact(xa)
  }

  // to insert a student into db
  def insertStudent(id: Int, name: String): IO[Int] = {
    val query  = sql"insert into students(id, name) values($id, $name)"
    val action = query.update.run
    action.transact(xa)
  }

  // we can use fragments in doobie
  def findStudentsByInitialLetter(letter: String): IO[List[Student]] = {
    val selectFragment = fr"select id, name"
    val fromFragment   = fr"from students"
    val whereFragment  = fr"where left(name, 1) = $letter"

    val statement = selectFragment ++ fromFragment ++ whereFragment
    val action =
      statement
        .query[Student]
        .to[
          List
        ] // for complicated case classes, we will need to define a doobie Read/Write implicits
    action.transact(xa)
  }

  // how to organize code with doobie?
  // define a type class "repository" that will describe the capabilities in a general effect type (also called tagless final)
  trait Students[F[_]] {
    def findById(id: Int): F[Option[Student]]
    def findAll: F[List[Student]]
    def create(name: String): F[Int]
  }

  // we can then create a companion object to instantiate the repository
  object Students {
    def make[F[_]: MonadCancelThrow](xa: Transactor[F]): Students[F] = new Students[F] {
      override def findById(id: Int): F[Option[Student]] = ???
      override def findAll: F[List[Student]]             = ???
      override def create(name: String): F[Int]          = ???
    }
  }

  // to use it, create a resource. This way it closes the resource once we are done using it.
  val postgresResource: Resource[IO, HikariTransactor[IO]] = for {
    ce <- ExecutionContexts.fixedThreadPool[IO](8)
    xa <- HikariTransactor.newHikariTransactor[IO](
      "org.postgresql.Driver",
      "jdbc:postgresql:localhost:5462/demo",
      "docker",
      "docker",
      ce
    )
  } yield xa

  val smallProgram: IO[Unit] = postgresResource.use { xa =>
    val studentsRepo = Students.make[IO](xa)
    for {
      id    <- studentsRepo.create("fares")
      fares <- studentsRepo.findById(id)
      _     <- IO.println(s"the first student is $fares")
    } yield ()
  }

  override def run: IO[Unit] = ???
}
