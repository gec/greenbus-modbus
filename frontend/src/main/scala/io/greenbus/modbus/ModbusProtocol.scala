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
package io.greenbus.modbus

import io.greenbus.app.actor.frontend.{ ProtocolConfigurer, ProtocolCommandAcceptor, MasterProtocol }
import io.greenbus.modbus.conversion._
import io.greenbus.client.service.proto.Model.{ EntityKeyValue, ModelUUID, Endpoint }
import org.totalgrid.modbus.{ ModbusManager, ModbusMaster }
import io.greenbus.app.actor.frontend.StackStatusUpdated
import io.greenbus.app.actor.frontend.MeasurementsPublished
import io.greenbus.modbus.xml.ProtocolType

object ModbusProtocol extends ProtocolConfigurer[ModbusConfig] {
  private val marshaller = new ModbusXmlMarshaller

  val configKey = "protocolConfig"

  def extractConfig(config: EntityKeyValue): Option[ModbusConfig] = {
    ModbusConfig.convertXml(marshaller, config)
  }

  def evaluate(endpoint: Endpoint, configFiles: Seq[EntityKeyValue]): Option[ModbusConfig] = {
    configFiles.find(_.getKey == configKey).flatMap(extractConfig)
  }

  def equivalent(latest: ModbusConfig, previous: ModbusConfig): Boolean = {
    false
  }
}

class ModbusProtocol extends MasterProtocol[ModbusConfig] {

  private val modbus = ModbusManager.start(8192, 6)

  private var stackMap = Map.empty[ModelUUID, ModbusMaster]

  def add(endpoint: Endpoint, protocolConfig: ModbusConfig, publish: (MeasurementsPublished) => Unit, statusUpdate: (StackStatusUpdated) => Unit): ProtocolCommandAcceptor = {

    remove(endpoint.getUuid)

    val deviceObs = new DeviceObserverAdapter(protocolConfig.measMap, publish)
    val channelObs = new ChannelObserverAdapter(statusUpdate)

    val connectionTimeoutMs = 5000
    val connectionRetryMs = 5000
    val operationTimeoutMs = 5000

    val master = protocolConfig.protocol match {
      case ProtocolType.RTU => modbus.addRtuMaster(protocolConfig.ipAddress, protocolConfig.port, protocolConfig.modbusAddress, deviceObs, channelObs, protocolConfig.polls, connectionTimeoutMs, connectionRetryMs, operationTimeoutMs)
      case ProtocolType.TCPIP => modbus.addTcpMaster(protocolConfig.ipAddress, protocolConfig.port, protocolConfig.modbusAddress, deviceObs, channelObs, protocolConfig.polls, connectionTimeoutMs, connectionRetryMs, operationTimeoutMs)
    }

    stackMap += (endpoint.getUuid -> master)

    new CommandHandlerAdapter(protocolConfig.cmdMap, master)
  }

  def remove(endpointUuid: ModelUUID) {
    stackMap.get(endpointUuid).foreach(_.close())
  }

  def shutdown() {
    modbus.shutdown()
  }
}

