package io.funcqrs.projections

import io.funcqrs.projections.Projection._

import scala.concurrent.Future
import scala.util.Try
import scala.util.control.NonFatal

trait Projection[E] {

  type HandleEvent = PartialFunction[E, Future[Unit]]
  type OnFailure   = PartialFunction[(E, Throwable), Future[Unit]]

  def name: String = this.getClass.getSimpleName

  def handleEvent: HandleEvent

  def onFailure: OnFailure = PartialFunction.empty

  final def onEvent(evt: E): Future[Unit] = {
    if (handleEvent.isDefinedAt(evt)) {
      import scala.concurrent.ExecutionContext.Implicits.global
      handleEvent(evt)
        .recoverWith {
          case NonFatal(exp) if onFailure.isDefinedAt(evt, exp) => onFailure(evt, exp)
        }
    } else {
      Future.successful(())
    }
  }

  /**
    * Builds a [[AndThenProjection]] composed of this Projection and the passed Projection.
    *
    * Events will be send to both projections. One after the other starting by this followed by the passed Projection.
    *
    * NOTE: In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    */
  def andThen(projection: Projection[E]) = new AndThenProjection(this, projection)

  /**
    * Builds a [[OrElseProjection]] composed of this Projection and the passed Projection.
    *
    * If this Projection is defined for a given incoming Event, then this Projection will be applied,
    * otherwise we fallback to the passed Projection.
    */
  def orElse(fallbackProjection: Projection[E]) = new OrElseProjection(this, fallbackProjection)

  object sync {

    /** Handles an incoming Payload synchronously. */
    object HandleEvent {
      def apply(handle: PartialFunction[E, Unit]): PartialFunction[E, Future[Unit]] = {
        case evt if handle.isDefinedAt(evt) =>
          Future.successful(handle(evt))
      }
    }

  }

  object attempt {

    /** Handles an incoming Payload synchronously using a [[Try]]. */
    object HandleEvent {
      def apply(handle: PartialFunction[E, Try[Unit]]): PartialFunction[E, Future[Unit]] = {
        case evt if handle.isDefinedAt(evt) =>
          Future.fromTry(handle(evt))
      }
    }
  }

  /** Handles an incoming Event asynchronously. */
  object HandleEvent {
    def apply(handle: PartialFunction[E, Future[Unit]]): PartialFunction[E, Future[Unit]] = {
      case evt if handle.isDefinedAt(evt) =>
        handle(evt)
    }
  }

}

object Projection {

  /** Projection with empty domain */
  def empty[E] = new Projection[E] {
    def handleEvent: HandleEvent = PartialFunction.empty
  }

  /**
    * A [[Projection]] composed of two other Projections to each Event will be sent.
    *
    * Note that the second Projection is only applied once the first is completed successfully.
    *
    * In the occurrence of any failure on any of the underling Projections, this Projection may be replayed,
    * therefore idempotent operations are recommended.
    *
    * If none of the underlying Projections is defined for a given DomainEvent,
    * then this Projection is considered to be not defined for this specific DomainEvent.
    * As such a [[AndThenProjection]] can be combined with a [[OrElseProjection]].
    *
    * For example:
    * {{{
    *   val projection1 : Projection = ...
    *   val projection2 : Projection = ...
    *   val projection3 : Projection = ...
    *
    *   val finalProjection = (projection1 andThen projection2) orElse projection3
    *
    *   finalProjection.onEvent(SomeEvent("abc"))
    *   // if SomeEvent("abc") is not defined for projection1 nor for projection2, projection3 will be applied
    * }}}
    *
    */
  private[funcqrs] class AndThenProjection[E](firstProj: Projection[E], secondProj: Projection[E])
      extends ComposedProjection(firstProj, secondProj)
      with Projection[E] {

    import scala.concurrent.ExecutionContext.Implicits.global

    val projections = Seq(firstProj, secondProj)

    override def name: String = s"${firstProj.name}-and-then-${secondProj.name}"

    def handleEvent: HandleEvent = {
      // note that we only broadcast if at least one of the underlying
      // projections is defined for the incoming event
      // as such we make it possible to compose using orElse
      case (envelope) if composedHandleEvent.isDefinedAt(envelope) =>
        // send event to all projections
        firstProj.onEvent(envelope).flatMap { _ =>
          secondProj.onEvent(envelope)
        }
    }
  }

  /**
    * A [[Projection]] composed of two other Projections.
    *
    * Its `receiveEvent` is defined in terms of the `receiveEvent` method form the first Projection
    * with fallback to the `receiveEvent` method of the second Projection.
    *
    * As such the second Projection is only applied if the first Projection is not defined
    * for the given incoming Events
    *
    */
  private[funcqrs] class OrElseProjection[E](firstProj: Projection[E], secondProj: Projection[E])
      extends ComposedProjection(firstProj, secondProj)
      with Projection[E] {
    override def name: String = s"${firstProj.name}-or-then-${secondProj.name}"

    def handleEvent = composedHandleEvent
  }

  private[funcqrs] class ComposedProjection[E](firstProj: Projection[E], secondProj: Projection[E]) {
    // compose underlying receiveEvents PartialFunction in order
    // to decide if this Projection is defined for given incoming DomainEvent
    private[funcqrs] def composedHandleEvent = firstProj.handleEvent orElse secondProj.handleEvent
  }

}
