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

import com.typesafe.scalalogging.LazyLogging
import io.greenbus.client.service.proto.Processing.Filter.FilterType
import io.greenbus.client.service.proto.{ Model, Processing }
import io.greenbus.client.service.proto.Processing._
import io.greenbus.loader.set.Actions._
import io.greenbus.loader.set.{ ByteArrayValue, Mdl, NamedEntId, Upload }
import io.greenbus.msg.Session

object ModbusIntegrationConfiguration extends LazyLogging {

  val statusPoints = Set("Device01.Status01", "Device01.Status02")
  val analogPoints = Set("Device01.Analog01", "Device01.Analog02")
  val allPoints = statusPoints union analogPoints

  val controls = Set("Device01.Control01", "Device01.Control02")
  val setpoints = Set("Device01.Setpoint01", "Device01.Setpoint02", "Device01.Setpoint03", "Device01.Setpoint04", "Device01.Setpoint05", "Device01.Setpoint06", "Device01.Setpoint07", "Device01.Setpoint08")
  val allCommands = controls union setpoints

  def loadActions(actionSet: ActionsList, session: Session): Unit = {
    Upload.push(session, actionSet, Seq())
  }

  def filterTrigger() = {

    val trigger = Trigger.newBuilder()
      .setFilter(Filter.newBuilder().setType(FilterType.DUPLICATES_ONLY))
      .addActions(Action.newBuilder()
        .setActionName("Filter")
        .setType(Processing.ActivationType.LOW)
        .setSuppress(true))
      .build()

    TriggerSet.newBuilder().addTriggers(trigger).build()
  }

  def buildConfig(deviceName: String, endpointName: String, port: Int, protocol: String): ActionsList = {
    import Mdl._

    val cache = new ActionCache

    cache.entityPuts += PutEntity(None, deviceName, Set("Equipment"))

    cache.pointPuts += PutPoint(None, s"$deviceName.Status01", Set("Status"), Model.PointCategory.STATUS, "status")
    cache.pointPuts += PutPoint(None, s"$deviceName.Status02", Set("Status"), Model.PointCategory.STATUS, "status")

    cache.pointPuts += PutPoint(None, s"$deviceName.Analog01", Set("Analog"), Model.PointCategory.ANALOG, "kW")
    cache.pointPuts += PutPoint(None, s"$deviceName.Analog02", Set("Analog"), Model.PointCategory.ANALOG, "V")

    cache.commandPuts += PutCommand(None, s"$deviceName.Control01", Set("Control"), Model.CommandCategory.CONTROL, s"$deviceName.Control01")
    cache.commandPuts += PutCommand(None, s"$deviceName.Control02", Set("Control"), Model.CommandCategory.CONTROL, s"$deviceName.Control02")

    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint01", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint01")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint02", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint02")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint03", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint03")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint04", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint04")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint05", Set("Setpoint"), Model.CommandCategory.SETPOINT_DOUBLE, s"$deviceName.Setpoint05")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint06", Set("Setpoint"), Model.CommandCategory.SETPOINT_INT, s"$deviceName.Setpoint06")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint07", Set("Setpoint"), Model.CommandCategory.SETPOINT_INT, s"$deviceName.Setpoint07")
    cache.commandPuts += PutCommand(None, s"$deviceName.Setpoint08", Set("Setpoint"), Model.CommandCategory.SETPOINT_INT, s"$deviceName.Setpoint08")

    cache.endpointPuts += PutEndpoint(None, endpointName, Set(), protocol)

    val pointNames = cache.pointPuts.result().map(_.name)
    val commandNames = cache.commandPuts.result().map(_.name)
    val pointAndCommandNames = pointNames ++ commandNames
    pointAndCommandNames.foreach { n =>
      cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(deviceName), "owns", NamedEntId(n)))
      cache.edgePuts += PutEdge(EdgeDesc(NamedEntId(endpointName), "source", NamedEntId(n)))
    }

    val modbusConfig = modbusMasterConfig(deviceName, port)
    cache.keyValuePutByNames += PutKeyValueByName(endpointName, "protocolConfig", ByteArrayValue(modbusConfig.getBytes("UTF-8")))

    val filterBytes = filterTrigger().toByteArray
    pointNames.foreach { name =>
      cache.keyValuePutByNames += PutKeyValueByName(name, "triggerSet", ByteArrayValue(filterBytes))
    }

    cache.result()
  }

  def modbusMasterConfig(deviceName: String, port: Int) =
    s"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        |<Master xmlns="io.greenbus.protocol.modbus">
        |    <TCPClient Address="127.0.0.1" Port="34001" />
        |    <Protocol Type="TCPIP" />
        |    <Stack Address="3"/>
        |    <Polls>
        |        <Poll Type="DiscreteInput" Start="0" Count="2" IntervalMs="1000" TimeoutMs="2000"/>
        |        <!-- <Poll Type="CoilStatus" Start="0" Count="2" IntervalMs="1000" TimeoutMs="2000"/> -->
        |        <Poll Type="InputRegister" Start="0" Count="2" IntervalMs="2000" TimeoutMs="2000"/>
        |        <!-- <Poll Type="HoldingRegister" Start="0" Count="2" IntervalMs="5000" TimeoutMs="2000"/> -->
        |    </Polls>
        |    <DiscreteInputMap>
        |        <Mapping Index="0" Name="$deviceName.Status01" Unit="raw"/>
        |        <Mapping Index="1" Name="$deviceName.Status02" Unit="raw"/>
        |    </DiscreteInputMap>
        |    <InputRegisterMap>
        |        <Mapping Index="0" Type="UInt16" Name="$deviceName.Analog01" Unit="raw"/>
        |        <Mapping Index="1" Type="UInt16" Name="$deviceName.Analog02" Unit="raw"/>
        |    </InputRegisterMap>
        |    <CommandMap>
        |         <Mapping Name="$deviceName.Control01" Index="0" CommandType="Coil" ConstBoolValue="true"/>
        |         <Mapping Name="$deviceName.Control02" Index="1" CommandType="Coil" ConstBoolValue="false"/>
        |         <Mapping Name="$deviceName.Setpoint01" Index="0" CommandType="Register"/>
        |         <Mapping Name="$deviceName.Setpoint02" Index="1" CommandType="Register"/>
        |         <Mapping Name="$deviceName.Setpoint03" Index="2" CommandType="Register" ConstIntValue="33"/>
        |         <Mapping Name="$deviceName.Setpoint04" Index="3" CommandType="Register" BitMaskToUpdate="0xF0" ShiftLeft="4"/>
        |         <Mapping Name="$deviceName.Setpoint05" Index="3" CommandType="Register" BitMaskToUpdate="0x1E00" ShiftLeft="9"/>
        |         <Mapping Name="$deviceName.Setpoint06" Index="4" CommandType="MultipleRegisters" RegisterCount="1"/>
        |         <Mapping Name="$deviceName.Setpoint07" Index="4" CommandType="MultipleRegisters" RegisterCount="2"/>
        |         <Mapping Name="$deviceName.Setpoint08" Index="4" CommandType="MultipleRegisters" RegisterCount="4"/>
        |    </CommandMap>
        |</Master>
        |""".stripMargin

}
