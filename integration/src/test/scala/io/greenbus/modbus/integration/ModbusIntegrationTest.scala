/**
 * Copyright 2011-2016 Green Energy Corp.
 *
 * Licensed to Green Energy Corp (www.greenenergycorp.com) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. Green Energy
 * Corp licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.greenbus.modbus.integration

import java.util.UUID

import akka.actor.{ ActorContext, ActorRef, ActorSystem }
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Model.ModelUUID
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{ BeforeAndAfterAll, FunSuite, Matchers }
import io.greenbus.msg.Session
import io.greenbus.msg.amqp.AmqpSettings
import io.greenbus.msg.qpid.QpidBroker
import io.greenbus.app.actor.{ AmqpConnectionConfig, ProtocolsEndpointStrategy }
import io.greenbus.app.actor.frontend.{ FrontendFactory, FrontendRegistrationConfig, MasterProtocol }
import io.greenbus.client.ServiceConnection
import io.greenbus.client.service.proto.CommandRequests.CommandSelect
import io.greenbus.client.service.proto.Commands
import io.greenbus.client.service.proto.Commands.{ CommandRequest, CommandResult, CommandStatus }
import io.greenbus.client.service.proto.FrontEnd.{ FrontEndConnectionStatus, FrontEndConnectionStatusNotification }
import io.greenbus.client.service.proto.Measurements.{ MeasurementNotification, Quality }
import io.greenbus.client.service.proto.ModelRequests.EntityKeySet
import io.greenbus.client.service.{ CommandService, ModelService }
import io.greenbus.integration.tools.ServiceWatcher
import io.greenbus.measproc.MeasurementProcessor
import io.greenbus.modbus.ModbusProtocol
import io.greenbus.modbus.conversion.ModbusConfig
import io.greenbus.services.{ CoreServices, ResetDatabase, ServiceManager }
import io.greenbus.util.UserSettings

import scala.collection.JavaConversions._
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ModbusIntegrationTest extends FunSuite with Matchers with LazyLogging with BeforeAndAfterAll {
  import io.greenbus.integration.tools.PollingUtils._
  import io.greenbus.modbus.integration.ModbusIntegrationConfiguration._

  val testConfigPath = "io.greenbus.test.cfg"

  val rootConfig = ConfigFactory.load()
  val slf4jConfig = ConfigFactory.parseString("""akka { loggers = ["akka.event.slf4j.Slf4jLogger"] }""")
  val config = slf4jConfig.withFallback(rootConfig)
  val system = ActorSystem("integrationTest", config)

  var services = Option.empty[ActorRef]
  var processor = Option.empty[ActorRef]
  var conn = Option.empty[ServiceConnection]
  var session = Option.empty[Session]

  var modbusMgr = Option.empty[ActorRef]
  var modbusEndpointMgr = Option.empty[ActorRef]

  val slave = new ModbusSlave(34001)

  var measWatcher = Option.empty[ServiceWatcher[MeasurementNotification]]
  var statusWatcher = Option.empty[ServiceWatcher[FrontEndConnectionStatusNotification]]

  override protected def beforeAll(): Unit = {

    ResetDatabase.reset(testConfigPath)

    logger.info("starting services")
    services = Some(system.actorOf(ServiceManager.props(testConfigPath, testConfigPath, CoreServices.runServices)))

    val amqpConfig = AmqpSettings.load(testConfigPath)
    val conn = ServiceConnection.connect(amqpConfig, QpidBroker, 1500)
    this.conn = Some(conn)

    val userConfig = UserSettings.load(testConfigPath)

    val session = pollForSuccess(500, 5000) {
      Await.result(conn.login(userConfig.user, userConfig.password), 500.milliseconds)
    }

    this.session = Some(session)

    ModbusIntegrationConfiguration.loadActions(ModbusIntegrationConfiguration.buildConfig("Device01", "Endpoint01", 34001, "modbus"), session)

    logger.info("starting processor")
    processor = Some(system.actorOf(MeasurementProcessor.buildProcessor(testConfigPath, testConfigPath, testConfigPath, 20000, "testNode")))

    Thread.sleep(500)
  }

  override protected def afterAll(): Unit = {
    slave.close()
    this.measWatcher.foreach(_.cancel())
    this.conn.foreach(_.disconnect())
  }

  def checkStatus(update: MeasurementNotification, name: String, v: Boolean) {
    update.getPointName should equal(name)
    update.getValue.hasBoolVal should equal(true)
    update.getValue.getBoolVal should equal(v)

    update.getValue.hasQuality should equal(true)
    update.getValue.getQuality.getValidity should equal(Quality.Validity.GOOD)
  }

  def checkAnalog(update: MeasurementNotification, name: String, v: Int) {
    update.getPointName should equal(name)
    update.getValue.hasIntVal should equal(true)
    update.getValue.getIntVal should equal(v)

    update.getValue.hasQuality should equal(true)
    update.getValue.getQuality.getValidity should equal(Quality.Validity.GOOD)
  }

  test("Initialized") {
    val session = this.session.get

    val (originals, measWatcher) = ServiceWatcher.measurementWatcherForOwner(session, "Device01")
    this.measWatcher = Some(measWatcher)

    val (originalStatuses, statusWatcher) = ServiceWatcher.statusWatcher(session, "Endpoint01")
    this.statusWatcher = Some(statusWatcher)

    val allUpdated = measWatcher.watcher.future { notes =>
      notes.map(_.getPointName).toSet == allPoints
    }

    val statusUpdated = statusWatcher.watcher.future { notes =>
      notes.exists(_.getValue.getState == FrontEndConnectionStatus.Status.COMMS_UP)
    }

    def protocolMgrFactory(context: ActorContext): MasterProtocol[ModbusConfig] = new ModbusProtocol

    val endpointStrategy = new ProtocolsEndpointStrategy(Set("modbus"))

    val amqpConfigFull = AmqpConnectionConfig.default(testConfigPath)

    val regConfig = FrontendRegistrationConfig(
      loginRetryMs = 1000,
      registrationRetryMs = 1000,
      releaseTimeoutMs = 20000,
      statusHeartbeatPeriodMs = 5000,
      lapsedTimeMs = 11000,
      statusRetryPeriodMs = 2000,
      measRetryPeriodMs = 2000,
      measQueueLimit = 1000,
      configRequestRetryMs = 1000)

    val endpointMgr = system.actorOf(FrontendFactory.create(
      amqpConfigFull, testConfigPath, endpointStrategy, protocolMgrFactory, ModbusProtocol, Seq(ModbusProtocol.configKey),
      connectionRepresentsLock = true,
      nodeId = UUID.randomUUID().toString,
      regConfig))

    this.modbusMgr = Some(endpointMgr)

    val initialUpdate = Await.result(allUpdated, 5000.milliseconds)

    val statusResults = Await.result(statusUpdated, 5000.milliseconds)

    initialUpdate.foreach { update =>
      update.getValue.hasQuality should equal(true)
      update.getValue.getQuality.hasValidity should equal(true)
      update.getValue.getQuality.getValidity should equal(Quality.Validity.GOOD)
    }

    initialUpdate.filter(up => statusPoints.contains(up.getPointName)).foreach { update =>
      update.getValue.hasBoolVal should equal(true)
      update.getValue.getBoolVal should equal(false)
    }

    initialUpdate.filter(up => analogPoints.contains(up.getPointName)).foreach { update =>
      update.getValue.hasIntVal should equal(true)
      update.getValue.getIntVal should equal(0)
    }
  }

  test("Measurement update") {
    val session = this.session.get
    val measWatcher = this.measWatcher.get
    measWatcher.watcher.reset()

    val measUpdated = measWatcher.watcher.future(_.nonEmpty)

    slave.setDiscreteInput(1, true)

    val updates = Await.result(measUpdated, 5000.milliseconds)

    updates.size should equal(1)
    val update = updates.head
    checkStatus(update, "Device01.Status02", true)

    measWatcher.watcher.reset()

    val analogUpdated = measWatcher.watcher.future(_.nonEmpty)

    slave.setInputRegister(0, 30)

    val analogUpdates = Await.result(analogUpdated, 5000.milliseconds)

    analogUpdates.size should equal(1)
    val analogUpdate = analogUpdates.head
    checkAnalog(analogUpdate, "Device01.Analog01", 30)
  }

  def issueControlTest(session: Session) {

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val control01 = commandNameMap("Device01.Control01")

    val controlSelect = CommandSelect.newBuilder().addCommandUuids(control01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(controlSelect), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder().setCommandUuid(control01.getUuid).setType(CommandRequest.ValType.NONE).build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    commandClient.deleteCommandLocks(List(selectResult.getId))
  }

  test("Command issuing") {
    val session = this.session.get

    slave.getDigitalOut(0) should equal(false)
    issueControlTest(session)
    slave.getDigitalOut(0) should equal(true)
  }

  test("Setpoint issuing") {
    val session = this.session.get

    slave.getHoldingRegister(0) should equal(0)

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint01 = commandNameMap("Device01.Setpoint01")

    val select = CommandSelect.newBuilder().addCommandUuids(setpoint01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setIntVal(120)
      .setType(Commands.CommandRequest.ValType.INT)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    slave.getHoldingRegister(0) should equal(120)

    val request2 = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setDoubleVal(45023.88)
      .setType(Commands.CommandRequest.ValType.DOUBLE)
      .build()

    val response2 = Await.result(commandClient.issueCommandRequest(request2), 5000.milliseconds)

    response2.getStatus should equal(Commands.CommandStatus.SUCCESS)

    slave.getHoldingRegister(0) should equal(45023)

    Await.result(commandClient.deleteCommandLocks(Seq(selectResult.getId())), 5000.milliseconds)
  }

  test("Setpoint negative") {
    val session = this.session.get

    slave.getHoldingRegister(0) should equal(45023)

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint01 = commandNameMap("Device01.Setpoint01")

    val select = CommandSelect.newBuilder().addCommandUuids(setpoint01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setIntVal(-3)
      .setType(Commands.CommandRequest.ValType.INT)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    slave.getHoldingRegisterSigned(0) should equal(-3)

    val request2 = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setDoubleVal(-30001.893)
      .setType(Commands.CommandRequest.ValType.DOUBLE)
      .build()

    val response2 = Await.result(commandClient.issueCommandRequest(request2), 5000.milliseconds)

    response2.getStatus should equal(Commands.CommandStatus.SUCCESS)

    slave.getHoldingRegisterSigned(0) should equal(-30001)

    Await.result(commandClient.deleteCommandLocks(Seq(selectResult.getId())), 5000.milliseconds)
  }

  test("Setpoint issuing const val") {
    val session = this.session.get

    slave.getHoldingRegister(2) should equal(0)

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint01 = commandNameMap("Device01.Setpoint03")

    val select = CommandSelect.newBuilder().addCommandUuids(setpoint01.getUuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(setpoint01.getUuid)
      .setIntVal(120)
      .setType(Commands.CommandRequest.ValType.INT)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    response.getStatus should equal(Commands.CommandStatus.SUCCESS)

    // value overridden by configuration
    slave.getHoldingRegister(2) should equal(33)
  }

  def issueSp(session: Session, uuid: ModelUUID, v: Long): CommandResult = {
    val commandClient = CommandService.client(session)

    val select = CommandSelect.newBuilder().addCommandUuids(uuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(uuid)
      .setIntVal(v)
      .setType(Commands.CommandRequest.ValType.INT)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    Await.result(commandClient.deleteCommandLocks(Seq(selectResult.getId)), 5000.milliseconds)

    response
  }
  def issueSp(session: Session, uuid: ModelUUID, v: Double): CommandResult = {
    val commandClient = CommandService.client(session)

    val select = CommandSelect.newBuilder().addCommandUuids(uuid).build

    val selectResult = Await.result(commandClient.selectCommands(select), 5000.milliseconds)

    val request = Commands.CommandRequest.newBuilder()
      .setCommandUuid(uuid)
      .setDoubleVal(v)
      .setType(Commands.CommandRequest.ValType.DOUBLE)
      .build()

    val response = Await.result(commandClient.issueCommandRequest(request), 5000.milliseconds)

    Await.result(commandClient.deleteCommandLocks(Seq(selectResult.getId)), 5000.milliseconds)

    response
  }

  // setpoint04, 0x00F0,  0000 0000 1111 0000
  // setpoint05, 0x1E00,  0001 1110 0000 0000
  test("Setpoint masking") {
    val session = this.session.get

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint04 = commandNameMap("Device01.Setpoint04")
    val setpoint05 = commandNameMap("Device01.Setpoint05")

    slave.getHoldingRegister(3) should equal(0)

    issueSp(session, setpoint04.getUuid, 9).getStatus should equal(CommandStatus.SUCCESS)
    slave.getHoldingRegister(3) should equal(0x90) // 9 << 4

    issueSp(session, setpoint05.getUuid, 9).getStatus should equal(CommandStatus.SUCCESS)
    slave.getHoldingRegister(3) should equal(0x1290) // (9 << 4) | (9 << 9)

    slave.setHoldingRegister(3, 0xB39D) // 1011 0011 1001 1101

    issueSp(session, setpoint05.getUuid, 6).getStatus should equal(CommandStatus.SUCCESS)
    slave.getHoldingRegister(3) should equal(0xAD9D) // 1010 1101 1001 1101

    issueSp(session, setpoint04.getUuid, 6).getStatus should equal(CommandStatus.SUCCESS)
    slave.getHoldingRegister(3) should equal(0xAD6D) // 1010 1101 0110 1101
  }

  test("Setpoint multi write") {
    val session = this.session.get

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint06 = commandNameMap("Device01.Setpoint06")
    val setpoint07 = commandNameMap("Device01.Setpoint07")
    val setpoint08 = commandNameMap("Device01.Setpoint08")

    def check(v: Seq[Int]): Unit = {
      slave.getHoldingRegister(4) should equal(v(0))
      slave.getHoldingRegister(5) should equal(v(1))
      slave.getHoldingRegister(6) should equal(v(2))
      slave.getHoldingRegister(7) should equal(v(3))
    }

    check(Seq(0, 0, 0, 0))

    issueSp(session, setpoint06.getUuid, 9).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(9, 0, 0, 0))

    issueSp(session, setpoint06.getUuid, 60001).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(60001, 0, 0, 0))

    issueSp(session, setpoint07.getUuid, 23).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(23, 0, 0, 0))

    issueSp(session, setpoint07.getUuid, 300000088).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(41816, 4577, 0, 0))

    issueSp(session, setpoint08.getUuid, 0x4000C00FBB800A03L).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(0x0A03, 0xBB80, 0xC00F, 0x4000))
  }

  test("Setpoint multi write float") {
    val session = this.session.get

    val modelClient = ModelService.client(session)
    val commandClient = CommandService.client(session)

    val commands = Await.result(modelClient.getCommands(EntityKeySet.newBuilder().addAllNames(allCommands.toSeq).build()), 5000.milliseconds)
    val commandNameMap = commands.map(c => (c.getName, c)).toMap

    val setpoint09 = commandNameMap("Device01.Setpoint09")
    val setpoint10 = commandNameMap("Device01.Setpoint10")

    slave.setHoldingRegister(4, 0)
    slave.setHoldingRegister(5, 0)
    slave.setHoldingRegister(6, 0)
    slave.setHoldingRegister(7, 0)

    def check(v: Seq[Int]): Unit = {
      slave.getHoldingRegister(4) should equal(v(0))
      slave.getHoldingRegister(5) should equal(v(1))
      slave.getHoldingRegister(6) should equal(v(2))
      slave.getHoldingRegister(7) should equal(v(3))
    }

    check(Seq(0, 0, 0, 0))

    issueSp(session, setpoint09.getUuid, 4.0).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(0, 0x4080, 0, 0))

    // -500.234 = 0xc3fa1df4
    issueSp(session, setpoint09.getUuid, -500.234).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(0x1DF4, 0xC3FA, 0, 0))

    // 400b333333333333
    issueSp(session, setpoint10.getUuid, 3.4).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(0x3333, 0x3333, 0x3333, 0x400b))

    // c0b593d8ef34d6a1
    issueSp(session, setpoint10.getUuid, -5523.8474).getStatus should equal(CommandStatus.SUCCESS)
    check(Seq(0xd6a1, 0xef34, 0x93d8, 0xc0b5))
  }

}
