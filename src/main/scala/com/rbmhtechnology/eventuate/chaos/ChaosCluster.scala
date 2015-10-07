/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate.chaos

import java.util.concurrent.ThreadLocalRandom

import akka.actor._

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util._

object ChaosCluster extends App {
  case object StartNodes
  case class KillNodes(nodes: Seq[String])

  val system = ActorSystem("cluster")
  val settings = new ChaosSettings(system.settings.config)
  val cluster = system.actorOf(Props(new ChaosCluster).withDispatcher("coordinator-dispatcher"))

  def random =
    ThreadLocalRandom.current

  def randomNodes: Seq[String] = Random.shuffle(settings.nodes).take(settings.nodesDownMax)

  def clusterDirectory: String = settings.clusterDirectory

  def randomStartDelay: Long =
    ThreadLocalRandom.current.nextLong(settings.delayStartMinMillis, settings.delayStartMaxMillis)

  def randomStopDelay: Long =
    ThreadLocalRandom.current.nextLong(settings.delayStopMinMillis, settings.delayStopMaxMillis)


  while (! StdIn.readLine.contains("exit"))
    cluster ! KillNodes(randomNodes)

    system.stop(cluster)
}

class ChaosCluster extends Actor with ChaosCommands {
  import ChaosCluster._
  import context.dispatcher

  private var schedule: Option[Cancellable] = None

  def receive =
    clusterUp

  def clusterUp: Receive = {
    case KillNodes(nodes) =>
      killNodes(nodes) match {
        case Success(_) =>
          println(s"Node(s) stopped. waiting for node restart...")
          context.become(nodesDown(nodes))
          schedule = Some(schedule(StartNodes, randomStartDelay))
        case Failure(e) =>
          println(s"Node(s) stopping failed: ${e.getMessage}")
          context.stop(self)
      }
  }

  def nodesDown(nodes: Seq[String]): Receive = {
    case StartNodes =>
      startNodes(nodes) match {
        case Success(_) =>
          println("""Shutdown Node(s) restarted. Type something to stop a random container or "exit" to shutdown...""")
          context.become(clusterUp)
          schedule = Some(schedule(StartNodes, randomStartDelay))
        case Failure(e) =>
          println(s"Node(s) starting failed: ${e.getMessage}")
          context.stop(self)
      }
  }

  override def preStart(): Unit = {
    startCluster(clusterDirectory) match {
      case Success(_) =>
        println("""Cluster started. Type something to stop a random container or "exit" to shutdown... """)
      case Failure(e) =>
        println(s"Cluster starting failed: ${e.getMessage}")
        context.stop(self)
    }
  }

  override def postStop(): Unit = {
    schedule.foreach(_.cancel())
    stopCluster(clusterDirectory) match {
      case Success(_) => println(s"Cluster stopped")
      case Failure(e) => println(s"Cluster stopping failed: ${e.getMessage}")
    }
    context.system.terminate()
  }

  private def schedule(event: Any, delay: Long) =
    context.system.scheduler.scheduleOnce(delay.millis, self, event)
}

