package org.dmonix.consul

import scala.concurrent.duration.DurationInt
import scala.util._

/**
  * Simple app to illustrate the usage of a ''Semaphore''.   
  * Start multiple instances to have competing users to the Semaphore.  
  * Requires Consul to be listening to localhost:8500 (see README.md for example to start Consul)
  * @author Peter Nerg
  */
object ManualSemaphore extends App {
  private val blocker = new java.util.concurrent.Semaphore(0)
  private val semaphore = Semaphore(ConsulHost("localhost"), "example-semaphore", 1).get
  
  //catches shutdown of the app and releases any potential permits
  sys.addShutdownHook {
    semaphore.release()
    blocker.release()
  }

  semaphore.tryAcquire(5.minutes) match {
    case Success(res) => println("Have a permit = "+res)
    case Failure(ex) => ex.printStackTrace()
  }
  
  //hold here for the lifetime of the app
  blocker.acquire()

}
