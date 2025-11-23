package com.rite.foundations

import cats.Monad
import cats.effect.{IO, IOApp}
import org.http4s.{Header, HttpRoutes, Request, Response}
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.impl.{OptionalValidatingQueryParamDecoderMatcher, QueryParamDecoderMatcher}
import org.http4s.ember.server.EmberServerBuilder
import cats.*
import cats.data.Kleisli
import cats.implicits.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.circe.*
import org.http4s.Header.*
import org.http4s.server.Router
import org.typelevel.ci.CIString

import java.util.UUID

object Http4s extends IOApp.Simple {

  // HTTP server for the typelevel stack. uses IOs and tagless final structure

  // simulate an http server with students and courses
  type Student = String
  case class Instructor(firstName: String, lastName: String)
  case class Course(
      id: String,
      title: String,
      year: Int,
      students: List[String],
      instructorName: String
  )

  object CourseRepository {
    // in memory map for easiness
    private val courses: Map[String, Course] = Map()

    private val catsEffectCourse: Course =
      Course(
        "f9c5c3c2-3ca4-4b32-8da2-2cf7b7f6f4a1",
        "Scala Course",
        2025,
        List("Fares", "someone"),
        "Martin Odersky"
      )

    // API
    def findCoursesById(courseId: UUID): Option[Course] = courses.get(courseId.toString)
    def findCourseByInstructor(name: String): List[Course] =
      courses.values.filter(_.instructorName == name).toList
  }

  // REST endpoints
  // GET localhost:8080/courses?instructor=Martin%20Odersky&year=2025
  // GET localhost:8080/courses/f9c5c3c2-3ca4-4b32-8da2-2cf7b7f6f4a1/students

  object InstructorQueryParamMatcher
      extends QueryParamDecoderMatcher[String]("instructor") // allows to match query parameters

  object YearQueryParamMatcher
      extends OptionalValidatingQueryParamDecoderMatcher[Int](
        "year"
      ) // allows to match and validate the query param. Optional because the query param is optional

  def courseRoutes[F[_]: Monad]: HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] {
      case GET -> Root / "courses" :? InstructorQueryParamMatcher(
            instructor
          ) +& YearQueryParamMatcher(optYear) => // optYear becomes an Option of ValidatedNel
        val courses = CourseRepository.findCourseByInstructor(instructor)
        optYear match {
          case Some(value) =>
            value.fold(
              _ => BadRequest("Parameter year is invalid"),
              year => Ok(courses.filter(_.year == year).asJson)
            )
          case None => Ok(courses.asJson)
        }

      case GET -> Root / "courses" / UUIDVar(courseId) / "students" =>
        CourseRepository.findCoursesById(courseId).map(_.students) match {
          case Some(value) =>
            Ok(
              value.asJson,
              Header.Raw(CIString("my-custom-header"), "rite")
            ) // we can also inject headers in the response
          case None => NotFound(s"No course with courseId $courseId found")
        }
    }
  }

  def healthEndpoint[F[_]: Monad]: HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*
    HttpRoutes.of[F] { case GET -> Root / "health" =>
      Ok("All good")
    }
  }

  // We can combine endpoints
  def allRoutes[F[_]: Monad]: HttpRoutes[F] =
    courseRoutes <+> healthEndpoint // combines both HttpRoutes into one. we can also define a pathPrefix for each route

  def routerWithPathPrefixes: Kleisli[IO, Request[IO], Response[IO]] = Router(
    "api"   -> courseRoutes[IO],  // becomes /api/courses/etc...
    "infra" -> healthEndpoint[IO] // /infra/health
  ).orNotFound

  override def run: IO[Unit] = EmberServerBuilder
    .default[IO] // at the end of the world, specify a concrete effect and not F
    .withHttpApp(allRoutes[IO].orNotFound) // or .withHttpApp(routerWithPathPrefixes)
    .build
    .use_
}
