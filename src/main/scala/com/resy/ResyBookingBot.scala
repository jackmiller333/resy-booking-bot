package com.resy

import akka.actor.ActorSystem
import org.apache.logging.log4j.scala.Logging
import org.joda.time.DateTime
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object ResyBookingBot extends Logging {

  def main(args: Array[String]): Unit = {
    logger.info("Starting Resy Booking Bot") 

    val resyConfig = 
      if (args.length == 0) ConfigSource.resources("resyConfig.conf")
      else ConfigSource.resources(args(0))
    val keysConfig =
      if (args.length < 2) ConfigSource.resources("main.env")
      else ConfigSource.resources(args(1))
    val bufferDays = resyConfig.at("bufferDays").loadOrThrow[BufferDays]
    val resyKeys   = keysConfig.at("resyKeys").loadOrThrow[ResyKeys]
    val resDetails = resyConfig.at("resDetails").loadOrThrow[ReservationDetails]
    val snipeTime  = resyConfig.at("snipeTime").loadOrThrow[SnipeTime]  
    val tableSize  = 
      if (args.length == 3) args(2).toInt
      else resDetails.partySize

    val system      = ActorSystem("System")
    val dateTimeNow = DateTime.now
    val todaysSnipeTime = dateTimeNow
      .withHourOfDay(snipeTime.hours)
      .withMinuteOfHour(snipeTime.minutes)
      .withSecondOfMinute(0)
      .withMillisOfSecond(0)

    val nextSnipeTime =
      if (todaysSnipeTime.getMillis > dateTimeNow.getMillis) todaysSnipeTime
      else todaysSnipeTime.plusDays(1)

    val millisUntilTomorrow = nextSnipeTime.getMillis - DateTime.now.getMillis - 2000
    val hoursRemaining      = millisUntilTomorrow / 1000 / 60 / 60
    val minutesRemaining    = millisUntilTomorrow / 1000 / 60 - hoursRemaining * 60
    val secondsRemaining =
      millisUntilTomorrow / 1000 - hoursRemaining * 60 * 60 - minutesRemaining * 60

    val reserveDate = dateTimeNow.plusDays(bufferDays.days).toString("YYYY-MM-dd")

    val newResDetails   = ReservationDetails(
      reserveDate,
      tableSize,
      resDetails.venueId,
      resDetails.resTimeTypes
    )

    logger.info(s"Attempting to get a reservation on $reserveDate for $tableSize")
    logger.info(newResDetails
    )
    logger.info(s"Next snipe time: $nextSnipeTime")
    logger.info(
      s"Sleeping for $hoursRemaining hours, $minutesRemaining minutes, and $secondsRemaining seconds"
    )

    val resyApi             = new ResyApi(resyKeys)
    val resyClient          = new ResyClient(resyApi)
    val resyBookingWorkflow = new ResyBookingWorkflow(resyClient, newResDetails)

    system.scheduler.scheduleOnce(millisUntilTomorrow millis) {
      resyBookingWorkflow.run()

      logger.info("Shutting down Resy Booking Bot")
      System.exit(0)
    }
  }
}
