/**
 * Copyright (C) 2010-2011, FuseSource Corp.  All rights reserved.
 *
 *     http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */

package org.fusesource.fabric.apollo.cluster

import org.scalatest.matchers.ShouldMatchers
import org.apache.activemq.apollo.util.FileSupport._
import org.fusesource.hawtdispatch._
import java.util.concurrent.TimeUnit._
import java.util.concurrent.TimeUnit
import org.apache.activemq.apollo.stomp.StompClient
import java.net.InetSocketAddress
import org.apache.activemq.apollo.broker.{Broker, Queue}
import org.apache.activemq.apollo.dto.{BrokerDTO, XmlCodec, QueueDestinationDTO}
import java.util.Properties
import org.apache.activemq.apollo.util.{ServiceControl, Dispatched}

/**
 */
class ClusterBrokerTest extends ZkFunSuiteSupport with ShouldMatchers {

  var brokers = List[Broker]()
  var broker_a:Broker = _
  var broker_b:Broker = _

  var cluster_a:ClusterConnector = _
  var cluster_b:ClusterConnector = _

  var client = new StompClient
  var clients = List[StompClient]()

  override protected def beforeEach(): Unit = {
    super.beforeEach
    def cluster_connector(broker:Broker) = broker.connectors.values.find(_.isInstanceOf[ClusterConnector]).map(_.asInstanceOf[ClusterConnector]).get

    broker_a = create_cluster_broker("apollo-cluster.xml", "a")
    cluster_a = cluster_connector(broker_a)
    broker_b = create_cluster_broker("apollo-cluster.xml", "b")
    cluster_b = cluster_connector(broker_b)
  }


  override protected def afterEach(): Unit = {
    clients.foreach(_.close)
    clients = Nil
    brokers.foreach(x=> ServiceControl.stop(x, "Stopping broker: "+x))
    brokers = Nil
    super.afterEach
  }

  def connect(broker:Broker):StompClient = connect(broker.get_socket_address.asInstanceOf[InetSocketAddress].getPort)

  def connect(port:Int):StompClient = {
    val c = new StompClient
    clients ::= c
    c.open("localhost", port )
    c.write(
      "CONNECT\n" +
      "accept-version:1.1\n" +
      "host:localhost\n" +
      "\n")
    val frame = c.receive
    frame should startWith("CONNECTED\n")
    c
  }

  def create_cluster_broker(file:String, id:String) = {
    val broker = new Broker
    val base = basedir / "node-data" / id

    val p = new Properties()
    p.setProperty("apollo.base", base.getCanonicalPath)
    p.setProperty("apollo.cluster.id", id)
    p.setProperty("zk.url", zk_url)

    val config = using(getClass.getResourceAsStream(file)) { is =>
      XmlCodec.decode(classOf[BrokerDTO], is, p)
    }

    debug("Starting broker");
    broker.config = config
    broker.tmp = base / "tmp"
    broker.tmp.mkdirs
    ServiceControl.start(broker, "starting broker "+id)

    brokers ::= broker
    broker
  }

