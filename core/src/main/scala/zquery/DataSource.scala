package zquery

import zio.ZIO

/**
 * A `DataSource[R, A]` is capable of executing requests of type `A` that
 * require an environment `R`.
 *
 * Data sources must implement the method `run` which takes a collection of
 * requests and returns an effect with a `CompletedRequestMap` containing a
 * mapping from requests to results. Because `run` is parameterized on a
 * collection of requests rather than a single request, data sources have the
 * ability to introspect on all the requests being executed in parallel and
 * optimize the query.
 *
 * Data sources will typically be parameterized on a subtype of `Request[A]`,
 * though that is not strictly necessarily as long as the data source can map
 * the request type to a `Request[A]`. Data sources can then pattern match on
 * the collection of requests to determine the information requested, execute
 * the query, and place the results into the `CompletedRequestsMap` using
 * [[CompletedRequestMap.empty]] and [[CompletedRequestMap.insert]]. Data
 * sources must provide requests for all results received or fail with an `E`.
 * Failure to do so will cause a query to die with a `QueryFailure` when run.
 */
trait DataSource[-R, -A] {
  def dataSource: DataSource.Service[R, A]
}

object DataSource {

  trait Service[-R, -A] { self =>

    /**
     * The data source's identifier.
     */
    val identifier: String

    /**
     * Execute a collection of requests. Data sources are guaranteed that the
     * collection will contain at least one request and that all requests will
     * be unique when they are called by `ZQuery`.
     */
    def run(requests: Iterable[A]): ZIO[R, Nothing, CompletedRequestMap]

    /**
     * Returns a new data source that executes requests of type `B` using the
     * specified function to transform `B` requests into requests that this
     * data source can execute.
     */
    final def contramap[B](f: B => A)(name: String): DataSource.Service[R, B] =
      new DataSource.Service[R, B] {
        val identifier = s"${self.identifier}.contramap($name)"
        def run(requests: Iterable[B]): ZIO[R, Nothing, CompletedRequestMap] =
          self.run(requests.map(f))
      }

    /**
     * Returns a new data source that executes requests of type `B` using the
     * specified effectual function to transform `B` requests into requests
     * that this data source can execute.
     */
    final def contramapM[R1 <: R, B](name: String)(f: B => ZIO[R1, Nothing, A]): DataSource.Service[R1, B] =
      new DataSource.Service[R1, B] {
        val identifier = s"${self.identifier}.contramapM($name)"
        def run(requests: Iterable[B]): ZIO[R1, Nothing, CompletedRequestMap] =
          ZIO.foreach(requests)(f).flatMap(self.run)
      }

    /**
     * Returns a new data source that executes requests of type `C` using the
     * specified function to transform `C` requests into requests that either
     * this data source or that data source can execute.
     */
    final def eitherWith[R1 <: R, B, C](
      that: DataSource.Service[R1, B]
    )(name: String)(f: C => Either[A, B]): DataSource.Service[R1, C] =
      new DataSource.Service[R1, C] {
        val identifier = s"${self.identifier}.eitherWith(${that.identifier})($name)"
        def run(requests: Iterable[C]): ZIO[R1, Nothing, CompletedRequestMap] = {
          val (as, bs) = requests.foldLeft((List.empty[A], List.empty[B])) {
            case ((as, bs), c) =>
              f(c) match {
                case Left(a)  => (a :: as, bs)
                case Right(b) => (as, b :: bs)
              }
          }
          self.run(as).zipWithPar(that.run(bs))(_ ++ _)
        }
      }

    override final def equals(that: Any): Boolean = that match {
      case that: DataSource.Service[_, _] => this.identifier == that.identifier
    }

    override final def hashCode: Int =
      identifier.hashCode

    /**
     * Provides this data source with its required environment.
     */
    final def provide(name: String)(r: R): DataSource.Service[Any, A] =
      provideSome(s"_ => $name")(_ => r)

    /**
     * Provides this data source with part of its required environment.
     */
    final def provideSome[R0](name: String)(f: R0 => R): DataSource.Service[R0, A] =
      new DataSource.Service[R0, A] {
        val identifier = s"${self.identifier}.provideSome($name)"
        def run(requests: Iterable[A]): ZIO[R0, Nothing, CompletedRequestMap] =
          self.run(requests).provideSome(f)
      }

    override final def toString: String =
      identifier
  }

  object Service {

    /**
     * Constructs a data source from a function taking a collection of
     * requests and returning a `CompletedRequestMap`.
     */
    def apply[R, A](name: String)(f: Iterable[A] => ZIO[R, Nothing, CompletedRequestMap]): DataSource.Service[R, A] =
      new DataSource.Service[R, A] {
        val identifier = name
        def run(requests: Iterable[A]): ZIO[R, Nothing, CompletedRequestMap] =
          f(requests)
      }

    /**
     * Constructs a data source from a pure function.
     */
    final def fromFunction[A, B](
      name: String
    )(f: A => B)(implicit ev: A <:< Request[Nothing, B]): DataSource.Service[Any, A] =
      new DataSource.Service[Any, A] {
        val identifier = name
        def run(requests: Iterable[A]): ZIO[Any, Nothing, CompletedRequestMap] =
          ZIO.succeed(requests.foldLeft(CompletedRequestMap.empty)((map, k) => map.insert(k)(Right(f(k)))))
      }

    /**
     * Constructs a data source from a pure function that takes a list of requests and returns a list of results of the same size.
     * Each item in the result list must correspond to the item at the same index in the request list.
     */
    final def fromFunctionBatched[A, B](
      name: String
    )(f: Iterable[A] => Iterable[B])(implicit ev: A <:< Request[Nothing, B]): DataSource.Service[Any, A] =
      fromFunctionBatchedM(name)(f andThen ZIO.succeed)

    /**
     * Constructs a data source from an effectual function that takes a list of requests and returns a list of results of the same size.
     * Each item in the result list must correspond to the item at the same index in the request list.
     */
    final def fromFunctionBatchedM[R, E, A, B](
      name: String
    )(f: Iterable[A] => ZIO[R, Nothing, Iterable[B]])(implicit ev: A <:< Request[E, B]): DataSource.Service[R, A] =
      new DataSource.Service[R, A] {
        val identifier = name
        def run(requests: Iterable[A]): ZIO[R, Nothing, CompletedRequestMap] =
          f(requests).map(
            results =>
              (requests zip results).foldLeft(CompletedRequestMap.empty) {
                case (map, (k, v)) => map.insert(k)(Right(v))
              }
          )
      }

    /**
     * Constructs a data source from an effectual function.
     */
    final def fromFunctionM[R, E, A, B](
      name: String
    )(f: A => ZIO[R, Nothing, B])(implicit ev: A <:< Request[E, B]): DataSource.Service[R, A] =
      new DataSource.Service[R, A] {
        val identifier = name
        def run(requests: Iterable[A]): ZIO[R, Nothing, CompletedRequestMap] =
          ZIO
            .foreachPar(requests)(k => ZIO.succeed(k).zip(f(k)))
            .map(_.foldLeft(CompletedRequestMap.empty) { case (map, (k, v)) => map.insert(k)(Right(v)) })
      }
  }
}
