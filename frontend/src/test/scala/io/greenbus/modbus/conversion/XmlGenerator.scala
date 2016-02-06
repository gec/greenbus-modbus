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

import com.google.protobuf.ByteString
import io.greenbus.client.service.proto.Model.StoredValue
import io.greenbus.modbus.xml._
import io.greenbus.modbus.xml.Polls.Poll
import javax.xml.bind.JAXBContext
import java.io.StringWriter
import scala.collection.JavaConversions._

object XmlGenerator {

  def writeToString[A](value: A, klass: Class[A]): String = {
    val ctx = JAXBContext.newInstance(klass)
    val m = ctx.createMarshaller
    val sw = new StringWriter
    m.marshal(value, sw)
    sw.toString
  }

  def generateConfigFile: StoredValue = {
    val master = generate
    val bs = ByteString.copyFromUtf8(writeToString(master, classOf[Master]))
    StoredValue.newBuilder().setByteArrayValue(bs).build()
  }

  def generatePoll(typ: DataType, start: Int, count: Int, intervalMs: Int, timeoutMs: Int) = {
    val poll = new Poll
    poll.setType(typ)
    poll.setStart(start)
    poll.setCount(count)
    poll.setIntervalMs(intervalMs)
    poll.setTimeoutMs(timeoutMs)
    poll
  }

  def createToBool[A <: BooleanValue](b: A, index: Int, name: String) = {
    b.setIndex(index)
    b.setName("Greenbus" + name + index)
    b
  }

  def toNumber[A <: NumericValue](n: A, index: Int, name: String, conversion: Conversion, maskOpt: Option[String] = None) = {
    n.setIndex(index)
    n.setName("greenbus" + name + index)
    n.setType(conversion)
    maskOpt.foreach(mask => n.setBitMask(mask))
    n
  }

  def toNumberByName[A <: NumericValue](n: A, index: Int, name: String, conversion: Conversion, maskOpt: Option[String] = None, shiftRightOpt: Option[Int] = None) = {
    n.setIndex(index)
    n.setName(name)
    n.setType(conversion)
    maskOpt.foreach(mask => n.setBitMask(mask))
    shiftRightOpt.foreach(off => n.setShiftRight(off))
    n
  }

  def generate: Master = {
    val root = new Master()
    val stack = new Stack
    stack.setAddress(0x03.toShort)
    root.setStack(stack)

    val polls = new Polls
    polls.getPoll.add(generatePoll(DataType.DISCRETE_INPUT, 0, 20, 1000, 2000))
    polls.getPoll.add(generatePoll(DataType.COIL_STATUS, 0, 20, 1000, 2000))
    polls.getPoll.add(generatePoll(DataType.INPUT_REGISTER, 0, 20, 5000, 2000))
    polls.getPoll.add(generatePoll(DataType.HOLDING_REGISTER, 0, 20, 5000, 2000))
    root.setPolls(polls)

    val di = new DiscreteInputMap
    di.getMapping.addAll((0 to 5).map(i => createToBool(new DiscreteInputMap.Mapping, i, "DiscreteInput")))
    root.setDiscreteInputMap(di)

    val cs = new CoilStatusMap
    cs.getMapping.addAll((0 to 5).map(i => createToBool(new CoilStatusMap.Mapping, i, "CoilStatus")))
    root.setCoilStatusMap(cs)

    val ir = new InputRegisterMap
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 0, "InputRegister", Conversion.S_INT_16))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 1, "InputRegister", Conversion.U_INT_16))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 2, "InputRegister", Conversion.S_INT_32_BE))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 4, "InputRegister", Conversion.S_INT_32_LE))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 6, "InputRegister", Conversion.U_INT_32_BE))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 8, "InputRegister", Conversion.U_INT_32_LE))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 10, "InputRegister", Conversion.FLOAT_32_BE))
    ir.getMapping.add(toNumber(new InputRegisterMap.Mapping, 12, "InputRegister", Conversion.FLOAT_32_LE))
    root.setInputRegisterMap(ir)

    val hr = new HoldingRegisterMap
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 0, "HoldingRegister", Conversion.S_INT_16))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 1, "HoldingRegister", Conversion.U_INT_16))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 2, "HoldingRegister", Conversion.S_INT_32_BE))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 4, "HoldingRegister", Conversion.S_INT_32_LE))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 6, "HoldingRegister", Conversion.U_INT_32_BE))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 8, "HoldingRegister", Conversion.U_INT_32_LE))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 10, "HoldingRegister", Conversion.FLOAT_32_BE))
    hr.getMapping.add(toNumber(new HoldingRegisterMap.Mapping, 12, "HoldingRegister", Conversion.FLOAT_32_LE))
    root.setHoldingRegisterMap(hr)

    root
  }
}