  test("sending and subscribe on a cluster slave") {

    // Both brokers should establish peer connections ...
    for( broker <- List(cluster_a, cluster_b) ) {
      within(5, SECONDS) ( access(broker){ broker.peers.size } should be > 0 )
    }

    val router_a = router(broker_a)
    val router_b = router(broker_b)

    router_a should not( be === router_b )

    // Lets get a cluster destination.. only one of them should be picked as the master..
    val dest_a = access(router_a)(router_a._get_or_create_destination(new QueueDestinationDTO(Array("test")), null)).success.asInstanceOf[ClusterRouter#ClusterDestination[Queue]]
    val dest_b = access(router_b)(router_b._get_or_create_destination(new QueueDestinationDTO(Array("test")), null)).success.asInstanceOf[ClusterRouter#ClusterDestination[Queue]]

    (access(router_a)(dest_a.is_master) ^ access(router_b)(dest_b.is_master)) should be === true

    // sort out which is the master and which is the slave..
    val (master, slave, master_dest, slave_dest) = if (dest_a.is_master) {
      (broker_a, broker_b, dest_a, dest_b)
    } else {
      (broker_b, broker_a, dest_b, dest_a)
    }

    // Lets setup some stomp connections..
    val slave_client = connect(slave)

    val MESSAGE_COUNT = 1000

    // send a messages on the slave.. it should get sent to the master.
    for( i <- 1 to MESSAGE_COUNT ) {
      slave_client.write(
        """|SEND
           |destination:/queue/test
           |
           |#%d""".format(i).stripMargin)

    }

    // that message should end up on the the master's queue...
    val master_queue = master_dest.local

    within(10, SECONDS) ( access(master_queue){ master_queue.queue_items } should be === MESSAGE_COUNT )

    println("============================================================")
    println("subscribing...")
    println("============================================================")

    // receive a message on the slave..
    slave_client.write(
      """|SUBSCRIBE
         |destination:/queue/test
         |id:0
         |
         |""".stripMargin)

    println("waiting for messages...")
    for( i <- 1 to MESSAGE_COUNT ) {
      val frame = slave_client.receive()
      frame should startWith("MESSAGE\n")
      frame should endWith("\n\n#"+i)
    }

    // master queue should drain.
    within(5, SECONDS) ( access(master_queue){ master_queue.queue_items } should be === 0 )

  }

  ignore("Migrate a queue") {

    // Both brokers should establish peer connections ...
    for( broker <- List(cluster_a, cluster_b) ) {
      within(5, SECONDS) ( access(broker){ broker.peers.size } should be > 0 )
    }

    val router_a = router(broker_a)
    val router_b = router(broker_b)

    router_a should not( be === router_b )

    // Lets get a cluster destination.. only one of them should be picked as the master..
    val dest_a = access(router_a)(router_a._get_or_create_destination(new QueueDestinationDTO(Array("test")), null)).success.asInstanceOf[ClusterRouter#ClusterDestination[Queue]]
    val dest_b = access(router_b)(router_b._get_or_create_destination(new QueueDestinationDTO(Array("test")), null)).success.asInstanceOf[ClusterRouter#ClusterDestination[Queue]]

    (access(router_a)(dest_a.is_master) ^ access(router_b)(dest_b.is_master)) should be === true

    // sort out which is the master and which is the slave..
    val (master, slave, master_dest, slave_dest) = if (dest_a.is_master) {
      (cluster_a, cluster_b, dest_a, dest_b)
    } else {
      (cluster_b, cluster_a, dest_b, dest_a)
    }

    // Lets setup some stomp connections..
    val slave_client = connect(slave.broker)

    // send a message on the slave.. it should get sent to the master.
    slave_client.write(
      """|SEND
         |destination:/queue/test
         |
         |#1""".stripMargin)

    // that message should end up on the the master's queue...
    val master_queue = master_dest.local
    val slave_queue = slave_dest.local

    // verify master queue has the message.
    within(5, SECONDS) ( access(master_queue){ master_queue.queue_items } should be === 1 )
    // slave queue should not have the message.
    access(slave_queue){ slave_queue.queue_items } should be === 0

    // Changing the cluster weight of the master to zero, should make him a slave.
    println("Master is "+master.node_id+", changing weight to convert to slave.")
    master.set_cluster_weight(0)

//    Thread.sleep(1000*1000)
    // slave becomes master.. message has to move to the new queue.
    within(5, SECONDS) ( access(slave_queue){ slave_queue.queue_items } should be === 1 )

  }


  def router(bs:Broker) = bs.default_virtual_host.router.asInstanceOf[ClusterRouter]

  def within[T](timeout:Long, unit:TimeUnit)(func: => Unit ):Unit = {
    val start = System.currentTimeMillis
    var amount = unit.toMillis(timeout)
    var sleep_amount = amount / 100
    var last:Throwable = null

    if( sleep_amount < 1 ) {
      sleep_amount = 1
    }
    try {
      func
      return
    } catch {
      case e:Throwable => last = e
    }

    while( (System.currentTimeMillis-start) < amount ) {
      Thread.sleep(sleep_amount)
      try {
        func
        return
      } catch {
        case e:Throwable => last = e
      }
    }

    throw last
  }

  def access[T](d:Dispatched)(action: =>T) = {
    (d.dispatch_queue !! { action }).await()
  }

}
