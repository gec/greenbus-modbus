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
package io.greenbus.modbus.conversion

import io.greenbus.modbus.xml._
import io.greenbus.client.service.proto.Model.EntityKeyValue
import javax.xml.bind.JAXBContext
import java.io.ByteArrayInputStream

import com.typesafe.scalalogging.LazyLogging
import org.totalgrid.modbus.poll.Poll

import scala.collection.JavaConversions._

case class ModbusConfig(polls: Seq[Poll], measMap: MeasMap, cmdMap: Seq[CommandMap.Mapping], modbusAddress: Byte, ipAddress: String, port: Int, protocol: ProtocolType)

object ModbusConfig extends LazyLogging {

  def convertXml(marshaller: ModbusXmlMarshaller, file: EntityKeyValue): Option[ModbusConfig] = {
    if (file.getValue.hasByteArrayValue) {
      try {
        Some(convertXmlOrThrow(marshaller, file.getValue.getByteArrayValue.toByteArray))
      } catch {
        case ex: Throwable =>
          logger.warn("Couldn't unmarshal modbus configuration: " + ex)
          None
      }
    } else {
      None
    }
  }

  def convertXmlOrThrow(marshaller: ModbusXmlMarshaller, bytes: Array[Byte]): ModbusConfig = {

    val xml = marshaller.readMasterConfig(bytes)

    val polls = xml.getPolls.getPoll().map(PollConversion.convertPoll).toVector
    val meas = new MeasMap(
      Option(xml.getDiscreteInputMap).map(_.getMapping.toList).getOrElse(Nil),
      Option(xml.getCoilStatusMap).map(_.getMapping.toList).getOrElse(Nil),
      Option(xml.getInputRegisterMap).map(_.getMapping.toList).getOrElse(Nil),
      Option(xml.getHoldingRegisterMap).map(_.getMapping.toList).getOrElse(Nil))

    val commandMappings = Option(xml.getCommandMap).flatMap(m => Option(m.getMapping)).map(_.toVector).getOrElse(Seq())

    val address = xml.getTCPClient.getAddress
    val port = xml.getTCPClient.getPort

    ModbusConfig(polls, meas, commandMappings, xml.getStack.getAddress.toByte, address, port, xml.getProtocol.getType)
  }
}

class ModbusXmlMarshaller {

  private val jaxbContext = JAXBContext.newInstance("io.greenbus.modbus.xml")

  def readMasterConfig(bytes: Array[Byte]): Master = {
    val um = jaxbContext.createUnmarshaller
    val is = new ByteArrayInputStream(bytes)

    val obj = um.unmarshal(is)
    obj.asInstanceOf[Master]
  }
}