package shareevent.simplemodel

import org.joda.time.{DateTime, Duration => JodaDuration}
import shareevent.{DomainContext, DomainService}
import shareevent.model._

import scala.util.{Failure, Success, Try}
import org.joda.time.Interval
import shareevent.persistence.QueryDSL

import QueryDSL._

class SimpleService extends DomainService[Try] {


  def createEvent(organizerId: Person.Id, title: String, theme: String,
                  organizerCost: Money, duration: JodaDuration,
                  scheduleWindow: JodaDuration, quantityOfParticipants: Int): DomainContext[Try] => Try[Event] = {
  ctx =>
    Try {
      Event(
        None,
        title = title,
        theme = theme,
        organizerId = organizerId,
        organizerCost = organizerCost,
        status = EventStatus.Initial,
        created = ctx.currentTime,
        duration = duration,
        scheduleWindow = scheduleWindow,
        minParticipantsQuantity = quantityOfParticipants)
    } flatMap {
       ctx.repository.eventsDAO.store(_)
    }
  }

  override def createLocation(name: String, capacity: Int, startSchedule: DateTime, endSchedule: DateTime, coordinate: Coordinate,
                              costs: Money): DomainContext[Try] => Try[Location] = { ctx =>
      // TODO: add check that capacity > 0
      ctx.repository.locationDAO.store(
        Location(None, name, capacity, coordinate, Seq.empty)
      )
  }

  /**
    * If participant is interested in event, he can participate
    * in scheduling of one.
    */
  override def participantInterest(event: Event, participant: Person): (DomainContext[Try]) => Try[Boolean] = _ => Success(true)

  override def schedule(eventId: Event.Id, locationId: Location.Id, time: DateTime): DomainContext[Try] => Try[ScheduleItem] = {

    _ => Try(ScheduleItem(eventId, locationId, time, Seq.empty))
  }

  override def locationConfirm(scheduleItem: ScheduleItem): DomainContext[Try] => Try[ScheduleItem] = {
    ctx => Try {

      ctx.repository.locationDAO.retrieveExistent(scheduleItem.locationId) match {
        case Success(location) => {
          def isCrossTime(booking: Booking, time: DateTime): Boolean = {
            booking.time.contains(time)
          }
          val bookings = location.bookings
          val isBad = bookings.exists(booking => isCrossTime(booking, scheduleItem.time) && scheduleItem.eventId != booking.eventId)
          if (isBad) {
             throw new RuntimeException("Can't allocate time for item")
          } else {
             // ? change state ?
             scheduleItem
          }
        }
        case Failure(ex) => throw ex
      }
      // booking chaNGE STATUS , RETURN
    }
  }

  override def generalConfirm(scheduleItem: ScheduleItem): DomainContext[Try] => Confirmation = {
    _ => Confirmation(scheduleItem)
  }


  override def cancel(confirmation: Confirmation): DomainContext[Try] => Try[Event] =

    context => {

      val item = confirmation.scheduleItem
      val eventId = item.eventId

      for {
           event <- context.repository.eventsDAO.retrieveExistent(eventId)
           interval = new Interval(item.time, event.duration)
           location <- context.repository.locationDAO.retrieveExistent(item.locationId)
           changed = location.copy(bookings = location.bookings.filterNot( _ == Booking(interval,eventId)))
           savedLocation = context.repository.locationDAO.merge(changed)
           cancelledEvent = event.copy(status = EventStatus.Cancelled)
           savedCancelledEvent <- context.repository.eventsDAO.merge(cancelledEvent)
      } yield {
        savedCancelledEvent
      }

  }


  override def run(confirmation: Confirmation): DomainContext[Try] => Try[Event] =
  ctx => {
    for {event <- ctx.repository.eventsDAO.retrieveExistent(
                              confirmation.scheduleItem.eventId)
         changed = event.copy(status = EventStatus.Done)
         saved <- ctx.repository.eventsDAO.merge(changed)
    }
     yield saved
  }

  override def possibleLocationsForEvent(event: Event): DomainContext[Try] => Try[Seq[ScheduleItem]] =
  { implicit ctx =>
      import shareevent.persistence.Repository.Objects._

      def unwrapTry[T](x:Try[T]):T =
        x match {
          case Success(x) => x
          case Failure(ex) => throw ex
        }

    def genSecheduleItems(eventId: Event.Id, locations:Seq[Location]):Try[Seq[ScheduleItem]] =
      Try {
        for {location <- locations
             times = unwrapTry(possibleTimesForLocation(location))
             time <- times
        } yield {
          ScheduleItem(eventId, location.id.get, time, Seq.empty)
        }
      }


    for {
        eventId <- event.id.toTry
        locations <- ctx.repository.locationDAO.query(
          locationMeta.select where locationMeta.capacity >= eventMeta.minParticipantsQuantity
        )
        scheduleItems <- genSecheduleItems(eventId,locations)
      } yield {
        scheduleItems
      }
  }

  override def possibleParticipantsInEvent(event: Event): DomainContext[Try] => Try[Seq[Person]] =
    implicit ctx => {
      import shareevent.persistence.Repository.Objects._

      //TODO: kluge
      def isInterestPlain(p:Person): Boolean =
        participantInterest(event,p)(ctx) match {
          case Success(x) => x
          case Failure(ex) => throw  ex
        }


        for {participants <- ctx.repository.personDAO.query(personMeta.select where personMeta.role === Role.Participant)
             selected <- Try (for(p <- participants if isInterestPlain(p)) yield p)
        } yield {
           selected
        }

    }

  def possibleTimesForLocation(l:Location)(implicit ctx:DomainContext[Try]):Try[Seq[DateTime]] = {

    for {locationId <- l.id.toTry
         location <- ctx.repository.locationDAO.retrieveExistent(locationId)
         } yield {
      val gaps = for {
        booking <- location.bookings
        booking2 <- location.bookings if booking2 != booking
        gap = booking.time.gap(booking2.time)
      } yield gap
      gaps.distinct.map(g => g.getStart)
    }

    /*
    // old variand without for comprehansion
    l.id match {
      case Some(x) => ctx.repository.locationDAO.retrieve(x.id) match {
        case Success(v) => v match {
          case Some(location) =>
            val gaps = for {
              booking <- location.bookings
              booking2 <- location.bookings if booking2 != booking
              gap = booking.time.gap(booking2.time)
            } yield gap
            gaps.distinct.map(g => g.getStart)
          case None => Seq()
        }
        case Failure(e) => Seq()
      }
      case None => Seq()
    }
    */

  }

}
