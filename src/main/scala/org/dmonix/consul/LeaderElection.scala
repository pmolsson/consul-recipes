package org.dmonix.consul

import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success, Try}

/**
  * Factory for creating candidates for leader election
  * @author Peter Nerg
  */
object LeaderElection {
  
  private[consul] val sessionTTL = 10.seconds
  
  /**
    * Create a candidate for leader election
    * @param consulHost Consul host
    * @param groupName The election group to join
    * @param info Optional information to be stored on the election key if/when this candidate becomes elected 
    * @param observer Optional observer to receive election updates
    * @return
    */
  def joinLeaderElection(consulHost: ConsulHost, groupName:String, info: Option[String] = None, observer:Option[ElectionObserver] = None): Try[Candidate] = {
    val sender = new ConsulHttpSender(consulHost)
    val consul = new Consul(sender) with SessionUpdater
    consul.createSession(Session(name = Option(groupName), ttl = Option(sessionTTL))).map { sessionID =>
      consul.registerSession(sessionID, sessionTTL)
      new CandidateImpl(consul, groupName, sessionID, info, observer)
    }
  }
}

/**
  * Observer to be notified for changes in election state.
  * @author Peter Nerg
  */
trait ElectionObserver {
  /**
    * This candidate has been elected as leader.
    */
  def elected():Unit

  /**
    * This candidate has lost leadership.
    */
  def unElected():Unit
}

/**
  * Represents a candidate in the leader election
  */
trait Candidate {
  /**
    * If this candidate has been elected as leader.
    * @return
    */
  def isLeader:Boolean

  /**
    * Leaves the election process.
    * Should this candidate currently be leader the leadership is released.
    * Once invoked this candidate will no longer be part of the election process, refer to ''LeaderElection.joinLeaderElection'' 
    * to create a new candidate and re-join the election
    */
  def leave():Unit
}



import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
  * Implements the election candidate. 
  * @param consul
  * @param groupName
  * @param sessionID
  * @param info
  * @param observer
  * @author Peter Nerg
  */
private class CandidateImpl(consul:Consul with SessionUpdater, groupName:String, sessionID:SessionID, info: Option[String], observer:Option[ElectionObserver]) extends Candidate {
  private val logger = LoggerFactory.getLogger(classOf[Candidate])
  
  private val setKey = SetKeyValue(
    key = s"leader-election/$groupName",
    value = info,
    acquire = Option(sessionID)
  )
  
  private val waitDuration = 60.seconds
  private var isActive:Boolean = true
  private var isLeaderState = attemptToTakeLeadership() //immediately try to cease leadership
  private var modifyIndex = 0

  logger.info(s"Session [$sessionID] joined leader election for group [$groupName], initial leader state is [$isLeaderState]")

  waitForElectionUpdate()

  override def isLeader: Boolean = isLeaderState

  override def leave(): Unit = {
    isActive = false
    consul.storeKeyValue(setKey.copy(acquire = None, release = Option(sessionID), value = None)) //release the ownership, we do this even if we don't own the key doesn't matter
    consul.destroySession(sessionID) //delete our session
    consul.unregisterSession(sessionID)
    if(isLeaderState) 
      notifyUnElected()
    isLeaderState = false
    logger.info(s"Session [$sessionID] has left the election group [$groupName]")
  }

  private def attemptToTakeLeadership():Boolean = {
    consul.storeKeyValue(setKey) match {
      case Success(true) if !isLeader => //acquired leadership
        notifyElected()
        true
      case Success(false) if isLeader =>  //lost leadership
        notifyUnElected()
        false
      case Success(newLeaderState) => //unchanged state 
        newLeaderState
      case _ => //failed to access Consul
        false
    }
  }
  
  private def waitForElectionUpdate():Unit = {
    Future(consul.readKeyValueWhenChanged(setKey.key, modifyIndex+1, waitDuration).get) //+1 to modifyIndex to block on the next value
      .filter(_ => isActive) //fail the Future in case we're no longer active
      .onComplete {
        //successful response from Consul with a key
        case Success(Some(keyValue)) =>
          modifyIndex = keyValue.modifyIndex
          logger.debug(s"Session [$sessionID] has read updated election data [$keyValue] and is in leader state [$isLeaderState]")
          keyValue.session match {
            //election node has no owner, fight for ownership
            //current owner yielded or the owning session was terminated  
            case None => 
              isLeaderState = attemptToTakeLeadership()

            //we have become owner, notify of the change...this should really not be possible
            case Some(ownerSession) if (ownerSession == sessionID) && !isLeader =>
              notifyElected()
              
            //we have lost ownership, notify of the change...a manual change in Consul can cause this
            case Some(ownerSession) if (ownerSession != sessionID) && isLeader =>
              notifyUnElected()
              
            //no change to owner state, just ignore  
            case _ =>
          }
          waitForElectionUpdate()
        case Success(None) => //got no data, file has been removed
          //FIXME what to do in case the key is removed
          isLeaderState = attemptToTakeLeadership()
          waitForElectionUpdate()
        //future/try failed...do a new get on the key again
        case Failure(ex) if isActive =>
          logger.warn(s"Session [$sessionID] in group [$groupName] failed to read election state due to [${ex.getMessage}]")
          waitForElectionUpdate()
        //future failed du to the 'filter' where we decided we're no longer active, just ignore and exit  
        case _ if !isActive =>
      }
  }
  
  private def notifyElected():Unit = {
    logger.info(s"Session [$sessionID] has acquired leadership in group [$groupName]")
    observer.foreach(_.elected())
  }
  
  private def notifyUnElected():Unit = {
    logger.info(s"Session [$sessionID] has lost leadership in group [$groupName]")
    observer.foreach(_.unElected())
  }
  
}